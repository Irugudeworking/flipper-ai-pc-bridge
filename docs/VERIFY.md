# Verification and release status

This is the check record for the version in this repository. The current manual-control fix compiles in both apps, and the updated APK uses the same signing certificate as the previously published APK. The current binaries have **not** been installed on the user's phone or Flipper because neither device was connected to this computer during the update. Earlier hardware observations below refer to the previous binaries. USB HID actions on the target and Windows screenshot return still need a full hardware run before they can be called end-to-end verified.

## Checks performed

| Check | Result | Scope |
|---|---|---|
| `cmake -S . -B <build-dir>` and `cmake --build <build-dir>` | Passed | Host C protocol/safety/queue code builds |
| `ctest --test-dir <build-dir> --output-on-failure` | **5/5 passed** | Protocol, actions, queue, sequence window, safety |
| `./gradlew :app:assembleDebug` for the manual-control update | Passed | Updated APK compiles; unit tests were not rerun for this change |
| `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` before the manual-control update | Passed | Earlier Android core tests and APK build; app module currently has **no unit tests** |
| `./gradlew :app:lintDebug` | Passed after fixes | Bluetooth permission exceptions and manifest declarations were corrected. Nonfatal dependency/deprecation warnings remain. |
| `ufbt fap_flipper_ai_bridge` with target 7 / API 87.1 for the manual-control update | Passed | Updated FAP compiles against the official SDK; no runtime guarantee |
| Previous FAP copied back from a paired Flipper | Exact SHA-256 match to the previous artifact | Earlier file transfer integrity only |
| Previous Android APK installed and opened on Samsung Galaxy A5 / Android 8 | Passed | Earlier install and Compose UI launch; updated APK not installed on hardware yet |
| APK signing certificate comparison | Passed | Updated APK certificate SHA-256 matches the previous APK, so an in-place update should preserve app data |
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
4. Plug the Flipper into the target computer via a USB data cable. Confirm its screen says **USB: HID / CONNECTED**. On the phone's **Manual** screen, send `abc` to the blank editor. Confirm input appears in the intended window. If the phone reports **USB HID not connected**, inspect the cable and port. This step is needed because the updated target-PC HID path has not been validated on hardware.
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
