#include "usb_transport.h"

#include "../app.h"

#include <furi_hal_usb.h>
#include <furi_hal_usb_cdc.h>
#include <furi_hal_usb_hid.h>

static void bridge_cdc_state_callback(void* context, CdcState state) {
    BridgeApp* app = context;
    if(!app) return;
    app->cdc_connected = state == CdcStateConnected;
    if(app->wake_event) furi_event_flag_set(app->wake_event, 1U);
}

static void bridge_cdc_rx_callback(void* context) {
    BridgeApp* app = context;
    if(!app || !app->cdc_rx_queue) return;
    BridgeCdcPacket packet = {0};
    int32_t length = furi_hal_cdc_receive(0, packet.bytes, sizeof(packet.bytes));
    if(length <= 0) return;
    packet.length = (uint8_t)length;
    if(furi_message_queue_put(app->cdc_rx_queue, &packet, 0) != FuriStatusOk) {
        app->cdc_overflow = true;
    }
    if(app->wake_event) furi_event_flag_set(app->wake_event, 1U);
}

static CdcCallbacks cdc_callbacks = {
    .tx_ep_callback = NULL,
    .rx_ep_callback = bridge_cdc_rx_callback,
    .state_callback = bridge_cdc_state_callback,
    .ctrl_line_callback = NULL,
    .config_callback = NULL,
};

bool bridge_usb_select_hid(void) {
    /* NULL selects the firmware's valid default HID VID/PID. A non-NULL
     * FuriHalUsbHidConfig copies its VID/PID verbatim into the descriptor. */
    return furi_hal_usb_set_config(&usb_hid, NULL);
}

bool bridge_usb_select_cdc(BridgeApp* app) {
    furi_assert(app);
    app->cdc_connected = false;
    app->cdc_overflow = false;
    furi_message_queue_reset(app->cdc_rx_queue);
    furi_hal_cdc_set_callbacks(0, &cdc_callbacks, app);
    if(!furi_hal_usb_set_config(&usb_cdc_single, NULL)) {
        furi_hal_cdc_set_callbacks(0, NULL, NULL);
        return false;
    }
    return true;
}

void bridge_usb_restore_default(void) {
    furi_hal_cdc_set_callbacks(0, NULL, NULL);
    furi_hal_usb_set_config(NULL, NULL);
}

void bridge_usb_cdc_send(const uint8_t* bytes, uint16_t length) {
    if(bytes && length) furi_hal_cdc_send(0, (uint8_t*)bytes, length);
}
