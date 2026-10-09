# Security model

This prototype is for an owner's explicitly authorized computer. HID control can target Windows or Linux. The automatic screenshot helper is a Windows-only experiment; Linux users must select the phone camera for vision.

- Phone and Flipper pairing use the stock BLE Serial profile's authenticated bonding and visible PIN flow; no custom cryptography is introduced.
- FAP execution begins after the user opens it from the Flipper Tools menu. The paired Android client recognizes that one-time manual request and restarts the FAP with the RPC context. Bluetooth connection alone does not start the FAP.
- Every AI action has a typed ID and bounded fields. AI cannot submit shell/PowerShell text, launch binaries, fetch code, access credentials, handle login/UAC, persist, keylog, or change security controls. The sole exception is an internal fixed PowerShell screenshot command invoked only by an explicit `REQUEST_SCREENSHOT`; it runs unprivileged with a taskbar entry and never evaluates AI-provided command text.
- Long BACK immediately cancels work and releases all keyboard and mouse state. Session loss, HID error, malformed command, and app shutdown run the same release-all path.
- Logs include state, message type, sequence, result and coarse transfer progress. Entered text and screenshot content are excluded by default.
- Screenshot capture runs only after the owner requests it from the phone during an active task. It captures the visible unlocked desktop once and exits. It does not change execution policy or create scheduled tasks/services.
- Protocol integrity CRC detects accidental corruption; authenticated BLE bonding supplies link authentication. CRC is not a cryptographic authenticator.

Do not treat this prototype as an unattended remote-control tool.
