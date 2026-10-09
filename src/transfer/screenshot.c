#include "screenshot.h"

#include "../protocol/messages.h"
#include "../transport/rpc_transport.h"
#include "../transport/usb_transport.h"

#include <furi.h>
#include <furi_hal_usb_hid.h>
#include <string.h>

#define SCREENSHOT_MAX_SIZE (2U * 1024U * 1024U)
#define SCREENSHOT_CHUNK_SIZE 200U
#define CDC_PACKET_SIZE 64U
#define USB_WAIT_TIMEOUT_MS 30000U
#define ACK_TIMEOUT_MS 5000U
#define MAX_RETRIES 3U

typedef struct {
    BridgeCdcPacket packet;
    uint8_t offset;
    uint32_t timeout_ticks;
} CdcReader;

static bool poll_emergency_input(BridgeApp* app);

/*
 * Fixed, one-shot Windows capture bootstrap. This is deliberately not part of
 * the AI action schema: it runs only after an explicit REQUEST_SCREENSHOT,
 * opens a visible (minimized to the taskbar) PowerShell window, and captures
 * only the signed-in visible desktop. It neither elevates nor changes policy.
 *
 * The Windows input language must map standard US HID keycodes to ASCII for
 * the Run command to work. PowerShell waits for Flipper's CDC re-enumeration,
 * then uses the existing bounded FBA1/FBR1/FBC1 screenshot framing.
 */
static const char windows_capture_command[] =
    "powershell -NoLogo -NoProfile -WindowStyle Minimized -Command \""
    "$ErrorActionPreference='Stop';"
    "$t=[DateTime]::UtcNow.AddSeconds(30);$n=$null;"
    "while(!$n -and [DateTime]::UtcNow -lt $t){"
    "$n=(Get-CimInstance Win32_SerialPort|Where-Object {$_.PNPDeviceID -match 'VID_0483&PID_5740'}|Select-Object -First 1 -ExpandProperty DeviceID);"
    "if(!$n){Start-Sleep -Milliseconds 200}};if(!$n){throw 'Flipper CDC unavailable'};"
    "$s=[IO.Ports.SerialPort]::new($n,115200,[IO.Ports.Parity]::None,8,[IO.Ports.StopBits]::One);"
    "$s.ReadTimeout=120000;$b=$null;$g=$null;$m=$null;"
    "try{$s.Open();while($s.BytesToRead -lt 4){Start-Sleep -Milliseconds 10};"
    "$r=New-Object byte[] 4;$s.Read($r,0,4)>$null;"
    "if([Text.Encoding]::ASCII.GetString($r)-ne 'FBA1'){throw 'Bad Flipper request'};"
    "Add-Type -AssemblyName System.Drawing;Add-Type -AssemblyName System.Windows.Forms;"
    "$v=[Windows.Forms.SystemInformation]::VirtualScreen;"
    "$b=[Drawing.Bitmap]::new($v.Width,$v.Height);$g=[Drawing.Graphics]::FromImage($b);"
    "$g.CopyFromScreen($v.Left,$v.Top,0,0,$b.Size);$m=[IO.MemoryStream]::new();"
    "$b.Save($m,[Drawing.Imaging.ImageFormat]::Png);$d=$m.ToArray();"
    "$c=[long]0xffffffffL;foreach($x in $d){$c=$c -bxor [long]$x;"
    "for($j=0;$j -lt 8;$j++){if($c -band 1){$c=($c -shr 1) -bxor 0xedb88320L}else{$c=$c -shr 1}}};"
    "$crc=[uint32](($c -bxor 0xffffffffL) -band 0xffffffffL);"
    "$h=New-Object byte[] 12;[Text.Encoding]::ASCII.GetBytes('FBR1').CopyTo($h,0);"
    "[BitConverter]::GetBytes([uint32]$d.Length).CopyTo($h,4);"
    "[BitConverter]::GetBytes($crc).CopyTo($h,8);$s.Write($h,0,12);"
    "for($o=0;$o -lt $d.Length;){while($s.BytesToRead -lt 4){Start-Sleep -Milliseconds 5};"
    "$q=New-Object byte[] 4;$s.Read($q,0,4)>$null;"
    "if([Text.Encoding]::ASCII.GetString($q)-ne 'FBC1'){throw 'Bad Flipper credit'};"
    "$z=[Math]::Min(200,$d.Length-$o);$s.Write($d,$o,$z);$o+=$z};"
    "while($s.BytesToRead -lt 4){Start-Sleep -Milliseconds 5};"
    "$q=New-Object byte[] 4;$s.Read($q,0,4)>$null;"
    "if([Text.Encoding]::ASCII.GetString($q)-ne 'FBA2'){throw 'Transfer not confirmed'}"
    "}finally{if($m){$m.Dispose()};if($g){$g.Dispose()};if($b){$b.Dispose()};$s.Dispose()}\"";

