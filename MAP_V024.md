# Fiskentra v0.24 — navigation reference design

Built on v0.23.1, retaining its Flic hold/track behavior. The source image is `qa/map-v024/reference.png` (user's September 14 navigation mockup).

- Separate maneuver panel: large cyan directional icon, distance, bold action and street name. Close stops navigation; tapping the text opens route options. Provider street names and roundabout exit numbers are parsed separately. Unnamed network segments are labelled explicitly.
- Compact lower panel: Walking / Driving, Steps, voice toggle, travel icon, time and distance, ETA and any endpoint gap. Removes the large Navigation title and duplicated Stop/metric blocks from this mode. Trip and Track panels retain their existing layout.
- Direct compass guidance remains available in Route options. Press the route summary to open options, or to choose a point when idle.
- Voice is opt-in and uses Android TextToSpeech with English navigation instructions. It speaks the current maneuver and approach cues at 300/100/25 m, without repeated announcements from GPS jitter. Arrival is announced once. Turning voice off, losing usable guidance, leaving the screen or backgrounding the app stops playback. An unavailable English engine disables the toggle and displays an explanation. Actual driving/audio intelligibility has not been physically verified.
- Official Google Material Icons are mechanically converted to Android vector resources. License: `app/src/main/res/raw/material_icons_license.txt`; upstream: https://github.com/google/material-design-icons (Apache 2.0). Existing app brand, map, compass and other icons are reused.
- The original Fiskentra symbol/wordmark and weather controls remain. Live geographic data, saved markers and track status are used instead of the mock's example numbers. No arbitrary route endpoint is labelled Parking, and no unmapped gap is presented as a verified walking route.

Validation: `assembleDebug lintDebug` succeeds (0 errors, 73 warnings); 40 road/progress/voice-cue and 23 parser/presentation checks pass. See `design-qa.md` for visual comparison and device interactions. Screenshots under `qa/map-v024/` stay local because they show device locations.

APK: `build/distributions/Fiskentra-v0.24-navigation-design.apk`.
