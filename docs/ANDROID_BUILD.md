# Android reference app

The source is split between `phone/android` (the UI and Bluetooth LE/RPC client) and `phone/core` (protocol, typed actions, controller, and screenshot reassembly). The core module is configured as a sibling Gradle project.

## Requirements

- Android Studio with JDK 17
- Android SDK Platform 35 and a recent Android build tools package
- Gradle 8.7 or newer through Android Studio's Gradle integration
- Android 8.0/API 26 or newer; Android 12+ asks for Bluetooth Scan and Connect permissions

Open `phone/android` in Android Studio and run the `app` configuration on a phone. Or, with a compatible local Gradle installation:

```sh
cd phone/android
gradle :core:test :app:assembleDebug
```

The app scans for the paired stock Flipper BLE Serial service and enables RX/flow notifications. It clears any old manual-launch request on connection, then waits for the user to open AI Bridge from the Flipper's Tools menu. That menu launch leaves a one-shot request; the phone sees it with RPC StorageStat, starts `flipper_ai_bridge` using RPC AppStart to provide the app's RPC context, and sends `HELLO` / `GET_CAPABILITIES` after the app reports started. Connecting the phone alone does not open the Flipper app.

The Compose app includes OpenAI and Gemini providers, encrypted key storage, **Check API**, manual HID controls, a bounded task loop and Phone camera vision. No API credentials are bundled. The updated APK passed `:app:assembleDebug` and its signing certificate matches the previous APK, allowing an in-place update. `:core:test` passed before the manual-control update; `:app:testDebugUnitTest` has no test sources. APK install/UI launch, BLE RPC readiness and phone-camera Refresh were observed on a Samsung Galaxy A5 running Android 8 with the previous APK. The updated APK has not yet been installed on hardware. The complete AI-to-PC action loop and Windows screenshot return remain unverified. See [install](INSTALL.md), [verification](VERIFY.md) and [troubleshooting](TROUBLESHOOTING.md).
