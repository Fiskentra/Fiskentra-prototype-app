# Fiskentra v0.23 — road navigation

The Navigation panel offers Driving, Walking and Direct. Choose a saved point, enter coordinates or long-press any location and select Navigate here. Driving and Walking request an actual route, draw its geometry, show the next maneuver and distance to it, and display remaining route distance, travel time and estimated arrival time. Steps opens the complete maneuver list; tapping a step centers the map there. Tap the guidance banner for route overview, recalculation, destination and routing information.

GPS is projected onto the route geometry. Remaining time uses the remaining portions of maneuver durations. Guidance pauses for stale GPS or accuracy worse than 50 m. Sustained deviation triggers recalculation after 8 seconds, with at least 30 seconds between requests and a 60-second delay after failure. Arrival requires proximity to the route end and GPS accuracy within 25 m. If a destination is off the mapped network, a dashed connector and explicit distance distinguish the route endpoint from the selected point.

The last successful route, profile and target persist locally and can be restored after changing screens or restarting. Cached geometry and steps work without internet; building another route or recalculating requires internet. Offline basemap downloads remain separate. Direct mode retains compass guidance to an arbitrary point.

## Routing service and limits

- Prototype endpoint: `https://valhalla1.openstreetmap.de/route` (Valhalla / FOSSGIS, OpenStreetMap data). Set `ROUTING_URL` in local.properties to use another HTTPS Valhalla endpoint; rebuild afterward. No API key is required for this prototype endpoint.
- Start and destination coordinates are sent only when building/recalculating. The app sends its client identifier, bounds response size, cancels obsolete results and does not send sensor updates to the server.
- ETA is an estimate **without live traffic**. Instructions use English to match the current map UI. No voice guidance is included.
- Turn guidance currently updates while the map screen is active. Background trip recording is separate; this change does not add background turn announcements.
- The public endpoint is a fair-use demo service, not a production SLA. Before distributing a public app, arrange hosting or follow the demo operator's published app-use requirements.
- OSM coverage/access rules can be incomplete, especially around shorelines and private paths. An unmapped connector does not represent a verified path.

Primary references: [Route API](https://valhalla.github.io/valhalla/api/route/api-reference/), [polyline6 decoding](https://valhalla.github.io/valhalla/api/decoding/), [demo server policy](https://github.com/valhalla/valhalla#demo-server).

## Validation

- `assembleDebug lintDebug` succeeds (0 errors, 74 warnings, primarily existing prototype/localization warnings).
- 136 JVM checks pass: the existing 88, 31 route geometry/progress checks and 17 parser checks. Route tests include turns, ETA progression, backward movement, off-route positions, endpoint gaps and loops.
- `scripts/Test-RoutingParser.ps1` uses public Berlin fixtures in `tests/fixtures/` captured from the demo endpoint on 2026-09-13; they contain no device locations. Driving returned 1.89 km / 267.052 s; Walking returned 1.91 km / 1374.48 s. The script downloads the fixed test-only org.json 20240303 JAR if missing.
- Device screenshots are local under `qa/map-v023/` and excluded from Git because they contain actual device locations.
- Installed over v0.22.2 on OPPO CPH2609 without deleting data. Verified real Driving and Walking requests to an existing point, different road/path geometry and durations, both Steps lists, full route overview and restoration after APK update and Forecast/Map transitions. No points or trips were created or deleted. Test navigation was stopped afterward.
- All seven weather swipe/button regression checks pass on the repeat run. The first run missed one return swipe while Forecast was loading; a manual repeat and the complete repeat run passed. Logo and forecast remain visible.
- Physical driving, sustained GPS loss and an actual offline reroute are not claimed as device-tested; calculation behavior is covered by deterministic tests.

APK: `build/distributions/Fiskentra-v0.23-road-navigation.apk`.
