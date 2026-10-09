# Feasibility report

Checked against the official Flipper Zero firmware `dev` tree on 2026-10-07 (checkout `76bddb6`). This report distinguishes APIs exported to external FAPs from facilities that exist only in firmware internals. Recheck it when changing the SDK revision.

## Findings

| Feature | Stock firmware | FAP | Custom firmware required | Notes |
|---|---|---|---|---|
| USB keyboard | SUPPORTED | SUPPORTED | No | `furi_hal_usb_set_config(&usb_hid, ...)`, `furi_hal_hid_kb_press/release/release_all` are exported in the F7 API symbol table. Windows binds its in-box HID keyboard driver. |
| USB mouse | SUPPORTED | SUPPORTED | No | Relative movement, buttons and wheel are exported. Absolute coordinates are not exposed by this HID report. |
| BLE communication | SUPPORTED | PARTIALLY_SUPPORTED | No | Reuse the stock authenticated BLE Serial/RPC profile and the RPC Application DataExchange channel. Phone must speak Flipper's length-delimited Protocol Buffers envelope. A general custom GATT service is outside this MVP. |
| USB Mass Storage | NOT_PRACTICAL | NOT_PRACTICAL | Yes | No MSC interface is present in the current F7 API exports or stock USB interface list. Flipper's SD filesystem API is a local filesystem API; it does not make the SD card a host-writable MSC volume. |
| HID + Mass Storage simultaneously | NOT_PRACTICAL | NOT_PRACTICAL | Yes | The supported USB interface table contains HID, HID+U2F, one CDC, or dual CDC. It does not contain a composite HID+MSC device. |
| Flipper reading host-written storage | NOT_PRACTICAL | NOT_PRACTICAL | Yes | There is no stock MSC block device to write to. Sharing a mounted FAT volume between Windows and Flipper would also require a coherent ownership/eject protocol; independently mounting the same FAT volume is unsafe and is not implemented here. |
| USB CDC/serial | SUPPORTED | SUPPORTED | No | `usb_cdc_single` and `usb_cdc_dual`, plus `furi_hal_cdc_receive/send/set_callbacks`, are exported. The CDC configuration is a separate USB mode. |
| HID + CDC simultaneously | NOT_PRACTICAL | NOT_PRACTICAL | Yes | No stock composite interface combines the classes. A FAP may switch the USB configuration at runtime, but the PC sees one class at a time and will re-enumerate. |
| Screenshot over USB without a PC helper | BLOCKED ON STOCK FIRMWARE | NOT_PRACTICAL | Yes, for a direct transport | The Windows screenshot can be created with built-in UI/key combinations, but stock FAP has no supported way to retrieve that host file. There is no MSC exchange volume, composite HID+CDC path, or host screen-capture API exposed to a FAP. |
| Screenshot using a fixed, one-shot Windows bootstrap | EXPERIMENTAL | EXPERIMENTAL | No | After an explicit phone screenshot request, the FAP uses HID to start a fixed unprivileged PowerShell capture command through Windows Run. It waits for CDC, captures the visible signed-in desktop in memory, sends a bounded PNG, and exits. The AI schema cannot provide command text. This avoids PC installation or manual helper launch, but relies on Windows input layout, policy, and an unlocked desktop; end-to-end Windows testing is required. |
| Large BLE file transfer | PARTIALLY_SUPPORTED | PARTIALLY_SUPPORTED | No | Stock BLE Serial accepts up to 486 raw bytes per service packet (243-byte GATT characteristic fragments); RPC provides flow control and TX indications. Application-level chunking, checksum, ACK/NACK and retry are required. Keep one bounded chunk in RAM and stream. |
| Dynamic USB mode change | SUPPORTED | SUPPORTED | No | `furi_hal_usb_set_config()` is exported. This switches the USB configuration; it does not create a composite device and may disconnect/re-enumerate the PC-side device. |
| Absolute mouse | NOT_PRACTICAL | NOT_PRACTICAL | Yes | Stock keyboard/mouse report exposes relative `int8_t dx/dy` movement only. Capabilities must report `absolute_mouse=false`. |

## Evidence in the official tree

- [FAP SDK and external app limitations](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/documentation/AppsOnSDCard.md): external apps are loaded into RAM and can import only symbols listed in the target API symbol table.
- [F7 API symbol table](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/targets/f7/api_symbols.csv): exports the HID press/release/move calls, USB mode switch, CDC send/receive callbacks, BLE Serial profile calls, and RPC AppData APIs; it lists `usb_cdc_single`, `usb_cdc_dual`, `usb_hid`, and `usb_hid_u2f` but no MSC or composite HID+CDC interface.
- [USB HID HAL header](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/targets/furi_hal_include/furi_hal_usb_hid.h): relative mouse API accepts `int8_t dx, int8_t dy` and keyboard release-all is explicit.
- [USB interface definitions](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/targets/furi_hal_include/furi_hal_usb.h): one `FuriHalUsbInterface` is selected at a time.
- [Stock BLE Serial service](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/targets/f7/ble_glue/services/serial_service.h): packet maximum is 486 bytes; service reports received/sent events and flow control.
- [RPC AppData API](https://developer.flipper.net/flipperzero/doxygen/rpc__app_8h.html) and [RPC protocol schema](https://github.com/flipperdevices/flipperzero-protobuf/blob/dev/flipper.proto): app data is an intended bidirectional raw-byte exchange layered over stock RPC.
- [RPC application schema](https://github.com/flipperdevices/flipperzero-protobuf/blob/dev/application.proto): start, DataExchange, confirmation and app-state messages.
- [BLE serial profile pairing configuration](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/targets/f7/ble_glue/profiles/serial_profile.c): bonding is enabled and the stock profile uses a visible PIN-code pairing flow.

## Architectural decision

The stock implementation uses the Flipper RPC BLE Serial profile for phone traffic, RPC AppData for the binary bridge protocol, and the exported USB HID functions for keyboard and relative mouse actions. USB storage and direct host-file access remain unavailable. `REQUEST_SCREENSHOT` starts a fixed, one-shot Windows capture command, then switches HID to CDC and forwards bounded PNG chunks to the phone. This bootstrap is experimental and not hardware-verified on Windows.

This does not capture the login/secure desktop, answer UAC, read passwords, or accept command text from AI. The fixed helper runs once with the user's normal permissions; no app is installed and no persistent process remains on Windows.
