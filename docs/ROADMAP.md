# Roadmap and implementation status

| Phase | Scope | Status |
|---|---|---|
| 0 | Official SDK/API feasibility | WORKING; report committed before implementation |
| 1 | Phone ↔ Flipper RPC AppData transport | EXPERIMENTAL; FAP build and Android client source exist; no device test |
| 2 | USB HID keyboard | EXPERIMENTAL; FAP built against exported API; hardware validation needed |
| 3 | USB HID relative mouse | EXPERIMENTAL; FAP built; absolute mouse explicitly false |
| 4 | Versioned binary protocol | WORKING on host tests; device integration EXPERIMENTAL |
| 5 | Bounded queue, cancel, emergency stop | EXPERIMENTAL until device disconnect/held-key tests pass |
| 6 | Screenshot transport experiment | Direct host-file path BLOCKED; fixed PowerShell bootstrap + CDC transport implemented, EXPERIMENTAL and unverified on Windows |
| 7 | Android controller | EXPERIMENTAL; source and host tests exist; Android build not run because JDK/SDK/Gradle are absent |
| 8 | AI adapter | PLANNED; provider-neutral interface exists, no provider is connected |
| 9 | Full screenshot → AI → action loop | BLOCKED pending Android build, real-device validation and an AI provider |

No status is upgraded from an API declaration alone. Hardware validation should record model, firmware version, SDK commit, Android version, Windows version, and test result.
