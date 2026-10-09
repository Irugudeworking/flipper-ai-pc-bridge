#include "protocol/actions.h"
#include "safety/safety.h"

#include <assert.h>
#include <stdio.h>

static void put_u16(uint8_t* out, uint16_t value) {
    out[0] = (uint8_t)value;
    out[1] = (uint8_t)(value >> 8U);
}

static void put_u32(uint8_t* out, uint32_t value) {
    out[0] = (uint8_t)value;
    out[1] = (uint8_t)(value >> 8U);
    out[2] = (uint8_t)(value >> 16U);
    out[3] = (uint8_t)(value >> 24U);
}

static void test_action_validation(void) {
    uint8_t key[2];
    put_u16(key, 0x28);
    assert(bridge_action_payload_valid(BridgeActionKeyTap, key, sizeof(key)));
    put_u16(key, 0);
    assert(!bridge_action_payload_valid(BridgeActionKeyTap, key, sizeof(key)));

    const uint8_t text[] = "Settings";
    assert(bridge_action_payload_valid(BridgeActionTextInput, text, sizeof(text) - 1));
    const uint8_t bad_text[] = {0x41, 0x00};
    assert(!bridge_action_payload_valid(BridgeActionTextInput, bad_text, sizeof(bad_text)));

    uint8_t delay[4];
    put_u32(delay, 5000);
    assert(bridge_action_payload_valid(BridgeActionWait, delay, sizeof(delay)));
    put_u32(delay, 5001);
    assert(!bridge_action_payload_valid(BridgeActionWait, delay, sizeof(delay)));
}

static void test_batch_parser(void) {
    uint8_t batch[32] = {2};
    uint16_t offset = 1;
    batch[offset++] = BridgeActionKeyTap;
    put_u16(batch + offset, 2);
    offset += 2;
    put_u16(batch + offset, 0x28);
    offset += 2;
    batch[offset++] = BridgeActionTextInput;
    put_u16(batch + offset, 2);
    offset += 2;
    batch[offset++] = 'O';
    batch[offset++] = 'K';
    BridgeActionView actions[BRIDGE_ACTION_BATCH_MAX];
    uint8_t count = 0;
    assert(bridge_action_batch_parse(batch, offset, actions, BRIDGE_ACTION_BATCH_MAX, &count));
    assert(count == 2);
    assert(actions[1].payload_length == 2);
    assert(actions[1].payload[0] == 'O');

    assert(!bridge_action_batch_parse(batch, offset - 1, actions, BRIDGE_ACTION_BATCH_MAX, &count));
    batch[offset] = 0x7F;
    assert(!bridge_action_batch_parse(batch, offset + 1, actions, BRIDGE_ACTION_BATCH_MAX, &count));

    uint8_t too_many[1] = {BRIDGE_ACTION_BATCH_MAX + 1};
    assert(!bridge_action_batch_parse(too_many, sizeof(too_many), actions, BRIDGE_ACTION_BATCH_MAX, &count));
}

static void test_batch_time_budget(void) {
    uint8_t batch[1 + (3 + 4) * 7] = {7};
    uint16_t offset = 1;
    for(uint8_t i = 0; i < 7; ++i) {
        batch[offset++] = BridgeActionWait;
        put_u16(batch + offset, 4);
        offset += 2;
        put_u32(batch + offset, 5000);
        offset += 4;
    }
    BridgeActionView actions[BRIDGE_ACTION_BATCH_MAX];
    uint8_t count = 0;
    assert(!bridge_action_batch_parse(batch, offset, actions, BRIDGE_ACTION_BATCH_MAX, &count));
}

typedef struct {
    uint8_t order[2];
    uint8_t count;
} FakeSafety;

static void fake_clear(void* context) {
    FakeSafety* fake = context;
    fake->order[fake->count++] = 1;
}

static void fake_release(void* context) {
    FakeSafety* fake = context;
    fake->order[fake->count++] = 2;
}

static void test_safety_order(void) {
    FakeSafety fake = {0};
    BridgeSafetyOps ops = {fake_clear, fake_release, &fake};
    bridge_safety_release(&ops);
    assert(fake.count == 2);
    assert(fake.order[0] == 1 && fake.order[1] == 2);
}

int main(void) {
    test_action_validation();
    test_batch_parser();
    test_batch_time_budget();
    test_safety_order();
    puts("action and safety tests: ok");
    return 0;
}
