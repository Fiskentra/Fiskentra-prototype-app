# Address navigation — 0.25 follow-up

The navigation panel now offers **Search address**, or **Change destination** during guidance. The header search opens the same destination search. Places is the initial tab; My points remains available for local search.

Enter a street, house number and city, select the matching result, then choose **Start driving** or **Start walking**. These actions stay in the sheet footer. Submitting the search or selecting a result dismisses the keyboard. Back to results, editing the query and switching sources clear the selected destination actions.

Search and preview leave the active route intact. Starting a route updates its destination and travel mode together, cancels obsolete route requests and persists both settings. Selecting a search result does not create a saved point. Replacing active recorded-track guidance retains its existing confirmation.

## Result handling

- Searches remain worldwide unless the user enables Search this map area. Map-center proximity is a ranking bias.
- Address labels retain the house number and the provider's formatted address order.
- Building bounds do not prevent navigation to an exact address. Point previews focus on their coordinates rather than fitting the building rectangle.
- A street-only fallback, region or water body requires choosing an exact destination on the map. MapTiler's `address` type also includes residential streets, so results without its house-number field are normalized as roads.
- Search requires internet and coverage depends on provider data. A matching city/street is not proof that the requested house exists in the data. New road routes require internet; existing ETA and unmapped endpoint-gap indicators remain in use.

Provider references: [geocoding API](https://docs.maptiler.com/cloud/api/geocoding/) and [place types](https://docs.maptiler.com/guides/location-services/geocoding-search/place-types-values/).

## Verification — 2026-09-16

- `Test-MapGuidanceSearch.ps1`: 45 backtrack + 53 search assertions passed, including address formatting, building bounds, street fallbacks, broad-area types and empty provider type tokens.
- `Test-RoadRoute.ps1`: 40 assertions passed; `Test-RoutingParser.ps1`: 23 assertions passed. Total focused checks: 161.
- Final `assembleDebug lintDebug`: successful in 43 seconds, 0 errors / 102 warnings.
- OnePlus CPH2609 / Android 15: real address search in Sweden and the UK. Driving to one address produced turns/ETA; changing to a second address with Start walking produced a pedestrian route. Both the second address and pedestrian mode survived APK replacement/restart.
- Previewing the UK result and closing search preserved all existing destination fields and the travel mode. Final-APK checks also covered street-only protection, clearing footer actions, switching to My points and starting Driving again from an exact address.
- Device font scale remained 1.0; app interface remained English. Tests were stationary: physical arrival and rerouting during movement were not revalidated.

Local artifact: `artifacts/Fiskentra-0.25-address-navigation-debug.apk` (versionName 0.25, versionCode 37), 57,346,640 bytes.

SHA-256: `27C993421651ED5F913DA3DB08547879CF5C8068EF7C10F3E036094F05462937`.

Private screenshots, device checks and build logs are under ignored `qa/address-navigation/`. The original 0.25 release artifact and its validation record remain separate.
