#pragma once

#include <stdint.h>

/* Stable v1 message identifiers carried in the RPC AppData channel. */
typedef enum {
    BridgeMsgHello = 1,
    BridgeMsgHelloAck = 2,
    BridgeMsgGetCapabilities = 3,
    BridgeMsgCapabilities = 4,
    BridgeMsgKeyDown = 5,
    BridgeMsgKeyUp = 6,
    BridgeMsgKeyTap = 7,
    BridgeMsgTextInput = 8,
    BridgeMsgMouseMove = 9,
    BridgeMsgMouseClick = 10,
    BridgeMsgMouseScroll = 11,
    BridgeMsgHotkey = 12,
    BridgeMsgWait = 13,
    BridgeMsgRequestScreenshot = 14,
    BridgeMsgScreenshotBegin = 15,
    BridgeMsgScreenshotChunk = 16,
    BridgeMsgScreenshotEnd = 17,
    BridgeMsgFileAck = 18,
    BridgeMsgFileNack = 19,
    BridgeMsgActionResult = 20,
    BridgeMsgPing = 21,
    BridgeMsgPong = 22,
    BridgeMsgCancel = 23,
    BridgeMsgEmergencyStop = 24,
    BridgeMsgError = 25,
    BridgeMsgActionBatch = 26,
} BridgeMessageType;

typedef enum {
    BridgeResultSuccess = 0,
    BridgeResultFailed = 1,
    BridgeResultTimeout = 2,
    BridgeResultCancelled = 3,
    BridgeResultUnsupported = 4,
    BridgeResultInvalid = 5,
    BridgeResultBusy = 6,
    BridgeResultUsbUnavailable = 7,
} BridgeResult;

typedef enum {
    BridgeActionKeyTap = 1,
    BridgeActionHotkey = 2,
    BridgeActionTextInput = 3,
    BridgeActionMouseMove = 4,
    BridgeActionMouseClick = 5,
    BridgeActionMouseScroll = 6,
    BridgeActionWait = 7,
    BridgeActionRequestScreenshot = 8,
    BridgeActionFinish = 9,
} BridgeActionType;