static bool launch_windows_capture(BridgeApp* app) {
    if(!app || !bridge_hid_connected()) return false;
    if(!bridge_hid_hotkey(0x08U, HID_KEYBOARD_R)) return false;
    furi_delay_ms(500);
    for(size_t index = 0; index < sizeof(windows_capture_command) - 1U; ++index) {
        if(!poll_emergency_input(app)) {
            bridge_hid_key_tap(HID_KEYBOARD_ESCAPE);
            return false;
        }
        uint8_t character = (uint8_t)windows_capture_command[index];
        if(!bridge_hid_text(&character, 1)) {
            bridge_hid_key_tap(HID_KEYBOARD_ESCAPE);
            return false;
        }
    }
    if(!bridge_hid_key_tap(HID_KEYBOARD_RETURN)) return false;
    furi_delay_ms(750);
    return !app->stop_requested;
}

static bool poll_emergency_input(BridgeApp* app) {
    InputEvent event;
    while(furi_message_queue_get(app->input_queue, &event, 0) == FuriStatusOk) {
        if(event.key == InputKeyBack && event.type == InputTypeLong) {
            app->safety_event_pending = true;
            app->stop_requested = true;
            bridge_hid_release_all();
            return false;
        }
    }
    return !app->stop_requested;
}

static uint32_t read_u32(const uint8_t* bytes) {
    return (uint32_t)bytes[0] | ((uint32_t)bytes[1] << 8U) |
           ((uint32_t)bytes[2] << 16U) | ((uint32_t)bytes[3] << 24U);
}

static void write_u32(uint8_t* bytes, uint32_t value) {
    bytes[0] = (uint8_t)value;
    bytes[1] = (uint8_t)(value >> 8U);
    bytes[2] = (uint8_t)(value >> 16U);
    bytes[3] = (uint8_t)(value >> 24U);
}

static uint32_t crc32_update(uint32_t state, const uint8_t* bytes, uint16_t length) {
    for(uint16_t index = 0; index < length; ++index) {
        state ^= bytes[index];
        for(uint8_t bit = 0; bit < 8; ++bit) {
            uint32_t mask = (uint32_t)-(int32_t)(state & 1U);
            state = (state >> 1U) ^ (0xEDB88320U & mask);
        }
    }
    return state;
}

static bool cdc_read_exact(BridgeApp* app, CdcReader* reader, uint8_t* output, uint16_t length) {
    uint16_t copied = 0;
    uint32_t waited_ms = 0;
    while(copied < length) {
        if(!poll_emergency_input(app) || app->cdc_overflow || !app->cdc_connected) return false;
        if(reader->offset >= reader->packet.length) {
            if(waited_ms >= reader->timeout_ticks) return false;
            FuriStatus status = furi_message_queue_get(app->cdc_rx_queue, &reader->packet, furi_ms_to_ticks(500U));
            if(status != FuriStatusOk) {
                waited_ms += 500;
                view_port_update(app->view_port);
                continue;
            }
            reader->offset = 0;
            if(reader->packet.length == 0 || reader->packet.length > CDC_PACKET_SIZE) return false;
        }
        uint16_t available = (uint16_t)(reader->packet.length - reader->offset);
        uint16_t needed = (uint16_t)(length - copied);
        uint16_t take = MIN(available, needed);
        memcpy(output + copied, reader->packet.bytes + reader->offset, take);
        copied += take;
        reader->offset = (uint8_t)(reader->offset + take);
    }
    return true;
}

