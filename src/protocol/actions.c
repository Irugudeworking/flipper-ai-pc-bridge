#include "actions.h"

static uint16_t read_u16(const uint8_t* data) {
    return (uint16_t)data[0] | ((uint16_t)data[1] << 8U);
}

static uint32_t read_u32(const uint8_t* data) {
    return (uint32_t)data[0] | ((uint32_t)data[1] << 8U) |
           ((uint32_t)data[2] << 16U) | ((uint32_t)data[3] << 24U);
}

bool bridge_action_payload_valid(uint8_t type, const uint8_t* payload, uint16_t length) {
    if(length && !payload) return false;
    switch(type) {
    case BridgeActionKeyTap:
        return length == 2 && read_u16(payload) > 0 && read_u16(payload) <= 0xE7;
    case BridgeActionHotkey:
        return length == 3 && (payload[0] & 0xF0U) == 0 && read_u16(payload + 1) > 0 &&
               read_u16(payload + 1) <= 0xE7;
    case BridgeActionTextInput:
        if(length > BRIDGE_ACTION_TEXT_MAX) return false;
        for(uint16_t i = 0; i < length; ++i) {
            if(payload[i] < 0x20U || payload[i] > 0x7EU) return false;
        }
        return true;
    case BridgeActionMouseMove: {
        if(length != 4) return false;
        int16_t dx = (int16_t)read_u16(payload);
        int16_t dy = (int16_t)read_u16(payload + 2);
        return dx >= -2048 && dx <= 2048 && dy >= -2048 && dy <= 2048;
    }
    case BridgeActionMouseClick:
        return length == 1 && (payload[0] == 1 || payload[0] == 2 || payload[0] == 4);
    case BridgeActionMouseScroll: {
        if(length != 2) return false;
        int16_t delta = (int16_t)read_u16(payload);
        return delta >= -120 && delta <= 120;
    }
    case BridgeActionWait:
        return length == 4 && read_u32(payload) <= BRIDGE_ACTION_DURATION_MAX_MS;
    case BridgeActionRequestScreenshot:
    case BridgeActionFinish:
        return length == 0;
    default:
        return false;
    }
}

bool bridge_action_batch_parse(
    const uint8_t* payload,
    uint16_t length,
    BridgeActionView* actions,
    uint8_t actions_capacity,
    uint8_t* action_count) {
    if(!payload || length == 0 || !actions || !action_count) return false;
    uint8_t count = payload[0];
    if(count == 0 || count > BRIDGE_ACTION_BATCH_MAX || count > actions_capacity) return false;
    uint16_t offset = 1;
    uint32_t total_wait = 0;
    for(uint8_t i = 0; i < count; ++i) {
        if((uint16_t)(length - offset) < 3) return false;
        uint8_t type = payload[offset];
        uint16_t data_length = read_u16(payload + offset + 1);
        offset += 3;
        if(data_length > (uint16_t)(length - offset)) return false;
        const uint8_t* data = payload + offset;
        if(!bridge_action_payload_valid(type, data, data_length)) return false;
        if(type == BridgeActionWait) total_wait += read_u32(data);
        if(total_wait > BRIDGE_ACTION_BATCH_DURATION_MAX_MS) return false;
        actions[i].type = type;
        actions[i].payload_length = data_length;
        actions[i].payload = data;
        offset += data_length;
    }
    if(offset != length) return false;
    *action_count = count;
    return true;
}
