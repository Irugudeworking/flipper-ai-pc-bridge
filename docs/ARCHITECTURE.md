# Architecture

## Runtime boundaries

```text
Android phone
  AI provider interface → typed action validator → bounded task loop
  BLE GATT client → Flipper RPC Protocol Buffers → AppData bridge frames
                                      ↓
Flipper Zero FAP
  RPC AppData callback → CRC/version parser → bounded FIFO → HID executor
  UI/input safety ← application state machine → USB HID keyboard + relative mouse
  Screenshot request: fixed Windows bootstrap over HID → USB CDC receiver → chunk sender over RPC AppData
                                      ↓
Windows 10/11
  In-box HID/CDC drivers; one fixed, unprivileged PowerShell capture on request
```

The phone is the network/AI computer. The FAP never accepts arbitrary code and makes no network requests. The stock BLE Serial profile carries Flipper RPC; the bridge's versioned binary protocol is nested inside RPC AppData.

## State machine

`BOOT → IDLE → PHONE_CONNECTED → USB_CONNECTING → READY → EXECUTING / WAITING / PAUSED → READY`; faults transition to `ERROR`. A local long BACK press enters `EMERGENCY_STOP`, clears pending actions, releases keyboard and mouse state, and reports the stop if the BLE RPC session remains open. Session loss also releases all HID state.

`RECEIVING_FILE` and `SENDING_FILE` are only used after an explicit phone screenshot request starts the fixed Windows capture command. No full PC image is held in Flipper RAM.

## Queue and bounds

- Up to 32 decoded actions in the queue.
- Up to 16 actions in one phone batch (the app may drain batches serially).
- Text input is limited to 96 printable ASCII bytes.
- One action is limited to 5 seconds; a batch is limited to 30 seconds.
- Bridge frame payload is limited to 240 bytes; file transfer chunks are smaller to fit the enclosing BLE RPC DataExchange packet.
- Duplicate sequence IDs are rejected; CRC, version and length failures do not reach the executor.

All bounds are protocol limits, not estimates of available Flipper RAM.

## USB transport

`UsbTransport` separates action execution from transport selection. `UsbTransportStock` selects the official `usb_hid` interface and calls exported FAP HID APIs. Mouse coordinates are relative. `ABSOLUTE_MOUSE=false`.

The future `UsbTransportComposite` remains planned for a future firmware effort. Current FAP USB configuration selects one interface at a time. The screenshot experiment switches HID to CDC and back; it does not claim simultaneous HID+CDC.

## Phone AI contract

`AiAgent.nextAction(task, screenshot, history)` returns typed actions. The schema allows only key, hotkey, text, relative mouse, wait, screenshot request and finish. The controller enforces iteration and wall-time limits, validates every response, serializes one bounded batch at a time, and supports cancel/pause/stop. Provider implementations are replaceable.

## Screenshot decision

Direct host-file access is `BLOCKED ON STOCK FIRMWARE`. On an explicit phone request, the FAP opens Windows Run with HID and types one fixed capture command; PowerShell runs minimized with a taskbar entry. It captures only the visible signed-in desktop, serializes a PNG in memory, sends it to CDC, and exits. The FAP forwards received bytes in acknowledged chunks over BLE. The command is not exposed as an AI action, does not elevate, and does not alter execution policy. This path is experimental pending Windows and hardware validation.
