# Fiskentra Android Prototype

Native Android MVP/prototype for Fiskentra — an outdoor companion for fishing, hunting, hiking, tourism and general adventures.

## Project stage

- Current version: `v0.14` Internal Prototype / Pre-Alpha.
- Hardware decision: Fiskentra will use the **Flic 2 Single Pack**. BlueUP SafeX Lite is no longer planned.
- Current target: validate first-run onboarding while retaining v0.13 settings, v0.12.1 accounts, v0.11 map layers, v0.10 automatic offline sync, v0.9.1 catch details, v0.8 weather, the confirmed v0.7 journal and v0.6.2 Flic behavior.
- Launch readiness: not ready for public users.

## What works in this prototype

- Native Android app, minimum Android 8.0 (API 26).
- GPS permission + live location acquisition using Android `LocationManager` (no Google dependency).
- Save a current outdoor "Moment" with latitude/longitude/time to local device storage.
- Start/stop an in-app trip track; route points persist locally and render on the field map.
- Saved points list with delete confirmation that removes cloud-synced points from Supabase before removing them locally.
- Clear delete status in the Saved screen: deleting from cloud, deleted from cloud, or cloud delete failed.
- Per-point cloud sync status in the Saved screen: saved locally, syncing, synced, deleting, or sync/delete pending.
- Saved screen backfill action to re-sync older local points and mark them as cloud synced.
- A process-wide sequential sync queue shared by the app and Flic service, preventing duplicate concurrent uploads.
- Automatic retry when Android validates internet access again, including while the foreground Flic service is active.
- Saved point map action: tap a saved point or `OPEN MAP` to center and highlight it on the selected MapTiler layer.
- Normal Map tab fits the camera around all saved points so different saved places appear on the map together.
- Selectable MapTiler Outdoor, Satellite/Hybrid, Topographic and Ocean maps powered by MapLibre Native Android.
- The selected layer persists across app restarts, while Fiskentra overlays keep current position, track, saved points and selected point visible on every layer.
- Map loading and failure status is shown without exposing the MapTiler API key.
- Optional Supabase email/password registration and sign-in; local Flic, GPS, map and journal features remain available without an account.
- Full-page Sign Up and Sign In forms with confirmation-email resend, password visibility, safe inline validation and password recovery.
- Email confirmation and password-reset links can return directly to Fiskentra through the private Android callback `com.fiskentra.app://auth/callback`.
- Access and refresh tokens are encrypted on-device with an Android Keystore AES-GCM key; passwords are never stored.
- `public.profiles` uses owner-only authenticated RLS policies and is inaccessible to unauthenticated clients.
- Profile now opens a device-local Settings screen in both local and signed-in modes.
- Metric/Imperial display and catch-entry units can be changed without changing canonical local or Supabase values.
- Explore start page, map layer, target fish and delete confirmation persist across restarts.
- Settings reports saved and pending-sync counts and can start the existing safe retry queue.
- New installs open a four-step quick start before Android permission prompts, explaining local-first capture, field permissions, Flic 2 actions and optional accounts.
- Existing installations skip onboarding automatically, while `Profile → Settings → View Quick Start` can reopen it at any time.
- Type-colored map markers and legend, so `Catch`, `Waypoint`, `Tackle change` and other saved point types are visually different on the map.
- Official `flic2lib-android` 2.0.1 pairing, persisted SDK pairing and foreground reconnect flow.
- Real Flic 2 single-press, double-press and hold callbacks routed to the existing button/GPS action mapping.
- Protection against stale queued Flic events older than 15 seconds after reconnect.
- Collision-aware map markers that reserve the live GPS position, spread overlapping Flic-created points around it and keep both layers visible.
- Device UI with both real pairing and manual single/double/hold test actions for end-to-end validation.
- Foreground service with a persistent notification that keeps one Flic connection and GPS updates active while the screen is off.
- Background capture that requires a GPS fix no older than 30 seconds, waits up to 20 seconds for a new fix and saves locally before cloud sync.
- Local fishing-day sessions that can be started and finished from Home or the new Log tab.
- Monthly fishing calendar with marked journal dates, multiple sessions per day and persistent history.
- Automatic fishing-day summaries for duration, catches, waypoints, tackle changes and all saved events.
- Journal event rows that open the saved GPS position directly on the field map.
- Open-Meteo current conditions captured for new saved points, including temperature, apparent temperature, humidity, precipitation, pressure and wind.
- Weather conditions stored locally for fishing-day and trip-track start/finish, with a 15-minute nearby-location cache that avoids repeated network calls.
- Saved-point weather can be added or refreshed later; a weather failure never blocks local point capture or Flic actions.
- Map and Weather are adjacent Explore pages: use the visible tabs or swipe left from the map's right edge and swipe right from Weather.
- Weather includes the current conditions and a cached seven-day Open-Meteo forecast.
- Pike, perch, zander, trout and carp can be selected for a transparent weather-only fishing outlook; it is explicitly not a catch guarantee.
- Fishing-calendar cells show the saved weather icon and temperature when a journal session captured conditions for that day.
- Catch points can store fish species, length, weight, lure/bait, notes and released/kept status.
- Catch details are local-first, survive restarts and update the existing Supabase point instead of creating a duplicate row.
- An optional catch photo is copied into Fiskentra's private on-device storage; it can be changed or removed without requesting broad photo-library permission.
- Dark outdoor-first prototype visual system.
- Official Fiskentra compass/pin branding supplied for the prototype, including the launcher icon.

