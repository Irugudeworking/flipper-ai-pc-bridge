#include "hid_executor.h"

#include "../transport/usb_transport.h"

#include <furi.h>
#include <furi_hal.h>

#define HID_TAP_MS 15U

bool bridge_hid_init(BridgeHidExecutor* hid) {
    furi_check(hid);
    bool ok = bridge_usb_select_hid();
    hid->initialized = ok;
    hid->connected = false;
    return ok;
}

void bridge_hid_deinit(BridgeHidExecutor* hid) {
    bridge_hid_release_all();
    if(hid && hid->initialized) {
        bridge_usb_restore_default();
        hid->initialized = false;
    }
}

bool bridge_hid_connected(void) {
    return furi_hal_hid_is_connected();
}

bool bridge_hid_key_down(uint16_t keycode) {
    return furi_hal_hid_kb_press(keycode);
}

bool bridge_hid_key_up(uint16_t keycode) {
    return furi_hal_hid_kb_release(keycode);
}

bool bridge_hid_key_tap(uint16_t keycode) {
    if(!furi_hal_hid_kb_press(keycode)) return false;
    furi_delay_ms(HID_TAP_MS);
    return furi_hal_hid_kb_release_all();
}

bool bridge_hid_hotkey(uint8_t modifiers, uint16_t keycode) {
    uint16_t usage = keycode;
    if(modifiers & 0x01U) usage |= KEY_MOD_LEFT_CTRL;
    if(modifiers & 0x02U) usage |= KEY_MOD_LEFT_SHIFT;
    if(modifiers & 0x04U) usage |= KEY_MOD_LEFT_ALT;
    if(modifiers & 0x08U) usage |= KEY_MOD_LEFT_GUI;
    if(!furi_hal_hid_kb_press(usage)) return false;
    furi_delay_ms(HID_TAP_MS);
    return furi_hal_hid_kb_release_all();
}

bool bridge_hid_text(const uint8_t* text, uint16_t length) {
    if(!text && length) return false;
    for(uint16_t index = 0; index < length; ++index) {
        uint8_t character = text[index];
        if(character < 0x20U || character > 0x7EU) return false;
        uint16_t keycode = hid_asciimap[character];
        if(keycode == HID_KEYBOARD_NONE || !furi_hal_hid_kb_press(keycode)) {
            bridge_hid_release_all();
            return false;
        }
        furi_delay_ms(HID_TAP_MS);
        if(!furi_hal_hid_kb_release_all()) return false;
    }
    return true;
}

bool bridge_hid_mouse_move(int16_t dx, int16_t dy) {
    while(dx || dy) {
        int8_t step_x = dx > INT8_MAX ? INT8_MAX : (dx < INT8_MIN ? INT8_MIN : (int8_t)dx);
        int8_t step_y = dy > INT8_MAX ? INT8_MAX : (dy < INT8_MIN ? INT8_MIN : (int8_t)dy);
        if(!furi_hal_hid_mouse_move(step_x, step_y)) return false;
        dx -= step_x;
        dy -= step_y;
        furi_delay_ms(8);
    }
    return true;
}

bool bridge_hid_mouse_click(uint8_t button) {
    if(!furi_hal_hid_mouse_press(button)) return false;
    furi_delay_ms(HID_TAP_MS);
    bool ok = furi_hal_hid_mouse_release(button);
    if(!ok) bridge_hid_release_all();
    return ok;
}

bool bridge_hid_mouse_scroll(int16_t delta) {
    while(delta) {
        int8_t step = delta > INT8_MAX ? INT8_MAX : (delta < INT8_MIN ? INT8_MIN : (int8_t)delta);
        if(!furi_hal_hid_mouse_scroll(step)) return false;
        delta -= step;
        furi_delay_ms(8);
    }
    return true;
}

void bridge_hid_release_all(void) {
    furi_hal_hid_kb_release_all();
    furi_hal_hid_consumer_key_release_all();
    furi_hal_hid_mouse_release(1U);
    furi_hal_hid_mouse_release(2U);
    furi_hal_hid_mouse_release(4U);
}
