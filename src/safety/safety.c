#include "safety.h"

void bridge_safety_release(BridgeSafetyOps* ops) {
    if(!ops) return;
    if(ops->clear_queue) ops->clear_queue(ops->context);
    if(ops->release_all) ops->release_all(ops->context);
}
