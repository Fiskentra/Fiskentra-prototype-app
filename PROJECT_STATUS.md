# Fiskentra Project Status

Last updated: 2026-08-31

## Current stage

- Version: `v0.13`
- Stage: Internal Prototype / Pre-Alpha
- Launch readiness: Not ready for public users
- Selected field button: Flic 2 Single Pack
- Product: https://flic.io/shop/flic-2-single-pack
- Retired hardware plan: BlueUP SafeX Lite
- Build status: Debug APK, Android Lint and signature verification pass
- Physical Flic 2 validation: Foreground callbacks confirmed; locked-phone validation remains a field test
- Physical Flic 2 callbacks: Confirmed working by user on 2026-08-18
- Map marker hotfix: Saved points now render above the live GPS dot and overlapping points spread visibly
- Map marker follow-up: The live GPS position is reserved, rendered last and remains visible inside overlapping saved-point clusters
- Background service: Implemented; locked-phone physical validation pending
- Fishing day log: Implemented and physically validated with active-session recovery, event summaries and calendar history
- Weather: Implemented with Open-Meteo capture, seven-day forecast, species outlook, local persistence and offline-safe cache; physical validation pending
- Supabase weather storage: `public.saved_points.weather jsonb` migration applied and verified on project `dwlbefpmwzmhutlvqfmu` on 2026-08-30
- Catch details: Implemented local-first with optional private-device photo and Supabase JSONB upsert; physical validation pending
- Supabase catch storage: `public.saved_points.catch_details jsonb` plus device-owned update policy applied and verified on 2026-08-30
- Supabase sync hotfix: v0.9.1 sends `X-Device-Id` on point upserts; live Data API verification returned HTTP 401 without the header and HTTP 201 with it
- Offline sync recovery: one process-wide sequential queue now retries persisted local points when Android validates internet access
- Fishing map layers: Outdoor, Satellite/Hybrid, Topographic and Ocean are selectable and persist locally; physical validation pending
- Accounts and profiles: production-shaped email/password UI, encrypted sessions, email deep links, recovery and private owner-only profile RLS implemented; physical validation pending
- Settings: device-local units, Explore start page, default map layer, target fish, delete confirmation and sync overview implemented; physical validation pending

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
| `v0.7` | Add fishing day log and calendar | Complete · physical app test confirmed by user on 2026-08-30 |
| `v0.8` | Add weather capture, forecast and fishing outlook | Complete · advanced to v0.9 |
| `v0.9` | Add catch details | Superseded by v0.9.1 sync hotfix |
| `v0.9.1` | Fix Supabase RLS authorization for point upserts | Complete · advanced to v0.10 |
| `v0.10` | Polish offline storage and sync recovery | Complete · physical recovery test confirmed by user on 2026-08-30 |
| `v0.11` | Improve fishing maps and layers | Implemented · physical validation pending |
| `v0.12` | Add user profiles and authentication | Superseded by v0.12.1 profile/auth hotfix |
| `v0.12.1` | Repair profiles and complete the real-user auth flow | Implemented · physical validation pending |
| `v0.13` | Add settings | Implemented · physical validation pending |
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

## v0.7 physical test completed

Physical validation was confirmed by the user on 2026-08-30 using the corrected v0.7 build based on v0.6.2.

1. Install v0.7 over v0.6.2 so existing paired buttons and saved points remain available.
2. Open `Log`, tap `START FISHING DAY`, then verify the active state also appears on Home.
3. Save one Catch, one Waypoint and one Tackle change using Flic 2 or the Device test controls.
4. Return to `Log` and confirm the three counters and event list update.
5. Finish the fishing day and confirm the session remains on its marked calendar date.
6. Close and reopen Fiskentra, select that date and confirm the journal remains available.

## v0.8 implementation

- Open-Meteo current conditions are captured by GPS coordinate without embedding an API key.
- Each weather snapshot stores observation time, temperature, apparent temperature, humidity, precipitation, pressure, wind speed/direction, WMO code, timezone and provider.
- New points are always stored locally before weather lookup or Supabase sync.
- Foreground and background Flic capture use the same enrichment flow without blocking the button acknowledgement.
- A 15-minute, 5 km local cache prevents rapid Flic actions from making duplicate weather requests and can enrich points during a short connectivity loss.
- Saved point cards, selected map points and fishing-log events show their recorded weather.
- Missing weather can be added or refreshed from Saved.
- Fishing-day sessions and the local trip track retain weather at start and finish.
- Existing v0.7 points, sessions and tracks remain readable because all weather fields are optional.
- Supabase stores a structured optional `weather jsonb` object; the production migration is applied, while v0.8 still retains a compatibility retry without weather.
- Explore now has adjacent Map and Weather pages with visible tabs and horizontal swipe navigation; map-to-weather swipe starts at the right edge so ordinary map panning remains available.
- The Weather page shows current conditions and seven daily forecasts, cached for 30 minutes within 10 km and available from the last cache when offline.
- The user can select Pike, Perch, Zander, Trout or Carp. A documented air-temperature, wind and precipitation heuristic produces an indicative score and reason, not a catch guarantee.
- Fishing-calendar cells show the icon and temperature from the session's saved observed weather. Forecast data is never substituted for historical observations.

