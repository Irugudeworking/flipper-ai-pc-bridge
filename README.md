# Flipper AI PC Bridge

An experimental Flipper Zero app and Android companion app. The phone sends bounded keyboard and mouse actions over Bluetooth. The Flipper presents USB HID to the computer. The phone can use its camera to view the screen on either Windows or Linux. An automatic screenshot path for a signed-in **Windows 10/11** desktop exists but has not been validated end to end.

## Start here

1. Read the [step-by-step installation guide](docs/INSTALL.md).
2. Download both files from [`dist/`](dist/): `flipper_ai_brain.apk` for Android and `flipper_ai_bridge.fap` for Flipper. **Both must be the matching versions in this repository.** Their checksums are in [`SHA256SUMS`](dist/SHA256SUMS).
3. Use the [verification checklist](docs/VERIFY.md) before letting the AI control a computer.
4. If a step fails, use the [troubleshooting guide](docs/TROUBLESHOOTING.md).

The Flipper app is opened **manually** from **Apps → Tools → Flipper AI Bridge**. Connecting the phone alone does not start it. A one-time app installation is required on the phone and Flipper. After that, the target computer needs no preinstalled bridge app for keyboard and mouse control.

**Manual control update:** the earlier FAP advertised USB ID `0000:0000`, which could prevent a PC from accepting its keyboard/mouse interface. The current FAP selects Flipper firmware's default HID identity and converts modifier key usages correctly. Install **both** current files in `dist/`; the phone now reports when USB HID is unavailable. The Flipper screen must say **USB: HID / CONNECTED** before manual input can reach the PC.

## What has been checked

| Area | Result |
|---|---|
| Android APK build | Current APK builds with Gradle and uses the same signing certificate as the previous APK; the previous APK was installed and launched on Samsung Galaxy A5, Android 8 |
| Android/Flipper BLE connection | Pairing, RPC readiness, capability exchange and ping have been observed on the paired devices |
| Flipper `.fap` build | Current FAP builds with official uFBT target 7, API 87.1; the previous FAP was copied to Flipper and read back byte-for-byte |
| Phone camera | A frame appeared in the Android app after **Refresh** on the connected phone |
| Host protocol and Android core tests | Passed; see [verification results](docs/VERIFY.md) |
| Flipper USB HID actions on a target PC | **Experimental; not validated end to end** |
| Automatic Windows screenshot and return over CDC/BLE | **Experimental; not validated end to end** |
| Automatic Linux desktop screenshot through Flipper | **Unavailable** in this build; use the phone camera |

One Gemini check returned AI JSON but failed the app's action validation; after that parser defect was corrected, later checks received HTTP 503 from Gemini. A successful end-to-end API check has not yet been recorded for this build. The app shows a saved key as *configured*, not as proof of current API availability. Use **Settings → Check API** to check your selected key and model; a 503 means the provider could not serve that request at that time.

## How it works

```text
Android phone (AI, vision, controls)
          │ stock BLE Serial/RPC
          ▼
Flipper Zero (bridge app and USB HID)
          │ USB cable
          ▼
Windows or Linux computer (keyboard/mouse target)
```

For vision, **Phone camera** takes a frame when requested and works with either target OS when the camera can see the display. **Flipper screenshot helper** is a separate Windows-only experiment: after an explicit phone request, the Flipper types a fixed PowerShell command through Windows Run, switches USB from HID to CDC, and forwards a bounded PNG. It cannot capture a lock screen or UAC secure desktop. It may fail because of keyboard layout, Windows policy, CDC enumeration or transport timing. The Flipper has no direct API to read a host screenshot or offer shared USB storage on stock firmware. See [screenshot pipeline](docs/SCREENSHOT_PIPELINE.md) and [feasibility report](docs/FEASIBILITY.md).

## Safety and scope

Use this only with a computer you own or are authorized to control. The agent accepts a limited action format and does not accept shell commands from the AI. It must stop at login, authentication and permission prompts. A long **Back** press on the Flipper stops actions and releases held keys and mouse buttons. The phone also has a **Stop** control. Neither mechanism replaces supervision while this is an experimental build.

API keys are entered in the Android app and stored with Android Keystore; no key is included here. The bundled APK is a **debug build**, not a Play Store release. Source, build steps and version limits are in [Android build](docs/ANDROID_BUILD.md), [Flipper build](docs/FLIPPER_BUILD.md), [protocol](docs/PROTOCOL.md) and [security](docs/SECURITY.md).
