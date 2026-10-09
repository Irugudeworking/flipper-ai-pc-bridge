#include "rpc_transport.h"

#include "../protocol/messages.h"
#include "../safety/safety.h"

#include <string.h>

static bool bridge_queue_command(BridgeApp* app, const BridgeFrame* frame) {
    BridgeCommand command = {
        .type = frame->type,
        .sequence_id = frame->sequence_id,
        .payload_length = frame->payload_length,
    };
    if(frame->payload_length) memcpy(command.payload, frame->payload, frame->payload_length);
    furi_mutex_acquire(app->command_mutex, FuriWaitForever);
    bool queued = bridge_command_queue_push(app->command_queue, &command);
    furi_mutex_release(app->command_mutex);
    if(queued) furi_event_flag_set(app->wake_event, 1U);
    return queued;
}

static void bridge_rpc_session_stop(BridgeApp* app) {
    app->phone_connected = false;
    app->session_closed = true;
    app->stop_requested = true;
    furi_event_flag_set(app->wake_event, 1U);
}

static void bridge_schedule_safety_stop(BridgeApp* app) {
    app->stop_requested = true;
    app->safety_event_pending = true;
    app->state = BridgeStateError;
    furi_event_flag_set(app->wake_event, 1U);
}

void bridge_rpc_event(const RpcAppSystemEvent* event, void* context) {
    furi_assert(event);
    BridgeApp* app = context;
    furi_assert(app);

    if(event->type == RpcAppEventTypeSessionClose) {
        furi_mutex_acquire(app->rpc_mutex, FuriWaitForever);
        app->rpc_context_available = false;
        app->rpc_context = NULL;
        furi_mutex_release(app->rpc_mutex);
        bridge_rpc_session_stop(app);
        return;
    }

    if(event->type == RpcAppEventTypeAppExit) {
        app->running = false;
        app->stop_requested = true;
        app->safety_event_pending = true;
        furi_event_flag_set(app->wake_event, 1U);
        if(app->rpc_context_available && app->rpc_context) {
            rpc_system_app_confirm(app->rpc_context, true);
        }
        return;
    }

    if(event->type != RpcAppEventTypeDataExchange ||
       event->data.type != RpcAppSystemEventDataTypeBytes) {
        bridge_schedule_safety_stop(app);
        if(app->rpc_context_available) rpc_system_app_confirm(app->rpc_context, false);
        return;
    }

    BridgeFrame frame;
    BridgeDecodeResult decoded = bridge_frame_decode(
        event->data.bytes.ptr, event->data.bytes.size, &frame);
    if(decoded == BridgeDecodeOk && !bridge_sequence_window_accept(&app->sequence_window, frame.sequence_id)) {
        uint8_t payload[4] = {BridgeResultInvalid, 0, 1, 0};
        bridge_rpc_send(app, BridgeMsgError, frame.sequence_id, payload, sizeof(payload));
        bridge_schedule_safety_stop(app);
        if(app->rpc_context_available) rpc_system_app_confirm(app->rpc_context, false);
        return;
    }
    if(decoded == BridgeDecodeOk &&
       (frame.type == BridgeMsgFileAck || frame.type == BridgeMsgFileNack)) {
        if(frame.payload_length != 8) {
            uint8_t payload[4] = {BridgeResultInvalid, 0, 2, 0};
            bridge_rpc_send(app, BridgeMsgError, frame.sequence_id, payload, sizeof(payload));
            bridge_schedule_safety_stop(app);
            if(app->rpc_context_available) rpc_system_app_confirm(app->rpc_context, false);
            return;
        }
        furi_mutex_acquire(app->transfer_mutex, FuriWaitForever);
        app->file_reply_type = frame.type;
        app->file_reply_id = (uint32_t)frame.payload[0] |
                             ((uint32_t)frame.payload[1] << 8U) |
                             ((uint32_t)frame.payload[2] << 16U) |
                             ((uint32_t)frame.payload[3] << 24U);
        app->file_reply_offset = (uint32_t)frame.payload[4] |
                                 ((uint32_t)frame.payload[5] << 8U) |
                                 ((uint32_t)frame.payload[6] << 16U) |
                                 ((uint32_t)frame.payload[7] << 24U);
        app->file_reply_pending = true;
        furi_mutex_release(app->transfer_mutex);
        furi_event_flag_set(app->wake_event, 1U);
        if(app->rpc_context_available) rpc_system_app_confirm(app->rpc_context, true);
        return;
    }
    bool urgent_stop = decoded == BridgeDecodeOk &&
                       (frame.type == BridgeMsgCancel || frame.type == BridgeMsgEmergencyStop);
    if(urgent_stop) {
        furi_mutex_acquire(app->command_mutex, FuriWaitForever);
        bridge_command_queue_clear(app->command_queue);
        furi_mutex_release(app->command_mutex);
        app->stop_requested = true;
        app->safety_event_pending = true;
        furi_event_flag_set(app->wake_event, 1U);
    }
    bool queued = decoded == BridgeDecodeOk &&
                  (urgent_stop || bridge_queue_command(app, &frame));
    if(queued && frame.type == BridgeMsgHello) app->phone_connected = true;
    if(decoded != BridgeDecodeOk) {
        uint32_t sequence = 0;
        if(event->data.bytes.size >= 8 && event->data.bytes.ptr[0] == BRIDGE_MAGIC_0 &&
           event->data.bytes.ptr[1] == BRIDGE_MAGIC_1) {
            const uint8_t* bytes = event->data.bytes.ptr + 4;
            sequence = (uint32_t)bytes[0] | ((uint32_t)bytes[1] << 8U) |
                       ((uint32_t)bytes[2] << 16U) | ((uint32_t)bytes[3] << 24U);
        }
        uint8_t payload[4] = {
            decoded == BridgeDecodeVersion || decoded == BridgeDecodeUnknownType ?
                BridgeResultUnsupported : BridgeResultInvalid,
            0,
            (uint8_t)decoded,
            0,
        };
        bridge_rpc_send(app, BridgeMsgError, sequence, payload, sizeof(payload));
        bridge_schedule_safety_stop(app);
    } else if(!queued) {
        app->state = BridgeStateError;
        uint8_t payload[4] = {BridgeResultBusy, 0, 0, 0};
        bridge_rpc_send(app, BridgeMsgError, frame.sequence_id, payload, sizeof(payload));
        bridge_schedule_safety_stop(app);
    }
    if(app->rpc_context_available) rpc_system_app_confirm(app->rpc_context, queued);
}

bool bridge_rpc_send(
    BridgeApp* app,
    uint8_t type,
    uint32_t sequence_id,
    const uint8_t* payload,
    uint16_t payload_length) {
    if(!app || payload_length > BRIDGE_MAX_PAYLOAD) return false;
    uint8_t packet[BRIDGE_MAX_FRAME];
    size_t packet_length = 0;
    BridgeFrame frame = {
        .version = BRIDGE_PROTOCOL_VERSION,
        .type = type,
        .sequence_id = sequence_id,
        .payload_length = payload_length,
        .flags = 0,
        .payload = payload,
    };
    if(bridge_frame_encode(&frame, packet, sizeof(packet), &packet_length) != BridgeDecodeOk) {
        return false;
    }

    furi_mutex_acquire(app->rpc_mutex, FuriWaitForever);
    bool available = app->rpc_context_available && app->rpc_context;
    if(available) rpc_system_app_exchange_data(app->rpc_context, packet, packet_length);
    furi_mutex_release(app->rpc_mutex);
    return available;
}
