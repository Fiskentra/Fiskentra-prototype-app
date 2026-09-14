# Track recording with Flic 2 hold

Version: 0.23.1-track-hold (34), based on 0.23-road-navigation (33).

- Idle → start recording.
- Recording → pause.
- Paused → resume the existing track.

Hold is handled immediately by the foreground service, before GPS point capture. It does not save a tackle-change event or wait in the GPS event queue. Single and double press retain the existing waypoint/catch mapping. The pairing test remains isolated from real capture.

TrackStore keeps the active trip open while paused, preserves its start time and points, and retains the existing segment-break behavior on resume. Existing manually saved tackle-change points remain supported.

Validation: Android debug build and lint succeeded; 6 Flic policy, 10 track-point, 18 field-navigation, 31 road-route and 16 isolated TrackStore checks passed. The TrackStore tests cover start without GPS, pause, resume, store recreation, route retention, original start time, segment breaks and starting after finish. The website build and 4 packaging tests passed; RU start/pause/resume and EN/LV copy were checked in the browser.

Installed over 0.23 on the connected CPH2609 without clearing app data. Confirmed the version, running map/navigation UI and all 131 pre-existing route points. Physical Flic 2 was disconnected during validation, so a hardware hold was not tested. No test trip was started on the user's phone.

APK: build/distributions/Fiskentra-v0.23.1-track-hold.apk.
