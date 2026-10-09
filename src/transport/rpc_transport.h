#pragma once

#include "../app.h"

void bridge_rpc_event(const RpcAppSystemEvent* event, void* context);
bool bridge_rpc_send(BridgeApp* app, uint8_t type, uint32_t sequence_id, const uint8_t* payload, uint16_t payload_length);
