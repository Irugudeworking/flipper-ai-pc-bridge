# Troubleshooting

Start with the exact status in the phone app's **Brain**, **Diagnostics**, and **Logs** tabs. The steps below distinguish Bluetooth connection, Flipper app startup, USB HID, vision, and AI service failures.

| Symptom | Likely cause | What to do |
|---|---|---|
| Flipper does not appear in **Find Flipper** | Bluetooth off, permission denied, no BLE advertisement, or an old bond | Turn on Flipper and phone Bluetooth; grant the requested Nearby devices/Bluetooth permission. On Android 11 and older, also grant Location for scanning. Pair through the Flipper's normal visible pairing flow, then scan again. |
| Phone Bluetooth says connected, but app says **open AI Bridge on Flipper** | Bluetooth RPC is ready; the FAP has not been manually started | Open **Apps → Tools → Flipper AI Bridge** on the Flipper **after** the phone says BLE RPC ready. The menu launch briefly exits, and the phone restarts it with the RPC context. |
| Flipper app opens automatically on phone connection | An old Android APK or FAP is installed | Install both matching files from `dist/`. Close/restart the old Flipper app, reconnect the phone, then open the new app yourself. |
| **Bridge ready** never appears after manual launch | Old/mismatched FAP, missing SD card, BLE RPC problem, or interrupted one-time marker | Confirm the FAP's exact card path, close the app, reconnect phone, wait for BLE RPC ready and launch from Tools again. Update both files together. Check Diagnostics/Logs for an RPC error. |
| FAP missing from Tools or says API mismatch | Wrong microSD path or firmware API incompatible with the bundled FAP | Copy to card-root `apps/Tools/flipper_ai_bridge.fap`. Check SD card. Rebuild against the matching firmware SDK if the loader rejects its API. |
| Flipper charges but no key/mouse input reaches PC | Power-only USB cable, FAP not running, HID enumeration failed, wrong focused window, or USB policy | Use a known USB **data** cable and another port. Confirm **Bridge ready**. Focus a blank text editor and send one harmless manual key. Watch for a keyboard/mouse device appearing on the PC. |
| **No screenshot yet** on Linux | The Flipper helper is Windows PowerShell only | Select **Settings → Vision source → Phone camera**, tap **Save settings**, enable camera and point it at the monitor. Tap **Refresh**. |
| Phone camera is black or Refresh fails | Camera permission denied, preview not bound, camera busy, or screen out of view | Grant Camera permission, reopen the app, enable the preview and wait for **Camera ready**. Retry Refresh while the monitor is visible. |
| Windows Flipper screenshot fails or times out | Experimental helper was not launched, Windows Run/PowerShell blocked, non-US input layout, COM/CDC re-enumeration failed, or image exceeds 2 MiB | Use **Phone camera** for a working vision path. For helper debugging, use a signed-in unlocked Windows desktop, check its policy and keyboard layout, and inspect app Logs. Do not expect capture from lock or UAC screens. |
| **Configured** API key but **Check API** fails | Configured only means an encrypted key is saved | Read the check's exact error. Confirm the selected provider and model in Settings. Re-save the correct key if necessary. |
| AI error **401/403** | Provider rejected the key or account/model access | Check the provider account and key permissions. Do not post the key in an issue or screenshot. |
| AI error **404** | Selected model or API endpoint not available to the account | Choose a valid model in Settings and tap **Save model**, then Check API again. |
| AI error **429** | Provider rate or quota limit | Wait for the provider's limit window or review account quota. Avoid rapid repeated checks. |
| Gemini error **503** | Gemini could not serve that request; commonly temporary overload or outage | Wait and retry with increasing delays. Two 503 responses do not prove the key is invalid. A saved key can be valid while the service is unavailable. |
| Android update says package conflict | Existing APK was signed with a different key | An in-place update requires the same signing key. If you uninstall the old app, its saved keys/settings are removed and must be entered again. |

## Collect a useful bug report

Include: what you tapped, the exact phone status/error text, Android version and phone model, Flipper firmware/API version, Windows/Linux version, and whether the USB cable carries data. Note the last successful step from [verification](VERIFY.md). Do **not** include credentials, pairing PINs, personal screenshots, or private data.

## Important limits

- The Flipper cannot directly read the target PC screen or a host file on stock firmware. The Windows helper is an experiment that types a fixed capture command; Linux does not have an equivalent path in this build.
- Phone camera frames are photos of the monitor. Reflections, blur, focus and angle can prevent useful AI vision.
- BLE connection alone does not mean the FAP is running. **Bridge ready** and capabilities are the meaningful app-level signals.
- The PC may need to re-enumerate USB when the FAP switches between HID and CDC.
- No current test record proves a complete AI → HID → PC → screenshot → phone loop. Treat task automation as supervised experimental use.
