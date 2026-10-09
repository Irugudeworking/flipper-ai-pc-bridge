#include "protocol.h"

#include "messages.h"

#include <string.h>

static uint16_t read_u16_le(const uint8_t* value) {
    return (uint16_t)value[0] | ((uint16_t)value[1] << 8U);
}

static uint32_t read_u32_le(const uint8_t* value) {
    return (uint32_t)value[0] | ((uint32_t)value[1] << 8U) |
           ((uint32_t)value[2] << 16U) | ((uint32_t)value[3] << 24U);
}

static void write_u16_le(uint8_t* output, uint16_t value) {
    output[0] = (uint8_t)value;
    output[1] = (uint8_t)(value >> 8U);
}

static void write_u32_le(uint8_t* output, uint32_t value) {
    output[0] = (uint8_t)value;
    output[1] = (uint8_t)(value >> 8U);
    output[2] = (uint8_t)(value >> 16U);
    output[3] = (uint8_t)(value >> 24U);
}

static uint32_t frame_crc(const uint8_t* header, const uint8_t* payload, size_t payload_length) {
    uint32_t crc = 0xFFFFFFFFU;
    for(size_t part = 0; part < 2; ++part) {
        const uint8_t* bytes = part == 0 ? header + 2 : payload;
        size_t length = part == 0 ? 10U : payload_length;
        for(size_t index = 0; index < length; ++index) {
            crc ^= bytes[index];
            for(uint8_t bit = 0; bit < 8; ++bit) {
                uint32_t mask = (uint32_t)-(int32_t)(crc & 1U);
                crc = (crc >> 1U) ^ (0xEDB88320U & mask);
            }
        }
    }
    return ~crc;
}

bool bridge_message_type_known(uint8_t type) {
    return type >= BridgeMsgHello && type <= BridgeMsgActionBatch;
}

BridgeDecodeResult bridge_frame_encode(
    const BridgeFrame* frame,
    uint8_t* output,
    size_t output_capacity,
    size_t* output_length) {
    if(!frame || !output || !output_length) return BridgeDecodeLength;
    if(frame->version != BRIDGE_PROTOCOL_VERSION) return BridgeDecodeVersion;
    if(!bridge_message_type_known(frame->type)) return BridgeDecodeUnknownType;
    if(frame->payload_length > BRIDGE_MAX_PAYLOAD ||
       (frame->payload_length > 0 && !frame->payload)) {
        return BridgeDecodeLength;
    }
    size_t total_length = BRIDGE_HEADER_SIZE + frame->payload_length;
    if(output_capacity < total_length) return BridgeDecodeTruncated;

    output[0] = BRIDGE_MAGIC_0;
    output[1] = BRIDGE_MAGIC_1;
    output[2] = frame->version;
    output[3] = frame->type;
    write_u32_le(output + 4, frame->sequence_id);
    write_u16_le(output + 8, frame->payload_length);
    write_u16_le(output + 10, frame->flags);
    if(frame->payload_length) memcpy(output + BRIDGE_HEADER_SIZE, frame->payload, frame->payload_length);
    write_u32_le(output + 12, frame_crc(output, output + BRIDGE_HEADER_SIZE, frame->payload_length));
    *output_length = total_length;
    return BridgeDecodeOk;
}

BridgeDecodeResult bridge_frame_decode(const uint8_t* input, size_t input_length, BridgeFrame* frame) {
    if(!input || !frame) return BridgeDecodeTruncated;
    if(input_length < BRIDGE_HEADER_SIZE) return BridgeDecodeTruncated;
    if(input[0] != BRIDGE_MAGIC_0 || input[1] != BRIDGE_MAGIC_1) return BridgeDecodeMagic;
    if(input[2] != BRIDGE_PROTOCOL_VERSION) return BridgeDecodeVersion;
    if(!bridge_message_type_known(input[3])) return BridgeDecodeUnknownType;

    uint16_t payload_length = read_u16_le(input + 8);
    if(payload_length > BRIDGE_MAX_PAYLOAD || input_length != BRIDGE_HEADER_SIZE + payload_length) {
        return BridgeDecodeLength;
    }
    if(read_u32_le(input + 12) != frame_crc(input, input + BRIDGE_HEADER_SIZE, payload_length)) {
        return BridgeDecodeCrc;
    }

    frame->version = input[2];
    frame->type = input[3];
    frame->sequence_id = read_u32_le(input + 4);
    frame->payload_length = payload_length;
    frame->flags = read_u16_le(input + 10);
    frame->payload = input + BRIDGE_HEADER_SIZE;
    return BridgeDecodeOk;
}
