#pragma once

#include "messages.h"

#include <stdbool.h>
#include <stdint.h>

#define BRIDGE_ACTION_BATCH_MAX 16U
#define BRIDGE_ACTION_TEXT_MAX 96U
#define BRIDGE_ACTION_DURATION_MAX_MS 5000U
#define BRIDGE_ACTION_BATCH_DURATION_MAX_MS 30000U

typedef struct {
    uint8_t type;
    uint16_t payload_length;
    const uint8_t* payload;
} BridgeActionView;

bool bridge_action_payload_valid(uint8_t type, const uint8_t* payload, uint16_t length);
bool bridge_action_batch_parse(
    const uint8_t* payload,
    uint16_t length,
    BridgeActionView* actions,
    uint8_t actions_capacity,
    uint8_t* action_count);
