# Verification and release status

This is the check record for the version in this repository. Passing build and protocol tests does **not** prove that AI control or the Windows screenshot loop works on every device. The manual Flipper launch handoff, USB HID actions on the target, and Windows screenshot return still need a full hardware run before they can be called end-to-end verified.

## Checks performed before publication

| Check | Result | Scope |
|---|---|---|
| `cmake -S . -B <build-dir>` and `cmake --build <build-dir>` | Passed | Host C protocol/safety/queue code builds |
| `ctest --test-dir <build-dir> --output-on-failure` | **5/5 passed** | Protocol, actions, queue, sequence window, safety |
| `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` | Passed | Android core tests and APK build; app module currently has **no unit tests** |
| `./gradlew :app:lintDebug` | Passed after fixes | Bluetooth permission exceptions and manifest declarations were corrected. Nonfatal dependency/deprecation warnings remain. |
| `ufbt fap_flipper_ai_bridge` with target 7 / API 87.1 | Passed | FAP compiles against the official SDK; no runtime guarantee |
| Installed FAP copied back from a paired Flipper | Exact SHA-256 match to installed artifact | File transfer integrity only |
| Android APK installed and opened on Samsung Galaxy A5 / Android 8 | Passed | Install and Compose UI launch |
| Phone camera **Refresh** | Passed | App displayed a newly captured camera frame |
| Gemini **Check API** | Incomplete | One AI JSON response exposed a local action-parser defect, which was corrected. Later checks returned provider HTTP 503, so the corrected check has not yet completed successfully. No key was included in the repository. |
| Windows automatic screenshot request through Flipper | **Not run** | Requires a Windows 10/11 target and hardware observation |
| Linux automatic screenshot request through Flipper | **Unsupported** | Windows PowerShell path cannot run on Linux |

Tracked source and documentation were scanned for common API-key/private-key patterns before publication. No match was found. The repository has no license file; review the code before reuse.

## End-user smoke check

Perform these in order, with a blank text editor on a computer you own:

1. Confirm the Flipper sees the microSD card and **Apps → Tools → Flipper AI Bridge** appears.
2. Connect the phone to the Flipper and confirm the phone says to open the bridge manually. Confirm the Flipper app did **not** open solely because Bluetooth connected.
3. Open the app on the Flipper yourself. Confirm the phone reports protocol capabilities and **Bridge ready**. If it does not, use [troubleshooting](TROUBLESHOOTING.md).
4. Plug the Flipper into the target computer via a USB data cable. On the phone's **Manual** screen, send a harmless key or short text to the blank editor. Confirm input appears in the intended window. This step is needed because target-PC HID has not been validated in the release check.
5. Select **Phone camera**, enable its preview, point it at the monitor, and tap **Refresh**. Confirm **Latest view** shows the monitor, not another scene.
6. In **Settings**, select your provider and tap **Check API**. A saved-key badge alone is not an API test. If the check fails, fix it before running an AI task.
7. Run only a small reversible task while watching the computer. Check that **Stop** and long Flipper **Back** release any held input.

If any step fails, record the Android version, Flipper firmware/API version, target OS, app status text, and the relevant non-secret entries from the app's **Logs** tab. Do not include API keys, passwords, private screenshots, or personal data in a public issue.

## Reproduce source checks

From the repository root:

```sh
cmake -S . -B build-host
cmake --build build-host
ctest --test-dir build-host --output-on-failure
cd phone/android
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:lintDebug
```

Use JDK 17 and Android SDK Platform/Build Tools 35. The `:app:testDebugUnitTest` task currently reports `NO-SOURCE`; `:core:test` contains the Android-side protocol tests. For FAP build prerequisites and firmware compatibility, see [Flipper build](FLIPPER_BUILD.md).
