#include "app.h"

#include "hid/hid_executor.h"
#include "protocol/actions.h"
#include "protocol/messages.h"
#include "safety/safety.h"
#include "transport/rpc_transport.h"
#include "transfer/screenshot.h"
#include "transport/usb_transport.h"

#include <furi_hal.h>
#include <gui/canvas.h>
#include <gui/view_port.h>
#include <storage/storage.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define ACTION_TIMEOUT_MAX_MS 5000U
#define BATCH_TIMEOUT_MAX_MS 30000U
#define WAIT_POLL_MS 20U
#define BRIDGE_LAUNCH_DIR "/ext/apps_data/flipper_ai_bridge"
#define BRIDGE_LAUNCH_REQUEST BRIDGE_LAUNCH_DIR "/manual_launch"

/* A menu launch cannot acquire the BLE RPC context directly. Leave a one-shot
 * request for the paired phone, which restarts this FAP with its RPC context. */
static bool request_manual_launch(void) {
    Storage* storage = furi_record_open(RECORD_STORAGE);
    storage_common_mkdir(storage, BRIDGE_LAUNCH_DIR);
    File* file = storage_file_alloc(storage);
    bool opened = storage_file_open(file, BRIDGE_LAUNCH_REQUEST, FSAM_WRITE, FSOM_CREATE_ALWAYS);
    const char marker[] = "AI_BRIDGE_V1";
    bool written = opened && storage_file_write(file, marker, sizeof(marker)) == sizeof(marker);
    storage_file_close(file);
    storage_file_free(file);
    furi_record_close(RECORD_STORAGE);
    return written;
}

static void clear_manual_launch_request(void) {
    Storage* storage = furi_record_open(RECORD_STORAGE);
    storage_common_remove(storage, BRIDGE_LAUNCH_REQUEST);
    furi_record_close(RECORD_STORAGE);
}

static uint16_t read_u16(const uint8_t* data) {
    return (uint16_t)data[0] | ((uint16_t)data[1] << 8U);
}

static uint32_t read_u32(const uint8_t* data) {
    return (uint32_t)data[0] | ((uint32_t)data[1] << 8U) |
           ((uint32_t)data[2] << 16U) | ((uint32_t)data[3] << 24U);
}

static void write_u16(uint8_t* data, uint16_t value) {
    data[0] = (uint8_t)value;
    data[1] = (uint8_t)(value >> 8U);
}

static void write_u32(uint8_t* data, uint32_t value) {
    data[0] = (uint8_t)value;
    data[1] = (uint8_t)(value >> 8U);
    data[2] = (uint8_t)(value >> 16U);
    data[3] = (uint8_t)(value >> 24U);
}

static void bridge_queue_clear(void* context) {
    BridgeApp* app = context;
    if(app->command_queue) {
        furi_mutex_acquire(app->command_mutex, FuriWaitForever);
        bridge_command_queue_clear(app->command_queue);
        furi_mutex_release(app->command_mutex);
    }
}

static void bridge_release_hid(void* context) {
    UNUSED(context);
    bridge_hid_release_all();
}

static void bridge_clear_and_release(BridgeApp* app) {
    BridgeSafetyOps ops = {
        .clear_queue = bridge_queue_clear,
        .release_all = bridge_release_hid,
        .context = app,
    };
    bridge_safety_release(&ops);
}

static void send_result(BridgeApp* app, uint32_t sequence, BridgeResult result, uint8_t completed) {
    uint8_t payload[4] = {(uint8_t)result, completed, 0, 0};
    bridge_rpc_send(app, BridgeMsgActionResult, sequence, payload, sizeof(payload));
}

static void send_error(BridgeApp* app, uint32_t sequence, BridgeResult result, uint16_t detail) {
    uint8_t payload[4] = {(uint8_t)result, 0, 0, 0};
    write_u16(payload + 2, detail);
    bridge_rpc_send(app, BridgeMsgError, sequence, payload, sizeof(payload));
}

