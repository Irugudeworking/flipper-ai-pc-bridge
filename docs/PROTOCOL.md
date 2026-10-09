# Phone ↔ Flipper protocol v1

Bridge frames are transported inside the stock RPC AppData byte field. RPC framing remains the official length-delimited Flipper Protocol Buffers envelope; do not write this bridge header directly to the BLE characteristic.

## Bridge frame

All multibyte integers are little-endian. The fixed 16-byte header is followed by `payload_length` bytes.

| Offset | Size | Field |
|---:|---:|---|
| 0 | 2 | magic bytes `46 42` (`FB`) |
| 2 | 1 | protocol version (`1`) |
| 3 | 1 | message type |
| 4 | 4 | sequence ID |
| 8 | 2 | payload length |
| 10 | 2 | flags (reserved, send zero in v1) |
| 12 | 4 | CRC-32/ISO-HDLC |
| 16 | 0–240 | payload |

CRC covers header bytes 2–11 followed by payload. Magic and CRC field are excluded. Unknown version, type, oversized or inconsistent length, malformed packet, or bad CRC is rejected before queueing.

## Message types

1 HELLO, 2 HELLO_ACK, 3 GET_CAPABILITIES, 4 CAPABILITIES, 5 KEY_DOWN, 6 KEY_UP, 7 KEY_TAP, 8 TEXT_INPUT, 9 MOUSE_MOVE, 10 MOUSE_CLICK, 11 MOUSE_SCROLL, 12 HOTKEY, 13 WAIT, 14 REQUEST_SCREENSHOT, 15 SCREENSHOT_BEGIN, 16 SCREENSHOT_CHUNK, 17 SCREENSHOT_END, 18 FILE_ACK, 19 FILE_NACK, 20 ACTION_RESULT, 21 PING, 22 PONG, 23 CANCEL, 24 EMERGENCY_STOP, 25 ERROR, 26 ACTION_BATCH.

Every request uses a nonzero sequence ID. Results echo that ID. A repeated ID within the 32 most recent requests is rejected; the phone must use a new ID for a retry. `CANCEL` and `EMERGENCY_STOP` are processed ahead of pending work.

## Action payloads

- `KEY_DOWN`, `KEY_UP`, `KEY_TAP`: `keycode:u16` (USB HID usage; modifiers use separate `HOTKEY`).
- `HOTKEY`: `modifier_mask:u8` (`ctrl=1, shift=2, alt=4, gui=8`), then `keycode:u16`.
- `TEXT_INPUT`: raw printable ASCII bytes only; maximum 96 bytes; never emitted in logs.
- `MOUSE_MOVE`: signed `dx:i16`, `dy:i16`; implementation splits into signed 8-bit relative HID reports.
- `MOUSE_CLICK`: `button:u8` (`1=left, 2=right, 4=middle`).
- `MOUSE_SCROLL`: signed wheel delta `i16`, split into signed 8-bit reports.
- `WAIT`: `duration_ms:u32`, maximum 5000.
- `ACTION_BATCH`: `count:u8`, then `count` records of `type:u8, length:u16, payload[length]`. Maximum 16 actions / 30 seconds total. Reject the entire batch if any member is malformed or disallowed.

## Result payloads

`ACTION_RESULT`: `result:u8`, `completed_count:u8`, `detail_code:u16`. Results: success, failed, timeout, cancelled, unsupported, invalid, busy. Human-readable details belong in phone logs, not in binary safety decisions.

`CAPABILITIES`: protocol version `u16`, capability bit flags `u32`, max packet `u16`, max batch actions `u8`, max text `u8`, max file size `u32`. v1 flags: keyboard bit0, relative mouse bit1, absolute mouse bit2, USB storage bit3, composite USB bit4, CDC bit5, screenshot helper bit6. This FAP reports bits 0, 1, 5 and 6: screenshot-helper means the experimental fixed PowerShell bootstrap and CDC transfer after an explicit phone request. Storage, composite USB and absolute mouse remain false. Maximum file size is 2 MiB.

## File chunk protocol

`SCREENSHOT_BEGIN`: `file_id:u32`, `total_size:u32`, `mime:u8` (`1=PNG`), `checksum_crc32:u32`. `SCREENSHOT_CHUNK`: `file_id:u32`, `offset:u32`, bytes (maximum 200 bytes). `SCREENSHOT_END`: `file_id:u32`, `checksum_crc32:u32`. Phone acknowledges accepted offsets with `FILE_ACK(file_id:u32,next_offset:u32)`; invalid or missing ranges use `FILE_NACK(file_id:u32,expected_offset:u32)`. Sender retries a chunk at most 3 times, then times out or accepts `CANCEL`. Reassembly occurs on the phone; Flipper stores only a chunk and transfer counters.

The file messages require a Windows capture producer. The current FAP starts its fixed producer only after an explicit phone screenshot request; direct Windows screenshot access from stock USB remains unavailable.
