#pragma once

#include <stdbool.h>

typedef struct {
    void (*clear_queue)(void* context);
    void (*release_all)(void* context);
    void* context;
} BridgeSafetyOps;

void bridge_safety_release(BridgeSafetyOps* ops);
