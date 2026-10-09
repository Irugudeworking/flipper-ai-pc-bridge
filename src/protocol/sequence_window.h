#pragma once

#include <stdbool.h>
#include <stdint.h>

#define BRIDGE_SEQUENCE_WINDOW_SIZE 32U

typedef struct {
    uint32_t values[BRIDGE_SEQUENCE_WINDOW_SIZE];
    uint8_t cursor;
    uint8_t count;
} BridgeSequenceWindow;

bool bridge_sequence_window_accept(BridgeSequenceWindow* window, uint32_t sequence_id);
