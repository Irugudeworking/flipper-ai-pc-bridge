#include "protocol/protocol.h"
#include "protocol/messages.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static void test_crc_vector(void) {
    static const uint8_t text[] = "123456789";
    assert(bridge_crc32(text, sizeof(text) - 1U) == 0xCBF43926U);
}

static void test_round_trip(void) {
    const uint8_t payload[] = {0x00, 0x10, 0xFF, 0x42};
    BridgeFrame source = {
        .version = BRIDGE_PROTOCOL_VERSION,
        .type = BridgeMsgActionBatch,
        .sequence_id = 0x78563412U,
        .payload_length = sizeof(payload),
        .flags = 0x0003,
        .payload = payload,
    };
    uint8_t encoded[BRIDGE_MAX_FRAME];
    size_t encoded_length = 0;
    assert(bridge_frame_encode(&source, encoded, sizeof(encoded), &encoded_length) == BridgeDecodeOk);
    BridgeFrame decoded;
    assert(bridge_frame_decode(encoded, encoded_length, &decoded) == BridgeDecodeOk);
    assert(decoded.sequence_id == source.sequence_id);
    assert(decoded.flags == source.flags);
    assert(decoded.type == source.type);
    assert(decoded.payload_length == sizeof(payload));
    assert(memcmp(decoded.payload, payload, sizeof(payload)) == 0);
}

static void test_malformed_packets(void) {
    const uint8_t payload[] = {1, 2, 3};
    BridgeFrame source = {1, BridgeMsgPing, 9, sizeof(payload), 0, payload};
    uint8_t data[BRIDGE_MAX_FRAME];
    size_t length = 0;
    assert(bridge_frame_encode(&source, data, sizeof(data), &length) == BridgeDecodeOk);
    BridgeFrame decoded;
    assert(bridge_frame_decode(data, BRIDGE_HEADER_SIZE - 1, &decoded) == BridgeDecodeTruncated);

    data[0] = 0;
    assert(bridge_frame_decode(data, length, &decoded) == BridgeDecodeMagic);
    data[0] = BRIDGE_MAGIC_0;

    data[2] = 2;
    assert(bridge_frame_decode(data, length, &decoded) == BridgeDecodeVersion);
    data[2] = BRIDGE_PROTOCOL_VERSION;

    data[3] = 0xFF;
    assert(bridge_frame_decode(data, length, &decoded) == BridgeDecodeUnknownType);
    data[3] = BridgeMsgPing;

    data[8] = 0xFF;
    data[9] = 0x00;
    assert(bridge_frame_decode(data, length, &decoded) == BridgeDecodeLength);
    data[8] = sizeof(payload);
    data[9] = 0;

    data[length - 1U] ^= 0x80;
    assert(bridge_frame_decode(data, length, &decoded) == BridgeDecodeCrc);
}

static void test_encode_bounds(void) {
    uint8_t output[BRIDGE_HEADER_SIZE];
    size_t length = 0;
    BridgeFrame frame = {1, BridgeMsgPing, 1, 1, 0, (const uint8_t*)"x"};
    assert(bridge_frame_encode(&frame, output, sizeof(output), &length) == BridgeDecodeTruncated);
    frame.version = 9;
    assert(bridge_frame_encode(&frame, output, sizeof(output), &length) == BridgeDecodeVersion);
    frame.version = 1;
    frame.type = 250;
    assert(bridge_frame_encode(&frame, output, sizeof(output), &length) == BridgeDecodeUnknownType);
    frame.type = BridgeMsgPing;
    frame.payload_length = BRIDGE_MAX_PAYLOAD + 1U;
    assert(bridge_frame_encode(&frame, output, sizeof(output), &length) == BridgeDecodeLength);
}

int main(void) {
    test_crc_vector();
    test_round_trip();
    test_malformed_packets();
    test_encode_bounds();
    puts("protocol tests: ok");
    return 0;
}
