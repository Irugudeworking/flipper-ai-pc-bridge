# Install and use Flipper AI PC Bridge

This guide installs the matching Android APK and Flipper FAP. The APK in `dist/` is a debug build. The Flipper app was built for Flipper target 7 / FAP API 87.1; external apps may require rebuilding when the device firmware API changes. See [build instructions](FLIPPER_BUILD.md) if the Flipper reports an API mismatch.

## What you need

- A Flipper Zero with a working microSD card and Bluetooth enabled.
- An Android phone running Android 8.0 (API 26) or newer, with Bluetooth LE and Internet access for the chosen AI provider.
- A USB **data** cable from Flipper to the computer you want to control. Power-only cables cannot carry keyboard/mouse data.
- A computer you own or are authorized to control. Keyboard and mouse HID are intended for Windows or Linux; the automatic screenshot helper is a **Windows 10/11-only experiment**.
- One initial way to put the `.fap` on the Flipper's microSD card and the `.apk` on the phone. Once installed, ordinary use does not require a bridge program on the target computer.

## 1. Get both matching files

Download these files from this repository's [`dist/`](../dist/) folder:

| Device | File | Destination |
|---|---|---|
| Android phone | [`flipper_ai_brain.apk`](../dist/flipper_ai_brain.apk) | Phone Downloads, then install |
| Flipper Zero | [`flipper_ai_bridge.fap`](../dist/flipper_ai_bridge.fap) | microSD `apps/Tools/flipper_ai_bridge.fap` |

Use both files from the same repository version. A different or older FAP can make the phone connect over Bluetooth without reaching **Bridge ready**.

For anyone who verifies downloaded files, [`dist/SHA256SUMS`](../dist/SHA256SUMS) lists the expected SHA-256 for each artifact. Verify the checksum **before** installing, using your platform's SHA-256 utility.

## 2. Install the APK on Android

1. On the phone, download `flipper_ai_brain.apk` and open it from Downloads or your file manager.
2. If Android asks whether that app may install packages, allow it for the file manager or browser you used, then return to the APK and tap **Install**. Review Android's warnings and the source before continuing. You can turn that install permission off afterward.
3. Open **Flipper AI Brain**. Permit nearby device/Bluetooth access when requested. Android 11 and older may request Location for BLE scanning; Android 12 and newer requests Bluetooth Scan and Connect. Camera permission is needed only if you enable the phone camera.
4. If updating an existing copy signed with the same debug key, Android installs over it and normally keeps settings. If Android says the package conflicts with an existing installation, the signing keys differ. Removing the old app clears its saved API keys and settings; re-enter them after installing.

For development, USB debugging provides an alternative from a setup computer: `adb install -r phone/android/app/build/outputs/apk/debug/app-debug.apk`. End users do not need USB debugging to use the APK.

## 3. Put the FAP on the Flipper microSD card

The microSD path **`apps/Tools/flipper_ai_bridge.fap`** is the card-root path. Flipper tools may show the same location as **`/ext/apps/Tools/flipper_ai_bridge.fap`**. Do not copy it into `apps_data`.

**Card-reader method:** Power off the Flipper, remove its microSD card, insert the card in a reader, create `apps/Tools` if needed, copy the `.fap` to that folder, safely eject the card, and reinsert it. Turn the Flipper back on. This is a one-time setup step.

**Flipper storage tool method:** If you already have the official Flipper SDK/uFBT storage tool and the Flipper appears as a USB serial port, send the file to `/ext/apps/Tools/flipper_ai_bridge.fap` with that tool. The exact serial port varies by computer. Copy the file only; avoid a build-tool `launch` command because that opens the Flipper app immediately.

On the Flipper, navigate to **Apps → Tools** and confirm **Flipper AI Bridge** appears. If it is missing, check the microSD card and the exact path. If the Flipper reports an application API or firmware mismatch, rebuild the FAP against your device firmware as described in [Flipper build](FLIPPER_BUILD.md).

## 4. Pair and connect the phone