static bool pause_or_cancel(BridgeApp* app) {
    if(app->stop_requested) return false;
    if(!app->usb_cdc_active && app->usb_connected && !bridge_hid_connected()) {
        app->safety_event_pending = true;
        app->stop_requested = true;
        return false;
    }
    InputEvent event;
    while(furi_message_queue_get(app->input_queue, &event, 0) == FuriStatusOk) {
        if(event.key == InputKeyBack && event.type == InputTypeShort) {
            app->running = false;
            app->stop_requested = true;
            return false;
        }
        if(event.key == InputKeyBack && event.type == InputTypeLong) {
            app->safety_event_pending = true;
            app->stop_requested = true;
            return false;
        }
        if(event.key == InputKeyOk && event.type == InputTypeShort) {
            app->paused = !app->paused;
            app->state = app->paused ? BridgeStatePaused : BridgeStateExecuting;
        }
    }

    while(app->paused && !app->stop_requested) {
        app->state = BridgeStatePaused;
        if(furi_message_queue_get(app->input_queue, &event, WAIT_POLL_MS) == FuriStatusOk) {
            if(event.key == InputKeyBack && event.type == InputTypeShort) {
                app->running = false;
                app->stop_requested = true;
                return false;
            }
            if(event.key == InputKeyBack && event.type == InputTypeLong) {
                app->safety_event_pending = true;
                app->stop_requested = true;
                return false;
            }
            if(event.key == InputKeyOk && event.type == InputTypeShort) {
                app->paused = false;
                app->state = BridgeStateExecuting;
            }
        }
    }
    return !app->stop_requested;
}

static BridgeResult execute_wait(BridgeApp* app, uint32_t duration_ms) {
    if(duration_ms > ACTION_TIMEOUT_MAX_MS) return BridgeResultInvalid;
    app->state = BridgeStateWaiting;
    strlcpy(app->current_action, "Wait", sizeof(app->current_action));
    for(uint32_t elapsed = 0; elapsed < duration_ms; elapsed += WAIT_POLL_MS) {
        if(!pause_or_cancel(app)) return BridgeResultCancelled;
        uint32_t remain = duration_ms - elapsed;
        furi_delay_ms(MIN(WAIT_POLL_MS, remain));
    }
    app->state = BridgeStateExecuting;
    return BridgeResultSuccess;
}

static BridgeResult execute_action(BridgeApp* app, uint8_t type, const uint8_t* payload, uint16_t length) {
    if(!pause_or_cancel(app)) return BridgeResultCancelled;
    if(!bridge_action_payload_valid(type, payload, length)) return BridgeResultInvalid;
    switch(type) {
    case BridgeActionKeyTap:
        if(length != 2 || read_u16(payload) == 0) return BridgeResultInvalid;
        strlcpy(app->current_action, "Key Tap", sizeof(app->current_action));
        return bridge_hid_key_tap(read_u16(payload)) ? BridgeResultSuccess : BridgeResultFailed;
    case BridgeActionHotkey:
        if(length != 3 || (payload[0] & 0xF0U) || read_u16(payload + 1) == 0) return BridgeResultInvalid;
        strlcpy(app->current_action, "Hotkey", sizeof(app->current_action));
        return bridge_hid_hotkey(payload[0], read_u16(payload + 1)) ? BridgeResultSuccess : BridgeResultFailed;
    case BridgeActionTextInput:
        strlcpy(app->current_action, "Text Input", sizeof(app->current_action));
        for(uint16_t index = 0; index < length; ++index) {
            if(!pause_or_cancel(app)) {
                bridge_hid_release_all();
                return BridgeResultCancelled;
            }
            if(!bridge_hid_text(payload + index, 1)) return BridgeResultFailed;
        }
        return BridgeResultSuccess;
    case BridgeActionMouseMove:
        if(length != 4) return BridgeResultInvalid;
        strlcpy(app->current_action, "Mouse Move", sizeof(app->current_action));
        return bridge_hid_mouse_move((int16_t)read_u16(payload), (int16_t)read_u16(payload + 2)) ?
                   BridgeResultSuccess : BridgeResultFailed;
    case BridgeActionMouseClick:
        if(length != 1 || (payload[0] != 1 && payload[0] != 2 && payload[0] != 4)) return BridgeResultInvalid;
        strlcpy(app->current_action, "Mouse Click", sizeof(app->current_action));
        return bridge_hid_mouse_click(payload[0]) ? BridgeResultSuccess : BridgeResultFailed;
    case BridgeActionMouseScroll:
        if(length != 2) return BridgeResultInvalid;
        strlcpy(app->current_action, "Mouse Scroll", sizeof(app->current_action));
        return bridge_hid_mouse_scroll((int16_t)read_u16(payload)) ? BridgeResultSuccess : BridgeResultFailed;
    case BridgeActionWait:
        if(length != 4) return BridgeResultInvalid;
        return execute_wait(app, read_u32(payload));
    case BridgeActionRequestScreenshot:
        return BridgeResultUnsupported; /* Screenshot transfer is a standalone request. */
    case BridgeActionFinish:
        if(length != 0) return BridgeResultInvalid;
        app->running = false;
        return BridgeResultSuccess;
    default:
        return BridgeResultUnsupported;
    }
}

