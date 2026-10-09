#include "queue/command_queue.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static BridgeCommand command(uint32_t sequence) {
    BridgeCommand out = {
        .type = 7,
        .sequence_id = sequence,
        .payload_length = 2,
        .payload = {0x28, 0},
    };
    return out;
}

static void test_bounded_fifo(void) {
    BridgeCommandQueue queue;
    bridge_command_queue_init(&queue);
    for(uint32_t i = 0; i < BRIDGE_COMMAND_QUEUE_CAPACITY; ++i) {
        BridgeCommand next = command(i + 1);
        assert(bridge_command_queue_push(&queue, &next));
    }
    assert(bridge_command_queue_count(&queue) == BRIDGE_COMMAND_QUEUE_CAPACITY);
    BridgeCommand extra = command(999);
    assert(!bridge_command_queue_push(&queue, &extra));
    for(uint32_t i = 0; i < BRIDGE_COMMAND_QUEUE_CAPACITY; ++i) {
        BridgeCommand popped;
        assert(bridge_command_queue_pop(&queue, &popped));
        assert(popped.sequence_id == i + 1);
        assert(popped.payload[0] == 0x28);
    }
    assert(!bridge_command_queue_pop(&queue, &extra));
}

static void test_clear_and_reuse(void) {
    BridgeCommandQueue queue;
    bridge_command_queue_init(&queue);
    for(uint32_t i = 0; i < 4; ++i) {
        BridgeCommand next = command(i + 1);
        assert(bridge_command_queue_push(&queue, &next));
    }
    bridge_command_queue_clear(&queue);
    assert(bridge_command_queue_count(&queue) == 0);
    BridgeCommand next = command(88);
    assert(bridge_command_queue_push(&queue, &next));
    BridgeCommand popped;
    assert(bridge_command_queue_pop(&queue, &popped));
    assert(popped.sequence_id == 88);
}

int main(void) {
    test_bounded_fifo();
    test_clear_and_reuse();
    puts("queue tests: ok");
    return 0;
}
