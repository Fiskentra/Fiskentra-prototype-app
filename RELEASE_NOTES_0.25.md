# Fiskentra 0.25 — field map update

Version: **0.25**, Android versionCode **37**, 16 September 2026. This is a development build for closed-beta field validation.

## Map and navigation

- Point and catch sheets with local photos, catch measurements, straight-line distance, favorites, honest sync status and existing edit/navigation actions.
- Shared filters for map markers, clusters, lists and local search: trip, date range, point type and favorites, including explicit handling of records without coordinates.
- Free camera, follow-position and follow-heading modes with heading smoothing, compass reset and protection against stale GPS fixes.
- GPS accuracy/age indicators and distinct location-disabled/permission states.
- Offline-area selection, coverage bounds, progress, management and truthful availability by style, zoom and map position.
- Local backtracking along the recorded route, preserving recording gaps and avoiding invented road instructions or ETA.
- Local point search and real place search with cancellation, stale-response protection, preview markers and explicit save/navigation actions.
- Native map features and clustering for larger datasets; Outdoor, Satellite, Topographic and Ocean layers.
- Existing Walking/Driving routes, maneuvers, Steps, voice controls and ETA retained, alongside the Fiskentra logo and two-way forecast swipes.

## Data reliability and fixes

- Durable local point ledger, migration preserving existing records, capture deduplication, original event timestamps, stable trip ownership, queued sync/delete and tombstones.
- Recording and point capture continue independently of the map View; track pause/resume preserves segments.
- Fixed offline/route/track overview framing, stale offline-coverage labels, point action clipping and renderer lifecycle failures.
- Cleared old place previews when changing searches; prevented invalid camera targets for events without GPS; made trip counts respect point reassignment.
- A compact arrow/distance remains above an open map sheet; full turn and street text returns when the sheet closes. Controls do not overlap the sheet at large font sizes.

## English interface and normal text size

The app uses English resources, SDK controls, date formatting and numeric formatting regardless of the device language. No Russian localization is included in this release. User-entered names/notes and provider place names remain unchanged.

The connected test phone was set to **100%** system font size at the user's request. The app does not override Android accessibility font scaling; earlier 200% checks remain relevant compatibility tests.

Map credentials are configured through ignored `local.properties`; the former embedded MapTiler fallback has been removed. Use `local.properties.example` when setting up another machine. Local APKs and private device evidence are excluded from Git.

## Validation

- Map implementation recheck: **453 assertions** across 16 scripts / 17 groups.
- Hardware recheck: real Walking/Driving route requests, turns/ETA, independent point selection/navigation target, filters matching local search, four map styles and offline-area recovery after restart.
- **7/7** forecast swipe/button checks and **20/20** Map–Saved lifecycle cycles; explicit GPS off/on check.
- Before/after comparison preserved all **129 points**, photo bytes, current track, archived trips, event receipts and deletions.
- Previous frame profiles passed their recorded Window frame budgets; those measurements retain their original APK provenance.
- Final 0.25: `assembleDebug lintDebug` passed in 51 seconds, with 0 errors / 101 warnings. The added `AppBundleLocaleChanges` warning concerns potential language split downloads; English is the built-in base language and this APK does not depend on downloaded translations.
- Installed on OnePlus CPH2609 / Android 15: versionName 0.25, versionCode 37, font_scale 1.0. Device language remains ru-LV while the catch date, decimal values and MapLibre accessibility labels render in English.
- Repeated the forecast gesture/button regression on this English/100% APK: **7/7 passed**; returned the phone to Map with the QA keep-awake flag disabled.

Local APK: `artifacts/Fiskentra-0.25-debug.apk`, 57,138,442 bytes. SHA-256: `A5A8CDE43E096F41C47DCE58A8E43BA0141EAD22AF610016DB0C8361690B4C1B`.

Detailed scopes and earlier APK provenance: [recheck](MAP_RECHECK.md), [acceptance](MAP_BETA_ACCEPTANCE.md), [performance methodology](MAP_BETA_F8_MEASUREMENTS.md).

## Remaining field validation

Real Flic operation with the screen locked, Bluetooth loss/recovery, airplane-mode operation, several hours of background recording/battery use, and arrival/rerouting during physical movement remain to be validated. New road routes and rerouting require internet. Coordinate sync does not imply complete cloud backup of photos and tracks. No production-readiness or routing-provider SLA is claimed.
