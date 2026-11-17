# Fiskentra 0.28 - fishing map layers

Version 0.28 (versionCode 41) adds five optional layers to the active Google map. Open the layer icon in the search bar to toggle them. Existing saved-point, current-track and point-label switches remain available, along with the Map types button.

- **Wind:** speed, source time and direction at the selected point, otherwise a fresh GPS position or the map centre. The arrow points downwind; the label reports the direction wind comes from. Data comes from the existing Open-Meteo client. Valid saved observations can be restored after an offline restart for up to one hour, within 5 km of their original location; saved or stale data is labelled.
- **Depth contours:** EMODnet marine bathymetry shading and contours with adjustable opacity. Layer options include the source colour legend and its depth reference.
- **Sea marks:** transparent OpenSeaMap chart symbols. Empty views and request failures have separate statuses.
- **Catch heatmap:** density of the user's recorded catches with valid coordinates. It works locally, follows existing point/date filters, and adds a species filter and opacity control.
- **Past trips:** up to 12 selected completed routes with distinct colours. Recording pauses and invalid coordinates remain separate segments. The chooser supports all recent trips, individual selections and no trips.

Layer switches, opacity, species and trip selections persist across restarts. Tap the layer status panel, or open Layer options from Tools, for controls, source links and Retry online layers. The status panel fits all five layer statuses. Repeated GPS updates retain unchanged overlays and avoid unnecessary tile reloads.

## Verification

Validation on 2026-10-28: debug assembly and Android Lint passed with zero errors. All 183 standalone checks passed: catch density/history (35), marine tile policy (29), map debug fixtures (17), existing map policies (66), wind policy (16) and persistent wind-cache regression (20).

USB verification on Samsung Galaxy A33 covered all eight switches, real wind and marine layer loading, the depth legend, a local fixture with 30 catches and three distinct routes, species filtering to 16 catches, and restoration of a two-trip selection and layer settings after a full restart. Offline restart checks temporarily aged only the weather-cache timestamps: a 35-minute observation was restored as saved/offline, while a 61-minute observation was rejected and its arrow hidden. The original weather cache and network settings were restored afterward.

The final APK was installed as an update. The saved-point data checksum remained identical to the backup taken before the original layer installation. Device evidence and backups are stored locally under the ignored `qa/layers-v028/` directory; no synthetic catches or routes are written to user storage.

## Coverage and limits

Depths and sea marks depend on provider coverage and connectivity; inland waters and near-shore detail may be missing. A loaded layer is reference information and does not verify waterway access, boat clearance or a safe route. The heatmap describes saved catches, rather than predicting fish. Wind represents one sampled location, rather than an interpolated regional field.

The history display downsamples long routes toward 8,000 points per trip while preserving segment endpoints; highly fragmented routes can exceed that target. Device checks are stationary and do not replace field testing on the water.
