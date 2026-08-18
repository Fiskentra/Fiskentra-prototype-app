# Fiskentra Android Prototype

Native Android MVP/prototype for Fiskentra — an outdoor companion for fishing, hunting, hiking, tourism and general adventures.

## Project stage

- Current version: `v0.5.2` Internal Prototype / Pre-Alpha.
- Hardware decision: Fiskentra will use the **Flic 2 Single Pack**. BlueUP SafeX Lite is no longer planned.
- Current target: finish physical Flic 2 testing on an Android phone, then begin `v0.6` background/locked-phone reliability.
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
- Saved point map action: tap a saved point or `OPEN MAP` to center and highlight it on the MapTiler Outdoor map.
- Normal Map tab fits the camera around all saved points so different saved places appear on the map together.
- Real MapTiler Outdoor map powered by MapLibre Native Android, with Fiskentra overlays for current position, track, saved points and selected point.
- Type-colored map markers and legend, so `Catch`, `Waypoint`, `Tackle change` and other saved point types are visually different on the map.
- Official `flic2lib-android` 2.0.1 pairing, persisted SDK pairing and foreground reconnect flow.
- Real Flic 2 single-press, double-press and hold callbacks routed to the existing button/GPS action mapping.
- Protection against stale queued Flic events older than 15 seconds after reconnect.
- Collision-aware map markers that reserve the live GPS position, spread overlapping Flic-created points around it and keep both layers visible.
- Device UI with both real pairing and manual single/double/hold test actions for end-to-end validation.
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

The Flic Android app may remain installed, but Fiskentra creates and stores its own official SDK pairing. Background and locked-phone reliability remains the separate `v0.6` milestone and will require a foreground service.

### Pair and test your Flic 2

1. First verify the button works in the Flic Android app and update its firmware if the app offers an update.
2. Install and open Fiskentra v0.5, then allow Location and Nearby devices.
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

The first launch map stack is MapLibre Native Android + MapTiler Outdoor. MapLibre embeds the interactive map view inside the native Android app, and MapTiler serves the `outdoor-v2` style through:

```text
https://api.maptiler.com/maps/outdoor-v2/style.json?key=<MAPTILER_API_KEY>
```

`local.properties.example` includes `MAPTILER_API_KEY`. You can keep the supplied prototype key or replace it with another MapTiler key in your local `local.properties`. Gradle exposes it to `BuildConfig.MAPTILER_API_KEY`.

The Android dependency is:

```kotlin
implementation("org.maplibre.gl:android-sdk:13.4.1")
```

After changing this dependency, run **File -> Sync Project with Gradle Files**, then **Build -> Clean Project**, then **Rebuild Project**.

The current fishing map layer shows:

- Real outdoor base map tiles.
- Current phone location.
- Local trip track.
- Saved point markers with different colors and letters: `Catch`, `Waypoint`, `Tackle change`, plus older prototype types such as `Sighting`, `Camp`, `Hazard` and `Map`.
- Compact marker legend on the Map screen.
- Automatic camera fit around all saved points when the Map tab is opened normally.
- Selected saved point highlight when opened from the Saved screen.

Depth/bathymetry, fishing zones and offline map packs are still future layers; this update replaces the prototype canvas with the real map engine and base map.

## Supabase backend

Fiskentra is prepared for Supabase project `dwlbefpmwzmhutlvqfmu`.

1. Copy `local.properties.example` to `local.properties`.
2. Keep your Android SDK path in `sdk.dir`.
3. In Supabase, open the Fiskentra project's **Connect** dialog and copy the current **publishable** key (`sb_publishable_...`). Put it in `SUPABASE_PUBLISHABLE_KEY`.
4. Never place a `service_role` key or `sb_secret_...` key in this Android project.

`local.properties` is ignored by Git. Gradle exposes only the URL and publishable key to `BuildConfig`, and `SupabaseConfig` is the single Android-side source for backend configuration. At runtime, `SupabaseConnection` performs a lightweight REST health check and the Home screen reports whether Fiskentra cloud is reachable.

Saved points can sync to Supabase through the REST Data API after this prototype table is created. The app stores each point locally first, shows "Syncing to Supabase..." while upload is running, then shows "Synced to cloud" or "Saved locally ... sync pending" in the Saved screen. Points created before per-point status existed may still show "Saved locally"; open Saved and tap "Sync local points" to resend them. Duplicate rows are safe because Supabase returns an already-synced conflict for the same device/local point ID.

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
  created_at timestamptz not null default now(),
  unique (device_id, local_id)
);

alter table public.saved_points enable row level security;

drop policy if exists "Prototype clients can insert saved points" on public.saved_points;
drop policy if exists "Prototype clients can read own saved points for delete" on public.saved_points;
drop policy if exists "Prototype clients can delete saved points" on public.saved_points;

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

grant insert on table public.saved_points to anon;
grant select (device_id, local_id) on table public.saved_points to anon;
grant delete on table public.saved_points to anon;
```

This prototype policy lets the Android app upload and delete its own saved points with the publishable key and the phone's install ID header. It grants only the `device_id` and `local_id` columns for the delete verification read, not latitude/longitude. Use authenticated users and owner-based RLS before enabling account cloud backup.

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
