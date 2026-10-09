# Flipper AI Brain (Android)

Flipper AI Brain is a Kotlin/Jetpack Compose app for controlling a paired Flipper Zero bridge over its BLE Serial/RPC service. It negotiates protocol v1 capabilities, exposes manual HID controls, and runs a constrained vision/action loop. Start with the repository's [installation guide](../../docs/INSTALL.md), [verification checklist](../../docs/VERIFY.md) and [troubleshooting guide](../../docs/TROUBLESHOOTING.md).

## Requirements and build

- JDK 17
- Android SDK platform 35 and Build Tools 35.0.0
- Gradle 8.7 (the project wrapper pins this version)

From this directory:

```sh
./gradlew test
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install from a connected Android device with USB debugging enabled, use `adb install -r app/build/outputs/apk/debug/app-debug.apk`; alternatively copy the APK to the phone and open it there. Release signing is intentionally not configured and no private signing key is included.

## Configure the AI provider

Open Settings and select OpenAI or Gemini. Enter that provider's API key and save it. The app keeps the two keys separately, encrypting each with AES-GCM and a non-exportable Android Keystore key. Keys are never placed in source, build files, or logs. Delete a key while its provider is selected to remove only that provider's key. The default OpenAI model is `gpt-4o-mini`; the default Gemini model is `gemini-3.8-flash`. Each model can be changed and saved separately. AI requests use HTTPS and require JSON action output. Screenshot detail can be set to low or high to control vision detail and image-token use. Google recommends a backend proxy for production mobile apps; this owner-operated build uses the key stored on the phone for direct Gemini requests.

**Configured** only means a key is saved. Tap **Check API** to send a small vision request with the selected key and model. The check does not need a Flipper or PC screenshot. A provider HTTP 503 can occur even with a valid key; check the exact error and retry later.

## Connect and operate

1. Put the Flipper in its visible BLE pairing/serial state.
2. In Brain, grant the Android Bluetooth permissions and tap **Find Flipper**.
3. Select the intended device from **Paired and nearby devices** and tap **Connect**. The app never connects to the first scan result automatically. A paired device can appear even when it is not currently advertising; it still needs to be powered on for the connection to succeed.
4. Once the phone says to open the bridge, open **Flipper AI Bridge** from **Tools** on the Flipper. The app leaves a one-shot start request and briefly exits; the phone sees it over BLE RPC and reopens the FAP with the RPC channel. The phone clears any old request when it connects, so connecting alone never starts the Flipper app. Wait for Bridge ready and inspect the reported capabilities.
5. Use Manual to try keyboard, text, mouse, and hotkey actions. Use Brain to enter a task and run it after configuring the selected provider's API key.
6. Pause keeps the task at a safe boundary. Stop cancels the AI coroutine and places `EMERGENCY_STOP` at the front of the BLE write queue. A disconnect cancels the loop and never resumes it automatically.

## Vision

`AUTO` uses the Flipper screenshot helper when the FAP advertises it. The current helper is experimental: it starts one fixed, unprivileged PowerShell capture in the signed-in Windows desktop after the phone asks for a screenshot, then returns a bounded PNG over CDC. It requires no preinstalled PC app, but depends on Windows Run being available, a US keyboard layout, CDC setup, and USB re-enumeration. It cannot capture login/UAC secure desktop screens. If a request fails and CameraX is enabled, AUTO switches to the phone camera for the task; otherwise the error is surfaced.

The Flipper screenshot helper is **Windows-only**. On Linux, choose **Phone camera** directly in Settings and point the camera at the monitor. Camera vision can also be used on Windows. The app captures a frame on request; the camera photo is not a direct desktop screenshot.

Camera permission is requested only when the camera is enabled. The camera preview is local; the app captures a single image only when the agent requests a frame or the user taps Capture Frame. It does not stream video.

## Protocol compatibility and limitations

The Android core reuses the repository's bridge-v1 frame format, CRC, RPC envelope, action limits, and screenshot receiver. Version/app/firmware strings are not included in the current 14-byte capability payload, so Diagnostics reports those values as unsupported instead of inventing them. Absolute mouse positioning and USB storage are not advertised by this FAP. The app emits only allow-listed HID GUI actions; it has no shell, PowerShell, registry, download, or executable action.

APK installation, BLE RPC connection, capability exchange and ping have been observed on a Samsung Galaxy A5 running Android 8 with a paired Flipper Zero. A camera frame was observed after Refresh. The new manual-start handoff requires both the updated phone APK and updated Flipper FAP and is not yet recorded as a complete hardware pass. Flipper-to-PC HID and Windows screenshot return have not been validated end to end. BLE behavior depends on the Android vendor stack, FAP release and pairing state. Auto-reconnect is opt-in, reuses only the previously selected device address, and retries four times after a failed connection.

## Security and troubleshooting

- Authentication, login, permission, and UAC tasks require manual owner interaction; the agent pauses instead of bypassing them.
- Logs redact all `TEXT_INPUT` content and never print the API key.
- No cleartext network traffic or custom TLS trust bypass is used.
- If the scan is empty, check that the Flipper BLE Serial service is active and grant the version-appropriate Bluetooth permission. Paired LE devices are also listed even when no live advertisement is seen.
- If protocol capabilities do not arrive, check pairing and open Flipper AI Bridge from Tools again. A short Back press exits the running bridge; a long Back press stops actions.
- If screenshots fail, select Phone Camera and allow camera access only for that feature.
- A 401/403 means the selected provider rejected the key or account access; a 429 is reported without an infinite retry.
