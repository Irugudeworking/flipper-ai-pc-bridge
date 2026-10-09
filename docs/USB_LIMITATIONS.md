# USB limitations

The official F7 USB layer exposes one active interface object at a time. Current stock objects are `usb_hid`, `usb_hid_u2f`, `usb_cdc_single`, and `usb_cdc_dual`. `furi_hal_usb_set_config()` can change that object at runtime, and the CDC HAL calls are exported to FAPs.

This supports a mutually exclusive HID ↔ CDC transition. It does not provide MSC, HID+MSC, or HID+CDC composite operation. Windows will enumerate the newly selected USB class after a mode change. Mouse movement is relative; the stock HID report does not provide absolute positioning.

Windows 10/11 have built-in HID class support. CDC serial enumeration/use is available as a COM port on stock Windows, subject to device and OS setup. No PC package or manual action is needed for the experimental screenshot path. On an explicit phone screenshot request, the FAP starts a fixed, unprivileged PowerShell capture through Windows Run, then switches to CDC. The Windows input layout must match the Flipper's US key map.

Never let the host and Flipper independently mount and modify the same FAT volume. This project does not expose the Flipper SD card to the host as shared storage.
