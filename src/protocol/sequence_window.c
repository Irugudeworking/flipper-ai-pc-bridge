#include "sequence_window.h"

bool bridge_sequence_window_accept(BridgeSequenceWindow* window, uint32_t sequence_id) {
    if(!window || sequence_id == 0) return false;
    for(uint8_t index = 0; index < window->count; ++index) {
        if(window->values[index] == sequence_id) return false;
    }
    window->values[window->cursor] = sequence_id;
    window->cursor = (window->cursor + 1U) % BRIDGE_SEQUENCE_WINDOW_SIZE;
    if(window->count < BRIDGE_SEQUENCE_WINDOW_SIZE) window->count++;
    return true;
}
