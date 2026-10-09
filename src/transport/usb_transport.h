#pragma once

#include <stdbool.h>
#include <stdint.h>

struct BridgeApp;

bool bridge_usb_select_hid(void);
bool bridge_usb_select_cdc(struct BridgeApp* app);
void bridge_usb_restore_default(void);
void bridge_usb_cdc_send(const uint8_t* bytes, uint16_t length);