1. Turn on Bluetooth on the Flipper and phone. Use the Flipper's normal Bluetooth pairing flow; accept the matching code on both devices if prompted.
2. On the phone, open **Flipper AI Brain → Brain → Find Flipper**. Select your Flipper by name and tap **Connect**. Grant the scan/connect permissions if Android asks.
3. Wait for **“BLE RPC ready; open AI Bridge on Flipper.”** This means Bluetooth is connected, but the bridge app is not running yet.
4. **Now** open **Apps → Tools → Flipper AI Bridge** on the Flipper. The menu launch briefly exits and leaves a one-time request; the phone detects it and starts the app with its RPC channel. Wait for protocol capabilities / **Bridge ready** in the phone app.
5. A short **Back** press on the Flipper closes the running app. A long **Back** press is the emergency stop. Connecting the phone again never opens the Flipper app by itself.

Open the app on the Flipper after the phone reaches BLE RPC ready. If you opened it earlier, close/reconnect and repeat steps 3–4.

## 5. Connect Flipper to the target computer

Plug the Flipper into the target PC with a USB data cable. Once the bridge app is running, the computer should detect a USB keyboard/mouse device. It may briefly disconnect and reconnect when the FAP changes USB mode. No custom driver or bridge application is needed for ordinary HID control.

Try a harmless manual action first: open a blank text editor on the target computer yourself, then use **Manual** in the phone app to send a short key tap or text. Confirm that the text appears in the intended window. Keep the screen visible while testing and stop if input goes to the wrong place.

## 6. Choose how the phone sees the screen

| Setting in **Settings → Vision source** | Windows | Linux | What happens |
|---|---|---|---|
| **Phone camera** | Available | Available | Point the phone camera at the monitor. The app captures a frame on **Refresh**, **Capture Frame**, or during a task. Keep the camera preview open and the display in view. |
| **Flipper screenshot helper** | Experimental | Unavailable | The Flipper types a fixed PowerShell capture command on a signed-in Windows desktop, temporarily switches to USB CDC, and relays a PNG to the phone. This has not been validated end to end. |
| **Auto** | Tries Flipper helper first | Tries and fails, then falls back if camera is enabled | For Linux, choose **Phone camera** directly to avoid waiting for a Windows-only attempt. |

The phone camera produces a photo of the monitor; it is not a direct PC screenshot. The Windows helper cannot capture the login screen or UAC secure desktop. It depends on Windows Run, keyboard layout, PowerShell policy, USB CDC enumeration, and transport timing. Treat it as experimental until you have verified it on your own machine.

## 7. Configure and check the AI provider

1. Open **Settings → AI provider** and select **OpenAI** or **Gemini**.
2. Enter a model name available to your account, then tap **Save model**. The default names are starting points and may need changing as provider availability changes.
3. Enter that provider's API key and tap **Save key**. OpenAI and Gemini keys are stored separately with Android Keystore. Do not put keys in the GitHub repository, screenshots, logs, or issue reports.
4. Tap **Check API**. This sends a small request using the selected key and model, without a Flipper screenshot. **Configured** means only that a key is saved. A success message confirms this check received a parseable model response. HTTP 401/403 usually indicates key or account access trouble; 404 points to the model name; 429 means a rate/quota limit; 503 means the provider could not serve that request. See [troubleshooting](TROUBLESHOOTING.md).
5. On **Brain**, enter a simple, reversible task, make sure the screen source shows the computer display, and tap **Run task**. Keep the phone and Flipper nearby. Use **Stop** on the phone or long **Back** on the Flipper whenever needed.

The AI cannot reliably act on a screen it cannot see. Confirm that **Latest view** displays the intended monitor before running a task.

## Updates and removal

- Update by installing both matching files again. Replacing the FAP does not flash or replace Flipper firmware. Close the old FAP before overwriting it.
- Android normally keeps the encrypted keys during a same-signature update. Deleting the Android app deletes its app data and keys.
- To remove the Flipper app, close it and remove `apps/Tools/flipper_ai_bridge.fap` from the card. The one-time launch marker lives under `apps_data/flipper_ai_bridge`; it can be removed after the app and phone are disconnected.

Continue with the [verification checklist](VERIFY.md). Known failures and their likely causes are in [troubleshooting](TROUBLESHOOTING.md).