static bool wait_phone_reply(
    BridgeApp* app,
    uint32_t file_id,
    uint8_t* type,
    uint32_t* next_offset,
    uint32_t timeout_ms) {
    for(uint32_t elapsed = 0; elapsed < timeout_ms; elapsed += 20U) {
        if(!poll_emergency_input(app) || !app->phone_connected) return false;
        furi_mutex_acquire(app->transfer_mutex, FuriWaitForever);
        bool pending = app->file_reply_pending;
        uint32_t reply_id = app->file_reply_id;
        if(pending) app->file_reply_pending = false;
        if(pending && reply_id == file_id) {
            *type = app->file_reply_type;
            *next_offset = app->file_reply_offset;
            furi_mutex_release(app->transfer_mutex);
            return true;
        }
        furi_mutex_release(app->transfer_mutex);
        furi_delay_ms(20);
    }
    return false;
}

static bool send_file_frame(BridgeApp* app, uint8_t type, const uint8_t* payload, uint16_t length) {
    uint32_t sequence = app->outgoing_sequence++;
    return bridge_rpc_send(app, type, sequence, payload, length);
}

static bool await_expected_offset(
    BridgeApp* app,
    uint32_t file_id,
    uint32_t expected,
    uint32_t retry_offset,
    uint8_t message_type,
    const uint8_t* payload,
    uint16_t payload_length) {
    for(uint8_t attempt = 0; attempt < MAX_RETRIES; ++attempt) {
        if(!send_file_frame(app, message_type, payload, payload_length)) return false;
        uint8_t reply_type = 0;
        uint32_t reply_offset = 0;
        if(wait_phone_reply(app, file_id, &reply_type, &reply_offset, ACK_TIMEOUT_MS)) {
            if(reply_type == BridgeMsgFileAck && reply_offset == expected) return true;
            if(reply_type == BridgeMsgFileNack && reply_offset == retry_offset) continue;
            return false;
        }
    }
    return false;
}

static void report_transfer_failure(BridgeApp* app, uint32_t sequence, uint16_t detail) {
    uint8_t payload[4] = {BridgeResultFailed, 0, (uint8_t)detail, (uint8_t)(detail >> 8U)};
    bridge_rpc_send(app, BridgeMsgError, sequence, payload, sizeof(payload));
    static const uint8_t failed[4] = {'F', 'B', 'E', '1'};
    bridge_usb_cdc_send(failed, sizeof(failed));
}

