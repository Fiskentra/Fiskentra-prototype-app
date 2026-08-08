# Fiskentra Android Prototype

Native Android MVP/prototype for Fiskentra — an outdoor companion for fishing, hunting, hiking, tourism and general adventures.

## What works in this prototype

- Native Android app, minimum Android 8.0 (API 26).
- GPS permission + live location acquisition using Android `LocationManager` (no Google dependency).
- Save a current outdoor "Moment" with latitude/longitude/time to local device storage.
- Start/stop an in-app trip track; route points persist locally and render on the field map.
- Saved points list with delete confirmation.
- Lightweight offline map canvas showing your current position and locally saved points around it.
- BLE scan, nearby device list and GATT connection flow.
- Automatic subscription attempt to notify/indicate GATT characteristics after connection.
- SafeX Lite-oriented device UI and a visible test action for end-to-end button/GPS validation.
- Dark outdoor-first prototype visual system.
- Official Fiskentra compass/pin branding supplied for the prototype, including the launcher icon.

## Important SafeX Lite integration note

The exact BlueUP SafeX Lite button-event payload / service UUID depends on its firmware and configuration. The app intentionally does **not** treat every BLE notification as a button press, because that could save false locations from battery/sensor/status notifications. `FiskentraBleManager` exposes raw candidate notifications; once the SafeX Lite profile is known, add a small decoder and route a confirmed press to `MainActivity.saveCurrentMoment("BLE button")`.

To complete the physical-button integration, provide one of:

1. The SafeX Lite GATT/service/button-event specification from BlueUP; or
2. A BLE capture/log from the physical tag while pressing the button (nRF Connect is suitable); or
3. Access to the actual device during Android testing so its services and advertisements can be inspected.

## Open and run

1. Install current stable Android Studio and Android SDK 35.
2. Open this folder as a project.
3. Let Android Studio use JDK 17 and sync the included Gradle 8.9 wrapper.
4. Run on a physical Android phone. BLE and real GPS are much easier to validate on hardware than an emulator.
5. Grant Location and Nearby Devices permissions.

A debug APK has been successfully compiled with Android SDK 35 / Build Tools 35.0.0 and JDK 17. The project uses no third-party runtime libraries.

## Supabase backend

Fiskentra is prepared for Supabase project `dwlbefpmwzmhutlvqfmu`.

1. Copy `local.properties.example` to `local.properties`.
2. Keep your Android SDK path in `sdk.dir`.
3. In Supabase, open the Fiskentra project's **Connect** dialog and copy the current **publishable** key (`sb_publishable_...`). Put it in `SUPABASE_PUBLISHABLE_KEY`.
4. Never place a `service_role` key or `sb_secret_...` key in this Android project.

`local.properties` is ignored by Git. Gradle exposes only the URL and publishable key to `BuildConfig`, and `SupabaseConfig` is the single Android-side source for backend configuration. At runtime, `SupabaseConnection` performs a lightweight REST health check and the Home screen reports whether Fiskentra cloud is reachable.

The database schema/cloud sync is intentionally not enabled until the Fiskentra Supabase project is accessible to the connected development account, so no unauthenticated user-data policies are created by accident.

## GitHub repository

Repository target: `https://github.com/Fiskentra/Fiskentra-prototype-app.git`.

The source tree includes a `.gitignore` that excludes local SDK configuration, generated APK/AAB/ZIP files, Gradle build output, and IDE state.

## Suggested next integrations

- Decode SafeX Lite single/double/long press events.
- Real map tiles + offline map packs (MapLibre is a good fit when a tile/data provider is selected).
- Track recording in a foreground service for background reliability.
- Weather provider abstraction (Open-Meteo, Tomorrow.io, Meteomatics, etc.) with user-selectable providers.
- Fishing-specific overlays: depth/bathymetry, bite forecast, species and catch log.
- Hunting/adventure modes, SOS/share flows and cloud sync.