## Important Flic integration note

The selected hardware is the [Flic 2 Single Pack](https://flic.io/shop/flic-2-single-pack), replacing the previously planned BlueUP SafeX Lite. The old generic `FiskentraBleManager` source is retained only as a dormant BLE diagnostic utility. The app UI no longer uses it, and raw GATT notifications cannot create saved points.

Fiskentra will use the official [`flic2lib-android`](https://github.com/50ButtonsEach/flic2lib-android) SDK. It owns Flic 2 scanning, pairing, reconnect behavior and button callbacks.

Verified hardware details relevant to the prototype:

- Triggers: push, double push and hold.
- Connection: Bluetooth 5 LE with direct Android support; no Flic Hub is required for phone use.
- Battery: replaceable CR2032, advertised for up to three years.
- Range: advertised up to 50 m indoors.
- Protection: IP44 splashproof, not waterproof or suitable for submersion.

The Fiskentra action mapping remains:

| Flic action | Saved point type |
|---|---|
| Single press | `Catch` |
| Double press | `Waypoint` |
| Hold | `Tackle change` |

The `v0.5` implementation state is:

1. Complete: add `flic2lib-android` 2.0.1 and replace the UI's generic scan/connect flow with Flic 2 SDK pairing.
2. Complete: route click, double-click and hold callbacks through the existing action mapping.
3. Complete: ignore queued events older than 15 seconds so an old press cannot save a new GPS point after reconnection.
4. Ready for hardware: validate local save, GPS, Supabase sync and reconnect behavior on a physical Android phone.
5. Ready for field test: test the button inside a splash-resistant case or wristband; the button itself is only IP44.

The Flic Android app may remain installed, but Fiskentra creates and stores its own official SDK pairing.

### Background and locked-phone test

1. Install and open Fiskentra v0.6.1.
2. Allow Location, Nearby devices and Notifications.
3. Confirm the persistent **Fiskentra field button active** notification appears.
4. Wait until Flic 2 reports ready, lock the phone and test single press, double press and hold.
5. Unlock the phone and confirm exactly one new `Catch`, `Waypoint` and `Tackle change` point.
6. Turn off internet and repeat; the points must appear locally. After connectivity returns, open Saved and tap **Sync local points**.

Open Fiskentra once after every phone reboot so Android can start the location foreground service from a visible activity. Force-stopping the app disables the service until Fiskentra is opened again.

### v0.6.2 Flic and offline regression test

1. Install v0.6.2 over the current Fiskentra installation and open it once.
2. Confirm **Flic 2 ready** and **BACKGROUND CAPTURE ACTIVE** on Device.
3. Test single press, double press and hold with internet available.
4. Disable Wi-Fi and mobile data while leaving Bluetooth and Location enabled.
5. Repeat all three Flic actions. Fiskentra now acknowledges the press before checking GPS.
6. Open Saved and verify all three points are present with **Saved locally · offline**.
7. Restore internet, tap **Sync local points** and verify they become **Synced to cloud**.

If Android cannot start the foreground service, v0.6.1 restores foreground Activity capture instead of discarding Flic actions. For background capture, Fiskentra prefers a GPS fix newer than 30 seconds, can immediately use a recent fix up to five minutes old when necessary, and uses a cached fix up to 30 minutes old after waiting for GPS. Cached-location saves are clearly identified in the point note and service status.

v0.6.2 additionally prevents an offline point from remaining indefinitely in `Syncing to Supabase`. Fiskentra checks connectivity before and immediately after scheduling an upload. If Android briefly reports a disconnected network as available, Saved automatically changes a sync attempt older than 20 seconds to `sync interrupted · retry when online`.

### Fishing day log and calendar test

This v0.7 flow was physically validated by the user on 2026-08-30.

1. Install and open Fiskentra v0.7 without uninstalling the previous build.
2. Open **Log** and tap **START FISHING DAY**.
3. Create a Catch, Waypoint and Tackle change using Flic 2 or the Device test buttons.
4. Return to **Log** and verify the counters, duration and event rows.
5. Tap an event to verify that its exact point opens on the map.
6. Tap **FINISH FISHING DAY** and confirm the date remains marked in the calendar.
7. Close and reopen Fiskentra and confirm the completed journal is still available.

Fishing-day sessions are local-first. An event is included when its saved timestamp falls between the session start and finish times, so screen-off Flic events appear after the app is reopened.

### Pair and test your Flic 2

1. First verify the button works in the Flic Android app and update its firmware if the app offers an update.
2. Install and open Fiskentra v0.10, then allow Location, Nearby devices and Notifications.
3. Open **Device** and tap **PAIR FLIC 2**.
4. Hold the Flic 2 for 6 seconds until it glows. Keep it close to the phone and accept Android's **Pair & connect** dialog.
5. Wait for **Flic 2 ready**, then test single press, double press and hold with a live GPS fix.

If pairing says the button is busy with another device, temporarily disconnect it from that other phone/device and retry. Do not factory-reset it unless normal pairing repeatedly fails.

## Open and run

1. Install current stable Android Studio and Android SDK 35.
2. Open this folder as a project.
3. Let Android Studio use its bundled JDK and sync the included Gradle wrapper.
4. Run on a physical Android phone. BLE and real GPS are much easier to validate on hardware than an emulator.
5. Grant Location and Nearby Devices permissions.

A debug APK has been successfully compiled with Android SDK 35, the Android Gradle Plugin's default Build Tools and the Android Studio bundled JDK. The project uses MapLibre Native Android for the real map view.

If Android Studio fails right after the MapTiler update, make sure `MapTilerMapView.java` imports `org.maplibre.android.*`, not the old `com.mapbox.mapboxsdk.*` package. Current MapLibre Native Android uses the `org.maplibre.android` namespace.

## MapTiler fishing map

The first launch map stack is MapLibre Native Android + MapTiler Outdoor. MapLibre embeds the interactive map view inside the native Android app. v0.11 uses the current MapTiler v4 styles and lets the user switch between them:

```text
https://api.maptiler.com/maps/<style-id>/style.json?key=<MAPTILER_API_KEY>
```

| Layer | MapTiler style | Intended use |
|---|---|---|
| Outdoor | `outdoor-v4` | Trails, outdoor landmarks, terrain and contours |
| Satellite | `hybrid-v4` | Aerial imagery with labels and roads |
| Topographic | `topo-v4` | General topographic detail |
| Ocean | `ocean-v4` | Marine seabed and bathymetry; inland lake depth is not guaranteed |

`local.properties.example` includes `MAPTILER_API_KEY`. You can keep the supplied prototype key or replace it with another MapTiler key in your local `local.properties`. Gradle exposes it to `BuildConfig.MAPTILER_API_KEY`.

The Android dependency is:

```kotlin
implementation("org.maplibre.gl:android-sdk:13.4.1")
```

After changing this dependency, run **File -> Sync Project with Gradle Files**, then **Build -> Clean Project**, then **Rebuild Project**.

The current fishing map shows:

- Four selectable real base-map layers with the choice saved locally.
- A clear loading or error message when the selected online layer is unavailable.
- Current phone location.
- Local trip track.
- Saved point markers with different colors and letters: `Catch`, `Waypoint`, `Tackle change`, plus older prototype types such as `Sighting`, `Camp`, `Hazard` and `Map`.
- Compact marker legend on the Map screen.
- Automatic camera fit around all saved points when the Map tab is opened normally.
- Selected saved point highlight when opened from the Saved screen.

The Ocean layer provides MapTiler's marine bathymetry. Dedicated inland lake depth, fishing zones and downloadable offline map packs are still future work.

### v0.11 map-layer test

1. Install v0.11 over v0.10 without clearing app data.
2. Open Map and switch through `OUTDOOR`, `SATELLITE`, `TOPO` and `OCEAN`.
3. Confirm every layer reaches its ready state and that GPS, track and saved markers remain visible.
4. Open a point from Saved and confirm it remains centered and highlighted on every layer.
5. Leave one layer selected, restart Fiskentra and confirm the same layer is restored.
6. Disable internet, reopen Map and confirm a clear loading/error state appears without affecting local points or Flic capture.

### v0.12.1 account and profile test

1. Install v0.12.1 over v0.11 or v0.12 without uninstalling or clearing app data.
2. Open `Profile` and confirm local mode still allows Map, Saved, Log, Device and all three Flic actions.
3. Create an account with an email address you can receive mail at and a password of at least eight characters.
4. Open the Supabase confirmation email and confirm that Android returns directly to Fiskentra and signs in.
5. Change the display name, restart Fiskentra and confirm the encrypted session and profile name are restored.
6. Disable internet and confirm the existing account remains visible offline and local field capture still works.
7. Sign out and confirm saved points, the journal and Flic pairing remain on the phone.
8. Confirm an incorrect password shows a safe error and never exposes an access token or raw server response.
9. Use `FORGOT PASSWORD?`, open the newest reset email and set a new password inside Fiskentra.

### v0.13 settings test

1. Install v0.13 over v0.12.1 without uninstalling or clearing app data.
2. Open `Profile → Settings` and switch Metric/Imperial; verify weather, forecast, calendar and catch values update.
3. Edit an existing catch in Imperial, switch back to Metric and confirm the same canonical value is retained.
4. Select the Explore start page, map layer and target fish, restart Fiskentra and confirm all choices persist.
5. Open a saved point while Weather is the Explore default and confirm the selected point still opens on Map.
6. Disable and re-enable delete confirmation using a disposable point.
7. Create a point offline and confirm Settings reports it as pending, then restore internet and use `SYNC NOW`.

### v0.14 onboarding test

1. Install v0.14 over v0.13 without clearing app data and confirm it opens the normal Home screen rather than interrupting an existing user.
2. Open `Profile → Settings → View Quick Start` and move through all four steps with Back and Continue.
3. On the permission step, verify Location, Nearby devices and Notification statuses match Android settings and `ALLOW FIELD ACCESS` opens only missing permission requests.
4. Confirm the Flic page shows single press = Catch, double press = Waypoint and hold = Tackle change.
5. Finish with `START FISKENTRA` and confirm the normal Home screen appears with existing data unchanged.
6. Reopen Quick Start and finish with `START AND SET UP FLIC 2`; confirm Device opens without losing the existing pairing.
7. On a clean installation, confirm Quick Start appears before Android permission dialogs and does not return after completion or Skip.

## Supabase backend

Fiskentra is prepared for Supabase project `dwlbefpmwzmhutlvqfmu`.

1. Copy `local.properties.example` to `local.properties`.
2. Keep your Android SDK path in `sdk.dir`.
3. In Supabase, open the Fiskentra project's **Connect** dialog and copy the current **publishable** key (`sb_publishable_...`). Put it in `SUPABASE_PUBLISHABLE_KEY`.
4. Never place a `service_role` key or `sb_secret_...` key in this Android project.

`local.properties` is ignored by Git. Gradle exposes only the URL and publishable key to `BuildConfig`, and `SupabaseConfig` is the single Android-side source for backend configuration. At runtime, `SupabaseConnection` performs a lightweight REST health check and the Home screen reports whether Fiskentra cloud is reachable.

Saved points can sync to Supabase through the REST Data API after this prototype table is created. The app stores each point locally first, enriches it with Open-Meteo conditions when available, then shows "Syncing to Supabase...", "Synced to cloud" or "Saved locally ... sync pending" in Saved. v0.10 keeps one sequential queue for the Activity and background Flic service, recovers interrupted states and retries unsynced points automatically when Android validates internet access again. The `SYNC LOCAL POINTS` button remains as a manual retry. v0.9 upserts by the stable point ID, so catch-detail edits update the existing row instead of creating duplicates. v0.9.1 adds the required `X-Device-Id` header to that upsert so the device-owned RLS INSERT/UPDATE policies accept it. If an optional JSONB column is unavailable, core point sync retries without that enrichment.

v0.12.1 adds optional Supabase Auth without making login a prerequisite for field use. Email confirmation is enabled on the Fiskentra Supabase project. Add `com.fiskentra.app://auth/callback` under **Authentication → URL Configuration → Redirect URLs** so confirmation and recovery emails return to the Android app. The default Supabase mailer is for team-member testing only; configure custom SMTP under **Authentication → Emails → SMTP Settings** before inviting external beta users. The account profile table is private by default: an authenticated user can select, insert and update only the row whose `id` equals `auth.uid()`. Existing saved points remain device-owned and are not silently reassigned when a user signs in.

The applied profile migrations are tracked at `supabase/migrations/20260831121112_add_private_user_profiles.sql` and `supabase/migrations/20260831153334_repair_profiles_primary_key.sql`. The follow-up `20260831163827_optimize_saved_points_owner_policy_lookup.sql` removes per-row request-header evaluation from the existing device-owner policies.

When a saved point is deleted, the app first shows `Deleting from cloud...`, then sends `DELETE /rest/v1/saved_points?select=local_id&device_id=eq.<install-id>&local_id=eq.<point-id>` to Supabase with an `X-Device-Id` header. Supabase must have a matching `SELECT` policy because RLS only lets `DELETE` affect rows that are visible to that role. If Supabase returns the deleted row, the app shows `Deleted from cloud` and removes the point from local storage. If the cloud delete fails or returns zero rows, the app shows `Cloud delete failed · try again` and the point stays on the phone so the user can retry with the normal `DELETE` action instead of leaving orphaned GPS data in the database.

```sql
create table if not exists public.saved_points (
  id text primary key,
  device_id text not null,
  local_id bigint not null,
  latitude double precision not null,
  longitude double precision not null,
  recorded_at timestamptz not null,
  type text not null default 'Moment',
  note text not null default '',
  weather jsonb,
  catch_details jsonb,
  created_at timestamptz not null default now(),
  unique (device_id, local_id)
);

alter table public.saved_points
add column if not exists weather jsonb;

alter table public.saved_points
add column if not exists catch_details jsonb;

alter table public.saved_points
drop constraint if exists saved_points_catch_details_object;

alter table public.saved_points
add constraint saved_points_catch_details_object
check (catch_details is null or jsonb_typeof(catch_details) = 'object');

alter table public.saved_points enable row level security;

drop policy if exists "Prototype clients can insert saved points" on public.saved_points;
drop policy if exists "Prototype clients can read own saved points for delete" on public.saved_points;
drop policy if exists "Prototype clients can delete saved points" on public.saved_points;
drop policy if exists "Prototype clients can update own saved points" on public.saved_points;

create policy "Prototype clients can insert saved points"
on public.saved_points
for insert
to anon
with check (true);

create policy "Prototype clients can read own saved points for delete"
on public.saved_points
for select
to anon
using (
  device_id = nullif(
    coalesce(nullif(current_setting('request.headers', true), ''), '{}')::json ->> 'x-device-id',
    ''
  )
);

create policy "Prototype clients can delete saved points"
on public.saved_points
for delete
to anon
using (
  device_id = nullif(
    coalesce(nullif(current_setting('request.headers', true), ''), '{}')::json ->> 'x-device-id',
    ''
  )
);

create policy "Prototype clients can update own saved points"
on public.saved_points
for update
to anon
using (
  device_id = nullif(
    coalesce(nullif((select current_setting('request.headers', true)), ''), '{}')::json ->> 'x-device-id',
    ''
  )
)
with check (
  device_id = nullif(
    coalesce(nullif((select current_setting('request.headers', true)), ''), '{}')::json ->> 'x-device-id',
    ''
  )
);

grant insert on table public.saved_points to anon;
grant select (device_id, local_id) on table public.saved_points to anon;
grant delete on table public.saved_points to anon;
grant update on table public.saved_points to anon;
```

This prototype policy lets the Android app upload and delete its own saved points with the publishable key and the phone's install ID header. It grants only the `device_id` and `local_id` columns for the delete verification read, not latitude/longitude. Use authenticated users and owner-based RLS before enabling account cloud backup.

The `weather` JSONB object is optional and contains only conditions returned for the point coordinates: observation time, Celsius temperature, humidity, precipitation, pressure, wind, WMO weather code, timezone and provider. This migration was applied and verified on Fiskentra Supabase project `dwlbefpmwzmhutlvqfmu` on 2026-08-30; the SQL remains here for reproducible setup.

The optional `catch_details` JSONB object contains species, length, weight, lure, notes and released/kept status. Local Android photo paths are deliberately excluded from cloud JSON. The v0.9 migration and device-owned update policy were applied and verified on the same project on 2026-08-30.

## v0.10 offline recovery test

1. Install v0.10 over v0.9.1 without uninstalling Fiskentra or clearing app data.
2. Disable Wi-Fi and mobile data, then save three different points with Flic 2.
3. Confirm every card says `Saved locally · offline · automatic retry queued`; no card may remain indefinitely in `Syncing`.
4. Leave Fiskentra in the background, restore internet and wait for Android to validate the connection.
5. Reopen Saved and confirm the three points changed to `Synced to cloud` without pressing the manual sync button.
6. Edit a Catch twice quickly during sync and confirm Supabase contains one row with the latest details.
7. Delete a point while another upload is active and confirm it does not reappear in Supabase.
8. Repeat once after force-closing and reopening Fiskentra to verify persisted queue recovery.

This v0.10 offline queue and automatic recovery flow was physically validated by the user on 2026-08-30.

## v0.9.1 catch-details and sync test

1. Install v0.9.1 over v0.8 or v0.9 without uninstalling Fiskentra.
2. Save a Catch with Flic 2 or the Device single-press test.
3. Open Saved, tap `ADD CATCH DETAILS`, enter species, length, weight, lure, notes and released/kept status, then save.
4. Reopen the card and confirm every value remains; open Log and Map and confirm the catch summary appears there too.
5. Tap `ADD PHOTO`, choose an image, restart Fiskentra and confirm the local photo remains. Test `CHANGE PHOTO` and `REMOVE LOCAL PHOTO`.
6. Edit the catch again while online and confirm it remains one Supabase row with updated `catch_details`.
7. Disable internet, edit another Catch, verify the local values remain, then restore internet and use `SYNC LOCAL POINTS`.
8. For points that showed `Supabase point sync HTTP 401` in v0.9, tap `SYNC LOCAL POINTS` and confirm they change to `Synced to cloud`.

## v0.8 weather test

1. Install v0.8 over v0.7 without uninstalling Fiskentra.
2. Keep Location and internet enabled, save a point, then open Saved.
3. Confirm the card shows condition, temperature, wind, humidity, pressure, precipitation and `Open-Meteo`.
4. Open that point on Map and confirm the weather summary appears in the selected-point card.
5. Start a fishing day and a trip track; confirm start weather appears on Home and in Log. Finish both and confirm finish/latest weather remains after reopening the app.
6. Disable Wi-Fi and mobile data, then create Catch, Waypoint and Tackle change with Flic 2. All points must still show `Saved locally · offline`; weather may use the recent 15-minute cache or remain `Weather not captured`.
7. Restore internet, open Saved, tap `ADD WEATHER` on a point without weather, then tap `SYNC LOCAL POINTS` for pending points.
8. Open Map, tap `WEATHER` or swipe left from the right edge, and confirm seven forecast days appear.
9. Select Pike, Perch, Zander, Trout and Carp; confirm the score and explanation change without claiming guaranteed catches.
10. Swipe right to return to Map and verify normal map panning still works.
11. Start a fishing day with internet, wait for weather capture, then open Log and verify its calendar cell contains the saved weather icon and temperature.

To test cloud delete after running the SQL:

```sql
select recorded_at, type, latitude, longitude
from public.saved_points
order by created_at desc;
```

Delete one point in the app, then run the query again. The Saved screen should show `Deleting from cloud...`, then `Deleted from cloud`, and the deleted row should disappear from Supabase. If the app shows `Cloud delete failed · try again`, the point remains in the Saved list so the delete can be retried.

To verify normal map saved-point markers, create several saved points in different nearby places, then open the Map tab directly from the bottom navigation. The Map screen should fit the camera around all saved points and show every marker in its real location. Marker colors and letters identify the saved point type, and the legend explains the visible types.

To verify selected saved point map opening, open Saved, then tap the point card or `OPEN MAP`. The Map screen should open with `SELECTED SAVED POINT`, center the MapTiler Outdoor map on that one location, and show a highlighted marker plus a selected-point details card. `CLEAR` returns the map to the normal all-saved-points view.

If an older app build already removed points from the phone but left rows in Supabase, clean those orphan rows manually by their visible `local_id` values:

```sql
delete from public.saved_points
where local_id in (1786374585128, 1786374588505, 1786374593521);
```

## GitHub repository

Repository target: `https://github.com/Fiskentra/Fiskentra-prototype-app.git`.

The source tree includes a `.gitignore` that excludes local SDK configuration, generated APK/AAB/ZIP files, Gradle build output, and IDE state.

## Suggested next integrations

- Edit saved point type/name and notes.
- Complete physical Flic 2 validation and record the phone/Android version results.
- Offline MapLibre/MapTiler map packs for low-signal fishing areas.
- Track recording in a foreground service for background reliability.
- Weather provider abstraction (Open-Meteo, Tomorrow.io, Meteomatics, etc.) with user-selectable providers.
- Fishing-specific overlays: depth/bathymetry, bite forecast, species and catch log.
- Hunting/adventure modes, SOS/share flows and cloud sync.
