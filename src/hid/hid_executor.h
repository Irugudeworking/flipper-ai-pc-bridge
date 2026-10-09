#pragma once

#include <stdbool.h>
#include <stdint.h>

typedef struct {
    bool initialized;
    bool connected;
} BridgeHidExecutor;

bool bridge_hid_init(BridgeHidExecutor* hid);
void bridge_hid_deinit(BridgeHidExecutor* hid);
bool bridge_hid_connected(void);
bool bridge_hid_key_down(uint16_t keycode);
bool bridge_hid_key_up(uint16_t keycode);
bool bridge_hid_key_tap(uint16_t keycode);
bool bridge_hid_hotkey(uint8_t modifiers, uint16_t keycode);
bool bridge_hid_text(const uint8_t* text, uint16_t length);
bool bridge_hid_mouse_move(int16_t dx, int16_t dy);
bool bridge_hid_mouse_click(uint8_t button);
bool bridge_hid_mouse_scroll(int16_t delta);
void bridge_hid_release_all(void);
