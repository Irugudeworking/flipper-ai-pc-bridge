#include "protocol/sequence_window.h"

#include <assert.h>
#include <stdio.h>

int main(void) {
    BridgeSequenceWindow window = {0};
    assert(!bridge_sequence_window_accept(&window, 0));
    assert(bridge_sequence_window_accept(&window, 10));
    assert(bridge_sequence_window_accept(&window, 12));
    assert(!bridge_sequence_window_accept(&window, 10));
    assert(bridge_sequence_window_accept(&window, 11));
    for(uint32_t id = 13; id < 13 + BRIDGE_SEQUENCE_WINDOW_SIZE; ++id) {
        assert(bridge_sequence_window_accept(&window, id));
    }
    assert(bridge_sequence_window_accept(&window, 10)); /* aged out of the bounded history */
    puts("sequence window tests: ok");
    return 0;
}