## v0.8 physical test gate

1. Install v0.8 over v0.7 without clearing app data.
2. With GPS and internet enabled, save a point and confirm a weather summary appears in Saved and on the selected map point.
3. Start and finish a fishing day and trip track; confirm start/finish conditions persist after restarting Fiskentra.
4. Disable internet and test all three Flic actions; point capture and `Saved locally · offline` must remain correct whether cached weather is present or not.
5. Restore internet, add weather to a point that has none, then sync pending local points.
6. Confirm older v0.7 points and fishing journals still open normally.
7. From Map, use the `WEATHER` tab and the left/right swipe gestures; verify the seven-day forecast and return to the interactive map.
8. Change target fish and confirm each forecast card updates its fishing score and explanation.
9. Start a fishing day online, wait for its weather, and confirm the journal calendar cell retains the observed weather after restarting Fiskentra.

## v0.9 implementation

- Every `Catch` point can hold species, length in centimetres, weight in kilograms, lure/bait, notes and released/kept status.
- Existing points remain readable because catch details are optional.
- Saved, selected-map and fishing-log event cards show the catch summary.
- Catch photos use Android's document picker and are copied into Fiskentra's private app storage for reliable offline access.
- Photos can be changed or removed and are intentionally not sent as device file paths to Supabase.
- The existing Supabase row is upserted by its stable point ID, so editing details does not create duplicates.
- `catch_details jsonb`, its object constraint, UPDATE grant and device-header RLS policy are applied in production.
- A real anonymous Data API test completed with HTTP 201 insert, HTTP 200 upsert and HTTP 204 cleanup delete.

## v0.9.1 physical test gate

1. Install v0.9.1 over v0.8 or v0.9 without clearing app data.
2. Create a Catch with Flic 2, open Saved and add all catch-detail fields.
3. Add a photo, restart Fiskentra, then change and remove the photo.
4. Confirm the catch summary is visible in Saved, Map and the fishing-day Log.
5. Edit a cloud-synced catch and confirm Supabase keeps one row and updates `catch_details`.
6. Repeat an edit offline, restore connectivity and use `SYNC LOCAL POINTS`.
7. Retry points that failed in v0.9 with HTTP 401 and confirm they become `Synced to cloud` without being recreated locally.

## v0.10 implementation

- `PointSyncQueue` is initialized at process start and shared by MainActivity and the foreground Flic service.
- Android's validated-network callback automatically retries persisted unsynced points after connectivity returns.
- Uploads are serialized, and one point is attempted only once per queue run.
- A newer edit made during an upload remains pending and is uploaded again after the older request finishes.
- Cloud delete is placed on the same remote executor after any active upload, preventing the upload from recreating a deleted row.
- Existing v0.6-v0.9.1 sync states remain readable; interrupted `syncing` states older than 20 seconds recover to the retry queue and interrupted deletes become retryable.
- No database migration or broader Supabase grant is required.

## v0.10 physical test completed

The user confirmed the v0.10 offline queue and automatic recovery flow on a physical Android phone on 2026-08-30.

1. Install v0.10 over v0.9.1 without clearing app data.
2. Save three different Flic points offline and confirm all three remain local with automatic retry queued.
3. Background Fiskentra, restore internet and verify all three sync without pressing `SYNC LOCAL POINTS`.
4. Edit one Catch twice during sync and verify one Supabase row contains the latest details.
5. Delete a point while another upload is running and verify the deleted row does not reappear.
6. Restart the app with pending points and confirm the persisted queue resumes automatically.

## v0.11 implementation

- Replaced the deprecated `outdoor-v2` style with MapTiler `outdoor-v4`.
- Added selectable `hybrid-v4`, `topo-v4` and `ocean-v4` base layers alongside Outdoor.
- The chosen layer is stored locally and restored after app restart.
- GPS, trip track, saved markers, collision spreading and selected-point highlighting remain Fiskentra overlays above every base layer.
- The Map page reports layer loading, success and failure without displaying request URLs or the MapTiler key.
- Ocean is described accurately as marine bathymetry; it does not promise depth data for inland lakes.

