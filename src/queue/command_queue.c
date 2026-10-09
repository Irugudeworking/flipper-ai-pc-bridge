#include "command_queue.h"

#include <string.h>

void bridge_command_queue_init(BridgeCommandQueue* queue) {
    if(queue) memset(queue, 0, sizeof(*queue));
}

bool bridge_command_queue_push(BridgeCommandQueue* queue, const BridgeCommand* command) {
    if(!queue || !command || command->payload_length > BRIDGE_MAX_PAYLOAD ||
       queue->count >= BRIDGE_COMMAND_QUEUE_CAPACITY) {
        return false;
    }
    queue->items[queue->write_index] = *command;
    queue->write_index = (queue->write_index + 1U) % BRIDGE_COMMAND_QUEUE_CAPACITY;
    queue->count++;
    return true;
}

bool bridge_command_queue_pop(BridgeCommandQueue* queue, BridgeCommand* command) {
    if(!queue || !command || queue->count == 0) return false;
    *command = queue->items[queue->read_index];
    queue->read_index = (queue->read_index + 1U) % BRIDGE_COMMAND_QUEUE_CAPACITY;
    queue->count--;
    return true;
}

void bridge_command_queue_clear(BridgeCommandQueue* queue) {
    if(!queue) return;
    queue->read_index = 0;
    queue->write_index = 0;
    queue->count = 0;
}

uint8_t bridge_command_queue_count(const BridgeCommandQueue* queue) {
    return queue ? queue->count : 0;
}
