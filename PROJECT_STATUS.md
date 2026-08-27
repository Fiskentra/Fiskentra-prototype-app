# Fiskentra Project Status

Last updated: 2026-08-30

## Current stage

- Version: `v0.7`
- Stage: Internal Prototype / Pre-Alpha
- Launch readiness: Not ready for public users
- Selected field button: Flic 2 Single Pack
- Product: https://flic.io/shop/flic-2-single-pack
- Retired hardware plan: BlueUP SafeX Lite
- Build status: Debug APK compiles successfully
- Physical Flic 2 validation: Foreground callbacks confirmed; locked-phone validation remains a field test
- Physical Flic 2 callbacks: Confirmed working by user on 2026-08-18
- Map marker hotfix: Saved points now render above the live GPS dot and overlapping points spread visibly
- Map marker follow-up: The live GPS position is reserved, rendered last and remains visible inside overlapping saved-point clusters
- Background service: Implemented; locked-phone physical validation pending
- Fishing day log: Implemented locally with active-session recovery, event summaries and calendar history

## Selected hardware

| Property | Flic 2 |
|---|---|
| Triggers | Push, double push, hold |
| Connection | Bluetooth 5 LE, direct Android support |
| Battery | Replaceable CR2032, advertised up to 3 years |
| Advertised indoor range | Up to 50 m |
| Water protection | IP44 splashproof; not waterproof |
| Android integration | Official `flic2lib-android` SDK |

## Button mapping

| Flic action | Fiskentra action | Saved point type |
|---|---|---|
| Single press | Register a catch | `Catch` |
| Double press | Save a waypoint | `Waypoint` |
| Hold | Record a tackle change | `Tackle change` |

## Roadmap to launch

| Version | Goal | Status |
|---|---|---|
| `v0.4.1` | Record Flic 2 as the selected hardware and update the prototype | Complete |
| `v0.5` | Integrate `flic2lib-android` and test the physical Flic 2 | Complete |
| `v0.6` | Support reliable button events with the app backgrounded or phone locked | Superseded by v0.6.1 hotfix |
| `v0.6.1` | Fix foreground fallback and true offline Flic capture | Superseded by v0.6.2 sync-status hotfix |
| `v0.6.2` | Prevent offline points from remaining in `Syncing` | Complete · physical offline test confirmed by user on 2026-08-30 |
| `v0.7` | Add fishing day log and calendar | Current · implementation complete, physical UI test pending |
| `v0.8` | Add weather to saved points and trips | Planned |
| `v0.9` | Add catch details | Planned |
| `v0.10` | Polish offline storage and sync recovery | Planned |
| `v0.11` | Improve fishing maps and layers | Planned |
| `v0.12` | Add user profiles and authentication | Planned |
| `v0.13` | Add settings | Planned |
| `v0.14` | Add onboarding | Planned |
| `v0.15` | Closed beta | Planned |
| `v1.0` | Official public launch | Planned |

## v0.5 implementation

- Official `flic2lib-android` 2.0.1 dependency and JitPack repository added.
- The SDK manager initializes at application startup.
- Previously paired Fiskentra buttons reconnect when the foreground app starts.
- The Device screen pairs a new Flic 2 through the official SDK.
- Single, double and hold callbacks map to `Catch`, `Waypoint` and `Tackle change`.
- Queued events older than 15 seconds are ignored, preventing an old press from saving a point with a fresh GPS position.
- The existing `FiskentraBleManager` remains dormant source for diagnostics only. It is not used by the app UI and cannot create points.

## v0.6 implementation

- One application-scoped Flic controller feeds both UI and service listeners without duplicate SDK callbacks.
- A `connectedDevice|location` foreground service keeps Flic and GPS active with the screen off.
- Android 14+ foreground-service permissions and Android 13+ notification permission are declared/requested.
- Flic actions use a GPS fix no older than 30 seconds.
- If GPS is stale, the service waits up to 20 seconds for a fresh fix and refuses to save a false position.
- Points are written locally first, then synced to Supabase on the service's background executor.
- The service is sticky across ordinary process reclamation and exposes its state on the Device screen.

## v0.6.1 reliability fix

- Restored the v0.5 foreground capture path when Android cannot start or has stopped the background service.
- Every service-handled Flic action now reports `press received` immediately before GPS processing.
- A fresh GPS fix is still preferred, but a recent fix up to five minutes old can be saved immediately when offline.
- After the normal GPS wait, a cached fix up to 30 minutes old is used instead of silently losing the button event.
- Local storage always happens before cloud sync.
- When Android reports no validated internet connection, Supabase is skipped immediately and the point is marked `Saved locally · offline`.
- Pending local points remain available for the existing `Sync local points` action after connectivity returns.

## v0.6.2 physical test gate

1. Install v0.6.2 over the existing Fiskentra build and open the app once.
2. On Device, confirm `Flic 2 ready` and `BACKGROUND CAPTURE ACTIVE`.
3. With internet enabled, test single, double and hold and confirm three new saved points.
4. Disable Wi-Fi and mobile data but keep Bluetooth and Location enabled.
5. Repeat single, double and hold; each press must be acknowledged and saved locally.
6. Open Saved and confirm the points show `Saved locally · offline`.
7. Restore internet and tap `Sync local points`; confirm the points change to `Synced to cloud`.

## v0.6.2 sync-status fix

- Offline state is checked before a point is marked as syncing.
- Network state is checked again inside the background upload task in case Android connectivity changes between capture and upload.
- Offline points now immediately show `Saved locally · offline`.
- Sync attempts store an update timestamp.
- A `Syncing` state older than 20 seconds is recovered automatically as `sync interrupted · retry when online` when Saved opens.
- Supabase upload timeouts were reduced so a stale Android network state cannot leave misleading UI for long.

## v0.6 physical test gate

1. Install the v0.6 debug APK and allow Location, Nearby devices and Notifications.
2. Open Fiskentra once after installation or phone reboot; confirm the persistent `Fiskentra field button active` notification.
3. Lock the phone and test single press, double press and hold.
4. Unlock the phone and confirm exactly three new points with the expected types and current coordinates.
5. Repeat after leaving Bluetooth range and returning; confirm reconnect and no stale point.
6. Repeat without internet; confirm local points appear, then use `Sync local points` after connectivity returns.

Force-stopping Fiskentra disables all Android background work until the user opens the app again.

## v0.7 implementation

- A new `Log` destination provides the fishing-day journal and calendar.
- A fishing day can be started and finished from Home or Log.
- The active session is stored locally and remains active after the activity or process is recreated.
- Saved events created by the phone or Flic 2 are included automatically when their timestamps fall inside a fishing session.
- Each journal day summarizes session duration, all events, catches, waypoints and tackle changes.
- The monthly calendar marks dates that contain one or more fishing sessions.
- Tapping a logged event opens its exact position on the field map.
- Multiple fishing sessions on the same calendar day are supported and aggregated into one day summary.

## v0.7 physical test gate

1. Install v0.7 over v0.6.2 so existing paired buttons and saved points remain available.
2. Open `Log`, tap `START FISHING DAY`, then verify the active state also appears on Home.
3. Save one Catch, one Waypoint and one Tackle change using Flic 2 or the Device test controls.
4. Return to `Log` and confirm the three counters and event list update.
5. Finish the fishing day and confirm the session remains on its marked calendar date.
6. Close and reopen Fiskentra, select that date and confirm the journal remains available.
