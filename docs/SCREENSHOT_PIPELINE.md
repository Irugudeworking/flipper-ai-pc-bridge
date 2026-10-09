# Screenshot pipeline

## Automatic one-shot stock path

**Direct host-file readback is BLOCKED ON STOCK FIRMWARE.** Stock FAP has no MSC, HID+storage composite, or host screen-capture API. The current no-install path uses Windows' built-in PowerShell only after the phone explicitly requests a screenshot. This executes a fixed capture command on the PC; it is not a direct USB screenshot feature.

## Phone-requested Windows CDC experiment

1. Phone sends `REQUEST_SCREENSHOT` as part of the owner's active task.
2. FAP opens Windows Run with HID and types one fixed PowerShell command. PowerShell runs under the signed-in user's normal permissions; its minimized window is visible on the taskbar. No arbitrary AI text is evaluated as a command.
3. The command waits for the Flipper CDC COM port to enumerate, captures the visible virtual desktop in memory, and sends one bounded PNG. It does not elevate, change execution policy, install a service, or persist.
4. The Windows input layout must map the Flipper's US keycodes to ASCII. The command cannot run on the login screen, secure desktop, or while Windows displays UAC.
5. FAP validates the marker, size and PNG signature, then checks CRC incrementally while it sends `SCREENSHOT_BEGIN`, chunks no larger than 200 bytes, and `SCREENSHOT_END` over RPC AppData. Phone ACKs offsets; the FAP retries at most three times and grants one CDC chunk of credit at a time.
6. FAP restores HID mode after completion, cancellation, timeout or error and releases all held keys/buttons.

This path is EXPERIMENTAL until tested with a real Flipper, Android phone and Windows 10/11. No image decoding or full-file buffering is performed on Flipper. Maximum screenshot size is 2 MiB; phone reassembles and verifies CRC before AI vision. FAP waits up to 30 seconds for CDC; a failed Windows launch reports an error rather than trying another execution path.

The capture process runs once and exits. It cannot capture the login/UAC secure desktop. If CDC setup, permissions, keyboard layout, or USB re-enumeration fail, the phone reports the error. With **Auto** vision selected and the phone camera already enabled, the Android app can use a camera frame for the task after a Flipper helper failure. The previous manually started sender remains in `windows-helper/Capture-Screen.ps1` for debugging.
