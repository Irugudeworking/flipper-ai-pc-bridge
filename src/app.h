#pragma once

#include "protocol/protocol.h"
#include "protocol/sequence_window.h"
#include "queue/command_queue.h"
#include "hid/hid_executor.h"

#include <furi.h>
#include <gui/gui.h>
#include <gui/view_port.h>
#include <rpc/rpc_app.h>

#define BRIDGE_BATCH_MAX_ACTIONS 16U
#define BRIDGE_TEXT_MAX_LENGTH 96U
#define BRIDGE_CDC_RX_PACKET_SIZE 64U

typedef struct {
    uint8_t length;
    uint8_t bytes[BRIDGE_CDC_RX_PACKET_SIZE];
} BridgeCdcPacket;

typedef enum {
    BridgeStateBoot,
    BridgeStateIdle,
    BridgeStatePhoneConnected,
    BridgeStateUsbConnecting,
    BridgeStateReady,
    BridgeStateExecuting,
    BridgeStateWaiting,
    BridgeStateReceivingFile,
    BridgeStateSendingFile,
    BridgeStatePaused,
    BridgeStateError,
    BridgeStateEmergencyStop,
} BridgeState;

typedef struct BridgeApp {
    BridgeCommandQueue* command_queue;
    FuriMessageQueue* input_queue;
    FuriMessageQueue* cdc_rx_queue;
    FuriMutex* rpc_mutex;
    FuriMutex* command_mutex;
    FuriMutex* transfer_mutex;
    FuriEventFlag* wake_event;
    BridgeHidExecutor hid;
    RpcAppSystem* rpc_context;
    Gui* gui;
    ViewPort* view_port;
    volatile BridgeState state;
    BridgeSequenceWindow sequence_window;
    uint8_t completed_actions;
    volatile bool phone_connected;
    bool usb_connected;
    bool paused;
    volatile bool running;
    volatile bool stop_requested;
    volatile bool session_closed;
    volatile bool rpc_context_available;
    volatile bool cdc_connected;
    volatile bool cdc_overflow;
    volatile bool usb_cdc_active;
    bool file_reply_pending;
    uint8_t file_reply_type;
    uint32_t file_reply_id;
    uint32_t file_reply_offset;
    uint32_t outgoing_sequence;
    volatile bool safety_event_pending;
    bool exit_requested;
    char current_action[24];
} BridgeApp;

void bridge_app_draw(Canvas* canvas, void* context);
void bridge_app_input(InputEvent* event, void* context);
int32_t flipper_ai_bridge_app(void* args);
