#pragma once

#include "../protocol/protocol.h"

#include <stdbool.h>
#include <stdint.h>

#define BRIDGE_COMMAND_QUEUE_CAPACITY 32U

typedef struct {
    uint8_t type;
    uint32_t sequence_id;
    uint16_t payload_length;
    uint8_t payload[BRIDGE_MAX_PAYLOAD];
} BridgeCommand;

typedef struct {
    BridgeCommand items[BRIDGE_COMMAND_QUEUE_CAPACITY];
    uint8_t read_index;
    uint8_t write_index;
    uint8_t count;
} BridgeCommandQueue;

void bridge_command_queue_init(BridgeCommandQueue* queue);
bool bridge_command_queue_push(BridgeCommandQueue* queue, const BridgeCommand* command);
bool bridge_command_queue_pop(BridgeCommandQueue* queue, BridgeCommand* command);
void bridge_command_queue_clear(BridgeCommandQueue* queue);
uint8_t bridge_command_queue_count(const BridgeCommandQueue* queue);