static BridgeResult execute_batch(BridgeApp* app, const BridgeCommand* command, uint8_t* completed) {
    BridgeActionView actions[BRIDGE_BATCH_MAX_ACTIONS];
    uint8_t count = 0;
    if(!bridge_action_batch_parse(
           command->payload,
           command->payload_length,
           actions,
           BRIDGE_BATCH_MAX_ACTIONS,
           &count)) {
        return BridgeResultInvalid;
    }
    *completed = 0;
    uint32_t started_at = furi_get_tick();
    uint32_t duration_limit = furi_ms_to_ticks(BATCH_TIMEOUT_MAX_MS);
    for(uint8_t index = 0; index < count; ++index) {
        if((uint32_t)(furi_get_tick() - started_at) > duration_limit) return BridgeResultTimeout;
        BridgeResult result = execute_action(
            app, actions[index].type, actions[index].payload, actions[index].payload_length);
        if(result != BridgeResultSuccess) return result;
        (*completed)++;
        if((uint32_t)(furi_get_tick() - started_at) > duration_limit) return BridgeResultTimeout;
    }
    return BridgeResultSuccess;
}

static void fill_capabilities(BridgeApp* app, uint32_t sequence) {
    uint8_t caps[14] = {0};
    write_u16(caps, BRIDGE_PROTOCOL_VERSION);
    write_u32(caps + 2, (1U << 0U) | (1U << 1U) | (1U << 5U) | (1U << 6U));
    write_u16(caps + 6, BRIDGE_MAX_FRAME);
    caps[8] = BRIDGE_BATCH_MAX_ACTIONS;
    caps[9] = BRIDGE_TEXT_MAX_LENGTH;
    write_u32(caps + 10, 2U * 1024U * 1024U); /* User-started CDC helper experiment only. */
    bridge_rpc_send(app, BridgeMsgCapabilities, sequence, caps, sizeof(caps));
}