BridgeResult bridge_screenshot_transfer(BridgeApp* app, uint32_t request_sequence) {
    furi_check(app);
    app->state = BridgeStateExecuting;
    strlcpy(app->current_action, "Start Capture", sizeof(app->current_action));
    view_port_update(app->view_port);
    if(!launch_windows_capture(app)) {
        bridge_hid_release_all();
        report_transfer_failure(app, request_sequence, app->stop_requested ? 2 : 13);
        return app->stop_requested ? BridgeResultCancelled : BridgeResultFailed;
    }
    bridge_hid_release_all();
    bridge_hid_deinit(&app->hid);
    app->usb_connected = false; /* HID→CDC re-enumeration is an intentional USB disconnect. */
    app->usb_cdc_active = true;
    app->state = BridgeStateUsbConnecting;
    view_port_update(app->view_port);
    if(!bridge_usb_select_cdc(app)) {
        app->usb_cdc_active = false;
        bridge_hid_init(&app->hid);
        report_transfer_failure(app, request_sequence, 1);
        return BridgeResultFailed;
    }

    bool transfer_complete = false;
    uint32_t waited = 0;
    while(!app->cdc_connected && waited < USB_WAIT_TIMEOUT_MS && !app->stop_requested) {
        if(!poll_emergency_input(app)) break;
        furi_delay_ms(250);
        waited += 250;
        view_port_update(app->view_port);
    }
    if(!app->cdc_connected || app->stop_requested) {
        report_transfer_failure(app, request_sequence, app->stop_requested ? 2 : 3);
        goto cleanup;
    }

    app->state = BridgeStateReceivingFile;
    view_port_update(app->view_port);
    static const uint8_t ready[4] = {'F', 'B', 'A', '1'};
    bridge_usb_cdc_send(ready, sizeof(ready));
    CdcReader reader = {.offset = 0, .timeout_ticks = 30000U};
    uint8_t header[12];
    if(!cdc_read_exact(app, &reader, header, sizeof(header))) {
        report_transfer_failure(app, request_sequence, 4);
        goto cleanup;
    }
    if(memcmp(header, "FBR1", 4) != 0) {
        report_transfer_failure(app, request_sequence, 5);
        goto cleanup;
    }
    uint32_t total_size = read_u32(header + 4);
    uint32_t expected_crc = read_u32(header + 8);
    if(total_size < 8U || total_size > SCREENSHOT_MAX_SIZE) {
        report_transfer_failure(app, request_sequence, 6);
        goto cleanup;
    }

    uint8_t begin_payload[13];
    write_u32(begin_payload, request_sequence);
    write_u32(begin_payload + 4, total_size);
    begin_payload[8] = 1; /* PNG */
    write_u32(begin_payload + 9, expected_crc);
    if(!await_expected_offset(
           app, request_sequence, 0, 0, BridgeMsgScreenshotBegin, begin_payload, sizeof(begin_payload))) {
        report_transfer_failure(app, request_sequence, 7);
        goto cleanup;
    }

    uint32_t offset = 0;
    uint32_t crc_state = 0xFFFFFFFFU;
    uint8_t last_progress_bucket = 0;
    uint8_t signature[8] = {0};
    uint8_t data[SCREENSHOT_CHUNK_SIZE];
    while(offset < total_size) {
        uint16_t amount = (uint16_t)MIN(SCREENSHOT_CHUNK_SIZE, total_size - offset);
        static const uint8_t credit[4] = {'F', 'B', 'C', '1'};
        bridge_usb_cdc_send(credit, sizeof(credit));
        reader.timeout_ticks = 10000U;
        if(!cdc_read_exact(app, &reader, data, amount)) {
            report_transfer_failure(app, request_sequence, app->stop_requested ? 2 : 8);
            goto cleanup;
        }
        if(offset < sizeof(signature)) {
            uint32_t take = MIN((uint32_t)amount, (uint32_t)sizeof(signature) - offset);
            memcpy(signature + offset, data, take);
            if(offset + take == sizeof(signature) &&
               memcmp(signature, "\x89PNG\r\n\x1a\n", sizeof(signature)) != 0) {
                report_transfer_failure(app, request_sequence, 9);
                goto cleanup;
            }
        }
        crc_state = crc32_update(crc_state, data, amount);

        uint8_t chunk_payload[8 + SCREENSHOT_CHUNK_SIZE];
        write_u32(chunk_payload, request_sequence);
        write_u32(chunk_payload + 4, offset);
        memcpy(chunk_payload + 8, data, amount);
        if(!await_expected_offset(
               app,
               request_sequence,
               offset + amount,
               offset,
               BridgeMsgScreenshotChunk,
               chunk_payload,
               (uint16_t)(8U + amount))) {
            report_transfer_failure(app, request_sequence, 10);
            goto cleanup;
        }
        offset += amount;
        uint8_t progress_bucket = (uint8_t)((offset * 100U / total_size) / 10U);
        if(progress_bucket > last_progress_bucket) {
            last_progress_bucket = progress_bucket;
            FURI_LOG_I("AIBridge", "screenshot progress id=%lu bytes=%lu/%lu", request_sequence, offset, total_size);
        }
    }

    if((crc_state ^ 0xFFFFFFFFU) != expected_crc) {
        report_transfer_failure(app, request_sequence, 11);
        goto cleanup;
    }
    uint8_t end_payload[8];
    write_u32(end_payload, request_sequence);
    write_u32(end_payload + 4, expected_crc);
    if(!await_expected_offset(
           app,
           request_sequence,
           total_size,
           total_size,
           BridgeMsgScreenshotEnd,
           end_payload,
           sizeof(end_payload))) {
        report_transfer_failure(app, request_sequence, 12);
        goto cleanup;
    }
    static const uint8_t complete[4] = {'F', 'B', 'A', '2'};
    bridge_usb_cdc_send(complete, sizeof(complete));
    transfer_complete = true;
    app->state = BridgeStateReady;
    goto cleanup;

cleanup:
    bridge_usb_restore_default();
    app->usb_cdc_active = false;
    app->usb_connected = false; /* Ignore CDC→HID re-enumeration as a host loss. */
    app->cdc_connected = false;
    app->cdc_overflow = false;
    if(!bridge_hid_init(&app->hid)) app->state = BridgeStateError;
    else if(app->state != BridgeStateError) app->state = BridgeStateReady;
    return app->stop_requested ? BridgeResultCancelled :
           transfer_complete ? BridgeResultSuccess : BridgeResultFailed;
}
