# Fiskentra 0.27 - Google Maps and compact boat guidance

- Replaced the active base map with Google Maps SDK for Android: Normal, Satellite, Terrain and Hybrid. Existing layer preferences migrate to their Google equivalents.
- Expanded the visible map, moved the editable place search to the top and replaced the large navigation/trip panels with compact controls. Flic/GPS, compass, location and point creation remain available; secondary actions are in menus.
- Disabled Walk and Driving entry points. Boat guidance shows direct distance/bearing and supports the existing recorded-track return flow; it does not calculate a verified navigable water route or check depth and hazards.
- Added measured GPS speed with one decimal. Metric uses `km/h`; Imperial uses `mil/h`. Units change only in app settings and use the existing persistent measurement preference.
- Kept the last reliable measured speed through intervening network fixes for up to 10 seconds. Missing, stale or unusable speed is shown as `--`, without estimating motion from coordinate noise.
- Changed arrival to a 50 m area with 75 m exit hysteresis and GPS accuracy of 25 m or better. Nearby guidance shows `Destination reached`; other distances are approximate rather than counting individual metres.
- Preserved Google attribution around sheets and the keyboard, fixed saved-point focus and map overview padding, and kept search listener cleanup safe across reopening.
- Load the Google Maps Android key from ignored local configuration or the environment. Explicit Google map-area downloads are unavailable; local points, tracks and GPS recording remain local-first.

Validation on 2026-10-26: Android debug assembly and lint passed, with 0 errors. The speed and arrival policy suites passed 40 and 38 checks. USB checks on Samsung Galaxy A33 covered map loading, search, saved points, both speed units, the corrected `mil/h` label, arrival, guidance stop, and APK replacement with saved data intact. The public version remains 0.27; the latest build has versionCode 40.

The device checks were stationary. Moving-boat speed, real water routes and physical arrival during travel were not field-tested.
