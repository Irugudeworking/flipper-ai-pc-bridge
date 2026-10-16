# Flipper build

Build with the official Flipper Build Tool (uFBT) or firmware Build Tool and SDK matching the target firmware. The FAP manifest is `application.fam`; the ID is `flipper_ai_bridge`.

```sh
python -m pip install --user ufbt
cd flipper-ai-pc-bridge
ufbt fap_flipper_ai_bridge
```

Alternatively, clone the official firmware recursively, place this app directory under `applications_user/flipper_ai_bridge`, then run `./fbt fap_flipper_ai_bridge` from the firmware root. External apps use only symbols exported by `targets/f7/api_symbols.csv`; do not silently substitute internal-only APIs.

**Current build status:** the updated FAP built successfully with official uFBT using Flipper target 7 / API 87.1. This version replaces an invalid `0000:0000` USB HID identity with the firmware's default HID identity. The previous FAP was copied to `/ext/apps/Tools/flipper_ai_bridge.fap` on a Flipper and read back with an exact SHA-256 match. The updated FAP has not yet been installed on hardware. Windows screenshot behavior remains unverified. The FAP does not replace Flipper firmware.

The build tool writes its result under its configured uFBT build directory; copy the generated `flipper_ai_bridge.fap` to `dist/` if you want a release copy. Install that `.fap` at `/ext/apps/Tools/flipper_ai_bridge.fap` on the Flipper. Pair and connect the phone through the stock BLE screen, then open **Flipper AI Bridge** from Tools on the Flipper. On connection, the Android client deletes any old request. A menu launch writes `/ext/apps_data/flipper_ai_bridge/manual_launch` and exits. The Android client polls for that request and starts the FAP by its full SD card path over BLE RPC with `args="RPC"`; stock firmware replaces that marker with the RPC AppData context passed to the FAP. The FAP clears the one-shot request after the RPC launch. A phone connection alone does not start the FAP. HID and the BLE RPC profile then operate concurrently on separate physical interfaces. A short Back press exits the running FAP; a long Back press stops actions.
