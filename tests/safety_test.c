#include "safety/safety.h"

#include <assert.h>
#include <stdbool.h>
#include <stdio.h>

typedef struct {
    bool key_down;
    bool mouse_down;
    bool queued_action;
} FakeDevice;

static void clear_queue(void* context) {
    FakeDevice* device = context;
    device->queued_action = false;
}

static void release_all(void* context) {
    FakeDevice* device = context;
    device->key_down = false;
    device->mouse_down = false;
}

static void assert_stop_releases_everything(void) {
    FakeDevice device = {.key_down = true, .mouse_down = true, .queued_action = true};
    BridgeSafetyOps ops = {.clear_queue = clear_queue, .release_all = release_all, .context = &device};
    bridge_safety_release(&ops);
    assert(!device.queued_action);
    assert(!device.key_down);
    assert(!device.mouse_down);
}

int main(void) {
    assert_stop_releases_everything(); /* disconnect while key/button is down */
    assert_stop_releases_everything(); /* physical emergency stop */
    assert_stop_releases_everything(); /* cancel during execution */
    puts("safety tests: ok");
    return 0;
}
