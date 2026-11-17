# Fiskentra 0.28.1 — local recovery candidate

Date: 6 November 2026. VersionCode: 42. Android application: `com.fiskentra.app`.

## Resulting behavior

- Flic actions remain single = Waypoint, double = Catch, hold = start/pause/resume recording. Fine/coarse location permissions are requested together. A foreground service is started only with a valid location or connected-device capability; missing capabilities and lifecycle start restrictions no longer enter the zero-type startup path.
- Direct boat guidance waits for a fresh position with known accuracy of 50 m or better. The selected destination is retained while guidance waits. Heading arrows disappear when their live source becomes unavailable, and recover when a valid source returns.
- The B1 local beta prevents point/photo transport, including automatic retry, manual sync and deletion. The durable outbox, revisions, receipts, photo references and tombstones are retained. Local deletion does not claim to remove copies uploaded by an earlier version. Auth/profile access and public online map/search/weather services remain separate.
- Build, test and USB scripts resolve an installed JDK/SDK instead of using another user's paths. The recovered local environment uses Temurin 21.0.13, Android Platform 35, Build Tools 36.0.0 and platform-tools. Gradle remains 9.5.0 and AGP remains 9.3.1; Java source/target remains 17.

## Verification performed

- `assembleDebug` and `lintDebug`: PASS; Lint has 0 errors and 117 warnings. Warnings remain visible, without a new baseline or disabled checks. They include legacy resource/layout/accessibility, localization, drawing allocation, dependency-version and orientation findings.
- 22 standalone suites: PASS. This includes point-ledger filesystem recovery, local-cloud policy (220 assertions, no opened connections), field access (13), map policies (78), boat speed (40), arrival (38), wind (16), track/GPX and backend-switch regression checks.
- PowerShell scripts parse successfully. Git diff whitespace checks pass.
- The existing 0.28 APK signature was verified and its matching saved debug keystore was preserved for the recovery APK.
- USB update on Samsung Galaxy A33 (SM-A336E), Android 16: PASS. The installed 0.28/41 APK was backed up and its certificate matched; 0.28.1/42 installed as an update. All 24 pre-existing private data files were byte-identical before first launch.
- On-device smoke: Google satellite tiles and online layers loaded; Saved, Journal, Devices and local-storage information opened; the three saved moments remained visible. Direct guidance waited at approximately 100 m GPS accuracy and resumed after an approximately 17 m fix. A restart with GPS disabled and no paired Flic survived without starting an invalid foreground service. GPS was restored and temporary guidance was stopped. The core history files were unchanged after the smoke test.

## APK and use

APK: `artifacts/Fiskentra-0.28.1-recovery-debug.apk`; SHA-256 and build metadata are stored beside it. This is a debug candidate, not a published production release.

Build: `./scripts/Build-Android.ps1` (add `-Offline` after dependencies have been cached). Checks: `./scripts/Test-AndroidCore.ps1`. Set `JAVA_HOME` / `ANDROID_HOME`, supply explicit script parameters, or keep the recovered `.tools` installation in an ancestor directory.

## Still awaiting validation

The USB checks above are stationary and use an existing installation with granted permissions. Fresh-install permission flows on Android 12/14+, physical Flic reconnect/locked-screen sessions and moving navigation still require device/field testing. No Flic was paired during recovery; the user deferred physical button testing. The earlier release notes' tests do not validate these remaining scenarios.

Automatic danger-point proximity alerts, verified marine routing and a full archive/restore system were not added. Depth/sea-mark overlays remain reference information. The rest of the beta roadmap remains subject to its own acceptance criteria.