static void process_command(BridgeApp* app, const BridgeCommand* command) {
    FURI_LOG_I("AIBridge", "command type=%u seq=%lu state=%u", command->type, command->sequence_id, app->state);
    if(command->type == BridgeMsgHello) {
        uint8_t payload[2] = {BRIDGE_PROTOCOL_VERSION, 0};
        bridge_rpc_send(app, BridgeMsgHelloAck, command->sequence_id, payload, sizeof(payload));
        app->phone_connected = true;
        app->state = BridgeStateReady;
        send_result(app, command->sequence_id, BridgeResultSuccess, 0);
        FURI_LOG_I("AIBridge", "result seq=%lu code=%u", command->sequence_id, BridgeResultSuccess);
    } else if(command->type == BridgeMsgGetCapabilities) {
        fill_capabilities(app, command->sequence_id);
    } else if(command->type == BridgeMsgPing) {
        bridge_rpc_send(app, BridgeMsgPong, command->sequence_id, command->payload, command->payload_length);
    } else if(command->type == BridgeMsgCancel || command->type == BridgeMsgEmergencyStop) {
        bridge_clear_and_release(app);
        app->paused = false;
        app->stop_requested = false;
        app->state = command->type == BridgeMsgEmergencyStop ? BridgeStateEmergencyStop : BridgeStateReady;
        send_result(app, command->sequence_id, BridgeResultCancelled, 0);
        bridge_rpc_send(app, BridgeMsgEmergencyStop, command->sequence_id, NULL, 0);
    } else if(command->type == BridgeMsgRequestScreenshot) {
        send_result(app, command->sequence_id, BridgeResultSuccess, 0);
        BridgeResult result = bridge_screenshot_transfer(app, command->sequence_id);
        FURI_LOG_I("AIBridge", "screenshot seq=%lu result=%u", command->sequence_id, result);
    } else if(command->type == BridgeMsgActionBatch) {
        app->state = BridgeStateExecuting;
        uint8_t completed = 0;
        BridgeResult result = execute_batch(app, command, &completed);
        if(result != BridgeResultSuccess) bridge_hid_release_all();
        send_result(app, command->sequence_id, result, completed);
        FURI_LOG_I("AIBridge", "result seq=%lu code=%u actions=%u", command->sequence_id, result, completed);
        app->state = app->paused ? BridgeStatePaused : BridgeStateReady;
    } else if(command->type >= BridgeMsgKeyDown && command->type <= BridgeMsgWait) {
        uint8_t action_type = command->type == BridgeMsgKeyTap ? BridgeActionKeyTap :
                              command->type == BridgeMsgTextInput ? BridgeActionTextInput :
                              command->type == BridgeMsgMouseMove ? BridgeActionMouseMove :
                              command->type == BridgeMsgMouseClick ? BridgeActionMouseClick :
                              command->type == BridgeMsgMouseScroll ? BridgeActionMouseScroll :
                              command->type == BridgeMsgHotkey ? BridgeActionHotkey :
                              command->type == BridgeMsgWait ? BridgeActionWait : 0;
        if(command->type == BridgeMsgKeyDown) {
            BridgeResult result = command->payload_length == 2 &&
                                  bridge_action_payload_valid(
                                      BridgeActionKeyTap, command->payload, command->payload_length) &&
                                  bridge_hid_key_down(read_u16(command->payload)) ?
                                      BridgeResultSuccess : BridgeResultInvalid;
            if(result != BridgeResultSuccess) bridge_hid_release_all();
            send_result(app, command->sequence_id, result, 0);
            FURI_LOG_I("AIBridge", "result seq=%lu code=%u", command->sequence_id, result);
        } else if(command->type == BridgeMsgKeyUp) {
            BridgeResult result = command->payload_length == 2 &&
                                  bridge_action_payload_valid(
                                      BridgeActionKeyTap, command->payload, command->payload_length) &&
                                  bridge_hid_key_up(read_u16(command->payload)) ?
                                      BridgeResultSuccess : BridgeResultInvalid;
            if(result != BridgeResultSuccess) bridge_hid_release_all();
            send_result(app, command->sequence_id, result, 0);
            FURI_LOG_I("AIBridge", "result seq=%lu code=%u", command->sequence_id, result);
        } else {
            app->state = BridgeStateExecuting;
            BridgeResult result = execute_action(
                app, action_type, command->payload, command->payload_length);
            if(result != BridgeResultSuccess) bridge_hid_release_all();
            send_result(app, command->sequence_id, result, result == BridgeResultSuccess ? 1 : 0);
            FURI_LOG_I("AIBridge", "result seq=%lu code=%u", command->sequence_id, result);
            app->state = BridgeStateReady;
        }
    } else {
        bridge_hid_release_all();
        send_error(app, command->sequence_id, BridgeResultUnsupported, command->type);
    }
}

static void stop_emergency(BridgeApp* app) {
    bridge_clear_and_release(app);
    app->paused = false;
    app->stop_requested = false;
    app->state = BridgeStateEmergencyStop;
    app->current_action[0] = '\0';
    bridge_rpc_send(app, BridgeMsgEmergencyStop, 0, NULL, 0);
}

static bool parse_rpc_context(const char* args, RpcAppSystem** rpc_context) {
    if(!args || !rpc_context) return false;
    unsigned long address = 0;
    if(sscanf(args, "RPC %lx", &address) != 1 || address == 0) return false;
    *rpc_context = (RpcAppSystem*)address;
    return true;
}