## v0.11 physical test gate

1. Install v0.11 over v0.10 without uninstalling or clearing data.
2. Switch through Outdoor, Satellite, Topographic and Ocean and confirm each reaches its ready state.
3. Confirm the live GPS dot, existing saved markers and active trip track remain visible on all four layers.
4. Open a saved point on Map, switch layers and confirm the point stays centered and highlighted.
5. Restart Fiskentra and confirm the last selected layer is restored.
6. Test without internet and confirm the layer status reports failure while local point saving and Flic actions continue normally.

## v0.12.1 implementation

- Added an optional Profile destination; authentication never gates Flic, GPS, Saved, Map or the fishing journal.
- Replaced temporary auth dialogs with dedicated Sign Up, Sign In and password-recovery forms.
- Email/password sign-up handles confirmation, resend and app deep-link return with safe user-facing errors.
- Password recovery opens a verified in-app new-password form.
- A valid access/refresh-token session is restored on launch and refreshed through Supabase Auth when needed.
- Passwords are never persisted. Session JSON is encrypted with AES-GCM using a non-exportable Android Keystore key.
- Sign-out attempts to revoke the Supabase session and always clears the encrypted local session, including offline use.
- Display names are stored in `public.profiles`, not trusted JWT user metadata, and can be edited from the Profile screen.
- Migration `20260831121112_add_private_user_profiles` created the profiles table with owner-only SELECT, INSERT and UPDATE policies.
- Migration `20260831153334_repair_profiles_primary_key` removed an accidental empty `Email` array column and restored `profiles.id` as the primary key without deleting the existing profile row.
- Migration `20260831163827_optimize_saved_points_owner_policy_lookup` removes the remaining Supabase performance-advisor warnings without changing device ownership rules.
- Anonymous Data API access to profiles returns HTTP 401. Security Advisor reports only the Pro-plan leaked-password-protection recommendation.
- Android system-bar insets prevent the title and bottom navigation from overlapping status/navigation controls; five primary tabs replace the crowded six-tab layout, with Device still reachable from Home.
- Existing saved points stay device-owned in v0.12, avoiding any silent ownership or data migration during sign-in.

## v0.12.1 physical test gate

1. Add `com.fiskentra.app://auth/callback` to Supabase Auth Redirect URLs, then install v0.12.1 over v0.11/v0.12 without clearing app data.
2. Verify all field features still work in local mode without creating an account.
3. Create an email/password account and confirm the email returns directly to Fiskentra.
4. Edit the display name and restart Fiskentra; the same account and profile should be restored.
5. Disable internet, reopen Profile and save all three Flic actions; the profile remains locally available and points remain local-first.
6. Restore internet and confirm the existing automatic point-sync behavior is unchanged.
7. Sign out and verify local points and journal data remain, while Profile returns to local mode.
8. Request a password reset, open the newest email and set a new password in Fiskentra.

## v0.13 implementation

- Added a Settings screen reachable from Profile in both local mode and signed-in mode.
- Metric remains the default; Imperial changes displayed weather, catch summaries and catch-entry fields while canonical local/cloud storage stays in centimetres, kilograms and Celsius.
- Explore can default to Map or Weather when opened from another bottom tab. Opening a saved point always overrides the preference and opens the map itself.
- Outdoor, Satellite, Topographic and Ocean can be selected from Settings using the same persisted map-layer preference as Explore.
- Pike, Perch, Zander, Trout and Carp can be selected as the default fishing-outlook species using the existing weather preference.
- Delete confirmation remains enabled by default and can be disabled explicitly. Failed cloud deletion still preserves the local point.
- Settings shows local saved-point and pending-sync counts and exposes the existing safe sync action without adding a destructive clear-data command.
- All settings remain local to the phone and work without an account or internet connection.

## v0.13 physical test gate

1. Install v0.13 over v0.12.1 without uninstalling or clearing data; confirm existing Flic pairing, points, journal and account session remain.
2. Open Profile → Settings in local mode and signed-in mode.
3. Switch Metric to Imperial and verify Weather, forecast cards, calendar temperatures and catch summaries show °F, mph, inches and pounds.
4. Edit an existing catch in Imperial, switch back to Metric and confirm the converted value is unchanged rather than duplicated or corrupted.
5. Select Weather as the Explore start page, leave Explore, then reopen it from another tab. Open a saved point and confirm it still opens Map.
6. Change the default map layer and target fish, restart Fiskentra and confirm both choices persist.
7. Disable delete confirmation, delete a disposable point, then re-enable confirmation and verify the dialog returns.
8. Create a point offline, confirm Settings reports one pending point, restore internet and use Sync Now.
