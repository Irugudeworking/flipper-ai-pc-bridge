#include "../app.h"

#include <gui/canvas.h>

#include <stdio.h>

static const char* state_name(BridgeState state) {
    switch(state) {
    case BridgeStateBoot: return "BOOT";
    case BridgeStateIdle: return "IDLE";
    case BridgeStatePhoneConnected: return "PHONE CONNECTED";
    case BridgeStateUsbConnecting: return "USB CONNECTING";
    case BridgeStateReady: return "READY";
    case BridgeStateExecuting: return "RUNNING";
    case BridgeStateWaiting: return "WAITING";
    case BridgeStateReceivingFile: return "RECEIVING FILE";
    case BridgeStateSendingFile: return "SENDING FILE";
    case BridgeStatePaused: return "PAUSED";
    case BridgeStateError: return "ERROR";
    case BridgeStateEmergencyStop: return "STOPPED";
    default: return "UNKNOWN";
    }
}

void bridge_app_draw(Canvas* canvas, void* context) {
    BridgeApp* app = context;
    canvas_clear(canvas);
    canvas_set_font(canvas, FontPrimary);
    canvas_draw_str(canvas, 2, 10, "AI PC BRIDGE");
    canvas_set_font(canvas, FontSecondary);
    canvas_draw_str(canvas, 2, 23, app->phone_connected ? "PHONE: CONNECTED" : "PHONE: WAITING");
    if(app->usb_cdc_active) {
        canvas_draw_str(canvas, 2, 34, app->cdc_connected ? "USB: CDC / CONNECTED" : "USB: CDC / WAITING");
    } else {
        canvas_draw_str(canvas, 2, 34, app->usb_connected ? "USB: HID / CONNECTED" : "USB: HID / WAITING");
    }
    canvas_draw_str(canvas, 2, 45, state_name(app->state));
    if(app->state == BridgeStateExecuting || app->state == BridgeStateWaiting) {
        canvas_draw_str(canvas, 2, 56, app->current_action);
        canvas_draw_str(canvas, 87, 56, "OK PAUSE");
    } else {
        canvas_draw_str(canvas, 2, 56, "Queue:");
        char queue_count[6];
        furi_mutex_acquire(app->command_mutex, FuriWaitForever);
        uint8_t count = bridge_command_queue_count(app->command_queue);
        furi_mutex_release(app->command_mutex);
        snprintf(queue_count, sizeof(queue_count), "%u", count);
        canvas_draw_str(canvas, 32, 56, queue_count);
        canvas_draw_str(canvas, 58, 56, "[OK] PAUSE");
    }
    canvas_draw_str(canvas, 2, 63, "BACK EXIT / HOLD STOP");
}

void bridge_app_input(InputEvent* event, void* context) {
    BridgeApp* app = context;
    if(!event || !app) return;
    if(event->key == InputKeyBack && event->type == InputTypeLong) {
        app->safety_event_pending = true;
        app->stop_requested = true;
        bridge_hid_release_all();
        furi_event_flag_set(app->wake_event, 1U);
        return;
    }
    furi_message_queue_put(app->input_queue, event, 0);
}