int32_t flipper_ai_bridge_app(void* args) {
    RpcAppSystem* rpc_context = NULL;
    if(!parse_rpc_context(args, &rpc_context)) {
        return request_manual_launch() ? 0 : 1;
    }

    BridgeApp app = {0};
    app.state = BridgeStateBoot;
    app.running = true;
    strlcpy(app.current_action, "", sizeof(app.current_action));
    app.rpc_mutex = furi_mutex_alloc(FuriMutexTypeNormal);
    app.command_mutex = furi_mutex_alloc(FuriMutexTypeNormal);
    app.transfer_mutex = furi_mutex_alloc(FuriMutexTypeNormal);
    app.wake_event = furi_event_flag_alloc();
    app.command_queue = malloc(sizeof(BridgeCommandQueue));
    app.cdc_rx_queue = furi_message_queue_alloc(16, sizeof(BridgeCdcPacket));
    app.input_queue = furi_message_queue_alloc(8, sizeof(InputEvent));
    if(!app.rpc_mutex || !app.command_mutex || !app.transfer_mutex || !app.wake_event ||
       !app.command_queue || !app.cdc_rx_queue || !app.input_queue) {
        FURI_LOG_E("AIBridge", "allocation failed during startup");
        free(app.command_queue);
        if(app.input_queue) furi_message_queue_free(app.input_queue);
        if(app.cdc_rx_queue) furi_message_queue_free(app.cdc_rx_queue);
        if(app.wake_event) furi_event_flag_free(app.wake_event);
        if(app.command_mutex) furi_mutex_free(app.command_mutex);
        if(app.transfer_mutex) furi_mutex_free(app.transfer_mutex);
        if(app.rpc_mutex) furi_mutex_free(app.rpc_mutex);
        return 1;
    }
    bridge_command_queue_init(app.command_queue);
    app.outgoing_sequence = 0x80000000U;
    app.gui = furi_record_open(RECORD_GUI);

    app.view_port = view_port_alloc();
    view_port_draw_callback_set(app.view_port, bridge_app_draw, &app);
    view_port_input_callback_set(app.view_port, bridge_app_input, &app);
    gui_add_view_port(app.gui, app.view_port, GuiLayerFullscreen);

    if(!bridge_hid_init(&app.hid)) {
        app.state = BridgeStateError;
    } else {
        app.state = BridgeStateUsbConnecting;
    }

    app.rpc_context = rpc_context;
    app.rpc_context_available = true;
    app.phone_connected = true;
    rpc_system_app_set_callback(rpc_context, bridge_rpc_event, &app);
    clear_manual_launch_request();
    rpc_system_app_send_started(rpc_context);
    app.state = BridgeStatePhoneConnected;

    while(app.running) {
        bool usb_was_connected = app.usb_connected;
        app.usb_connected = bridge_hid_connected();
        if(usb_was_connected && !app.usb_connected && !app.usb_cdc_active) {
            app.stop_requested = true;
            app.safety_event_pending = true;
        }
        if(app.stop_requested && app.safety_event_pending) {
            stop_emergency(&app);
            app.safety_event_pending = false;
        } else if(app.stop_requested && app.session_closed) {
            bridge_clear_and_release(&app);
            app.stop_requested = false;
            app.phone_connected = false;
            app.state = BridgeStateIdle;
            app.running = false;
        }

        InputEvent input;
        while(furi_message_queue_get(app.input_queue, &input, 0) == FuriStatusOk) {
            if(input.key == InputKeyBack && input.type == InputTypeLong) {
                app.safety_event_pending = true;
                app.stop_requested = true;
                stop_emergency(&app);
                app.safety_event_pending = false;
            } else if(input.key == InputKeyBack && input.type == InputTypeShort) {
                app.running = false;
                break;
            } else if(input.key == InputKeyOk && input.type == InputTypeShort) {
                app.paused = !app.paused;
                app.state = app.paused ? BridgeStatePaused : BridgeStateReady;
            }
        }
        if(!app.running) break;

        BridgeCommand command;
        bool have_command = false;
        if(!app.paused && !app.stop_requested) {
            furi_mutex_acquire(app.command_mutex, FuriWaitForever);
            have_command = bridge_command_queue_pop(app.command_queue, &command);
            furi_mutex_release(app.command_mutex);
        }
        if(have_command) {
            process_command(&app, &command);
        } else {
            if(!app.paused) {
                furi_event_flag_wait(app.wake_event, 1U, FuriFlagWaitAny, WAIT_POLL_MS);
            } else {
                furi_delay_ms(WAIT_POLL_MS);
            }
        }
        if(app.state == BridgeStateUsbConnecting && app.usb_connected) app.state = BridgeStateReady;
        view_port_update(app.view_port);
    }

    bridge_clear_and_release(&app);
    if(app.rpc_context_available && app.rpc_context) {
        rpc_system_app_send_exited(app.rpc_context);
        rpc_system_app_set_callback(app.rpc_context, NULL, NULL);
    }
    if(app.usb_cdc_active) bridge_usb_restore_default();
    if(app.hid.initialized) bridge_hid_deinit(&app.hid);
    gui_remove_view_port(app.gui, app.view_port);
    view_port_free(app.view_port);
    furi_record_close(RECORD_GUI);
    furi_message_queue_free(app.input_queue);
    furi_message_queue_free(app.cdc_rx_queue);
    free(app.command_queue);
    furi_event_flag_free(app.wake_event);
    furi_mutex_free(app.command_mutex);
    furi_mutex_free(app.transfer_mutex);
    furi_mutex_free(app.rpc_mutex);
    return 0;
}
