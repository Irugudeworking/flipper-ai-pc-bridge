#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define BRIDGE_PROTOCOL_VERSION 1U
#define BRIDGE_HEADER_SIZE 16U
#define BRIDGE_MAX_PAYLOAD 240U
#define BRIDGE_MAX_FRAME (BRIDGE_HEADER_SIZE + BRIDGE_MAX_PAYLOAD)
#define BRIDGE_MAGIC_0 0x46U /* F */
#define BRIDGE_MAGIC_1 0x42U /* B */

typedef struct {
    uint8_t version;
    uint8_t type;
    uint32_t sequence_id;
    uint16_t payload_length;
    uint16_t flags;
    const uint8_t* payload;
} BridgeFrame;

typedef enum {
    BridgeDecodeOk = 0,
    BridgeDecodeTruncated,
    BridgeDecodeMagic,
    BridgeDecodeVersion,
    BridgeDecodeLength,
    BridgeDecodeCrc,
    BridgeDecodeUnknownType,
} BridgeDecodeResult;

uint32_t bridge_crc32(const uint8_t* data, size_t length);
bool bridge_message_type_known(uint8_t type);
BridgeDecodeResult bridge_frame_encode(
    const BridgeFrame* frame,
    uint8_t* output,
    size_t output_capacity,
    size_t* output_length);
BridgeDecodeResult bridge_frame_decode(const uint8_t* input, size_t input_length, BridgeFrame* frame);
