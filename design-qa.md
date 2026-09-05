# Fiskentra v0.16.1 native rebuild — design QA

## Scope and evidence
Review dates: 2026-09-03–04. Native Android debug candidate, versionCode 23. This is not a production or pixel-identical certification.

- Visual references: C:/Fiskentra/Fiskentra-prototype-app/design/fiskentra-full-structure, boards 01–06, 24 panels.
- Source boards: 1448 × 1086; panel dimensions approximately 327–343 × 834–981.
- Device: OPPO CPH2609, 1080 × 2376 pixels, 480 dpi (360 × 792 dp).
- qa/compare_final.py crops Android system bars and scales proportionally to equal panel width. The shorter phone viewport is not stretched.
- Combined full comparisons: qa/compare-final-*.png and qa/contact-final-{core,auth,setup,details}.png. Focused comparisons: qa/focus-final-*.png.
- All four groups were recaptured unobstructed after the earlier pocket protection/PiP/connection failures. Those earlier captures are not approval evidence.
- Core screens use the existing account and 61 real saved moments. GPS is now on and a live position with approximately ±4 m accuracy was visible.
- Account/setup/detail previews are isolated, non-persistent fixtures. Some detail fixtures intentionally show no forecast, no recorded trip or no catch photo, unlike the populated reference samples. Those captures verify empty-state layout, not loaded-state equivalence.
- Native scrolling exposes content below the fold; fixed primary actions remain available on Map tools and Catch editing.

## Fixes and regression checks
1. Rebuilt presentation using a compact four-tab shell, navy/blue palette, condensed typography, thin borders and small radii. Preserved existing local-first services.
2. Corrected the collapsed map, oversized headers/calendar, English dates, source-like navigation, saved-marker visibility and centering.
3. Reproduced a physical-device Map transition freeze. The main thread waited in MapLibreSurfaceView.RenderThread.onWindowResize. Switched to the SDK-supported TextureView renderer. Subsequent map, journal, saved, device and layer-sheet captures succeeded, with live GPS and saved markers visible.
4. Split map controls into separate location, compass and layers buttons plus one grouped zoom pair.
5. Refined Sign In spacing, 58 dp fields/actions, larger small labels and a working in-field password visibility action.
6. Strengthened the welcome heading. Rebuilt the reset-email confirmation icon and composition.
7. Corrected clipped Waiting labels on Flic test cards and sized their action icons explicitly.
8. Prevented repeated taps on unavailable map switches from ever displaying those features as enabled.
9. Made the Flic completion copy conditional on actual connection/test results.
10. Fixed map-tools Done outside the scroll area, catch editor footer, selected-session summaries and sampled image decoding. Removed silent list truncation.
11. Test-mode physical Flic callbacks do not create points while the visible test screen is active; the listener is cleared on pause.

## Fidelity surfaces
- Typography: bundled Barlow Condensed regular/medium/semibold. Full and focused comparisons inspected for header hierarchy, field sizes, wrapping and test-status clipping.
- Layout: source order/grouping preserved across the four main sections and setup/detail routes. Long forms and tool sheets scroll on the shorter device. Some source multi-line cards use more compact native rows.
- Color: navy surfaces, electric-blue actions, slate borders and semantic catch/waypoint/tackle colors. Dynamic disabled/error/connection states are not replaced with source success states.
- Images/icons: supplied Fiskentra wordmark; generated lake/Flic hero assets; official Tabler vector icons. Hero imagery is an interpretation of the board's art direction, not an identical photograph. Licenses are bundled.
- Copy/content: English UI. Missing hourly/depth/offline-area/GPX/social/profile-photo functions explicitly say Coming soon. Email confirmation uses the implemented secure link flow; six-digit code entry is not falsely shown as working.

## Limits and follow-up
- Minor reference differences remain: exact hero photography, some icon silhouettes, brand-blue tint, profile/setup progress styling and compact card spacing. No claim of 1:1 reproduction.
- Loaded forecast, full historical summary and partly completed Flic fixture states still need equivalent-state visual coverage; empty fixtures do not prove them.
- Large Android font scaling, landscape/tablet and long-duration map performance are not validated by this portrait pass.
- TextureView has a performance tradeoff. Field battery/frame-rate testing remains necessary. Official API: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-libre-map-options/texture-mode.html
- Physical field checks were subsequently confirmed by the user on 2026-09-04: single press, double press and hold saved the correct points without missed events offline and with the screen locked; restoring internet synchronized the points without duplicates. Hardware behavior was user-tested, not simulated or independently instrumented by the agent. Long-duration battery/map performance remains unverified.
- No production Supabase changes, external account creation, sign-out, deletion, storage clearing, GitHub commit or push was performed.
- Device screenshots/UI dumps remain local and are ignored by Git; they may contain private account/location data. Reproducible QA scripts remain eligible for version control.

## Build and handoff checks
- Normal Gradle assembleDebug + lintDebug: successful; 0 errors, 36 warnings.
- Debug update install preserves the account, pairing and saved records.
- Debug-only preview activity is excluded from release builds.
- Final normal APK update installed successfully; map/map-tools, welcome, reset-email, Flic completion, active-trip and Flic-test post-fix captures reviewed. The Waiting labels are fully visible.
- APK SHA-256: 564F8405B10A2E4B810C2293E6A43E284219C8C011AEF25B16A3914B79014AB0. Signature verification passed.
- Repeated-toggle smoke test passed twice: Coming soon appeared and Offline map remained off on both attempts.
- Normal tap navigation passed through Saved, Journal, Devices, Forecast / Map and Map; the map actions were present after the final transition.
- MainActivity was restored to Home with qa_keep_awake=false. The user's global charging/keep-awake preference was not changed.

The preceding evidence describes v0.16.1, not acceptance of subsequent changes.

## v0.16.2 Android-first follow-up (2026-09-04)

### Scope and source truth
- User-requested removal: Watch / Wear OS card, action and navigation asset. Bluetooth is now the Devices icon. The source watch row is intentionally excluded from this comparison.
- Reference directory: `C:/Fiskentra/Fiskentra-prototype-app/design/fiskentra-full-structure/`.
- Changed panels: `01-planning-and-forecast-en.png` detailed forecast; `03-journal-saved-devices-en.png` trip summary and Devices; `05-registration-en.png` profile setup and email progress.
- Boards are 1448 × 1086 raster references. Native target is OPPO CPH2609, 1080 × 2376 physical pixels, 360 × 792 dp at 3× density. No CSS runtime is involved. Comparison script removes system bars and normalizes to equal content width without vertical stretching; the native viewport is shorter than the reference panel.
- Filled forecast and trip data are debug-only fixtures. The summary fixture contains five session catches plus one older excluded catch. Preview activity does not access the real account or point store.

### Implementation checks
- Normal `assembleDebug` and `lintDebug` passed: 0 errors, 36 warnings.
- Pure-Java TripStatistics suite: 14 checks passed (session boundaries, four-hour groups, timezone rollover, empty states, deterministic lure grouping and aggregate-label collision).
- APK v0.16.2 / code 24 installed with `adb install -r`; installed version confirmed through Android package metadata. No uninstall or storage clearing.
- Final-layout APK signature scheme v2 verification passed; SHA-256 `40BEB261AB05839105CB92AEF45E01EE1BE8116C4F1CB8660BA55FC5150A7EAF`. The first v0.16.2 comparison build had hash `87D02C345A24DA268428B96924EB5602AF9B6A3618A0D15CAA9838A32B72C345`.
- No Watch / Wear OS references remain under app source or asset-import scripts. Flic capture services were not changed by this follow-up.

### Comparison history and findings
- Initial capture returned `null root node returned by UiTestAutomationBridge`; the wake-and-retry obtained fresh UI trees and screenshots for all four changed routes. The infrastructure blocker is resolved.
- Devices: combined full/focused comparison `qa/compare-final-device.png` and `qa/focus-final-device.png` confirms intentional watch removal and Bluetooth navigation. Real disconnected/local status differs from the board's connected example and is not fabricated to match it. No new blocking visual issue in the removal.
- [P2, resolved] Detailed forecast temperature labels wrapped unevenly, shifting daily scores. Before evidence: `qa/v0162-before-forecast.png`. Fix: single-line, width-aware temperature labels with uniform 19 dp height. Post-fix `qa/compare-final-forecastDetail.png` confirms all five temperature and score rows align.
- [P2, resolved] Trip summary metric captions collided with icons and Peak catches wrapped; extra title and oversized map changed the composition. Before evidence: `qa/v0162-before-summary.png`. Fix: icon/value above single-line caption, date in header, 114 dp map. Post-fix `qa/compare-final-tripSummary.png` and `qa/focus-final-tripSummary.png` confirm readable metrics and corrected map proportions.
- [P2, resolved] Profile unit selectors used tiny filter-chip text. Before evidence: `qa/v0162-before-profile.png`. Fix: 14–18 sp adaptive text in the 42 dp unit control. Post-fix `qa/compare-final-profileSetup.png` and the lower-scroll Imperial-selected capture confirm readable labels and actual preview selection change.
- Full and focused source/native inputs were inspected for all four screens. No actionable P0/P1/P2 finding remains within these reviewed states. The pass is not a source-identical certification of the whole app.

### Fidelity surfaces
- Typography: bundled Barlow Condensed with condensed body/navigation labels; source hierarchy retained. The three readability/density issues above passed post-fix comparison. Section headings are somewhat heavier than the raster source (P3).
- Rhythm: navy panels, thin borders and small radii; body scrolls within a persistent navigation shell on the shorter physical viewport. Watch space is intentionally removed. Summary metric/map proportions corrected.
- Colors: existing blue/navy/slate tokens preserved. Lime/amber/purple indicate recorded point types. Selected unit controls use existing filled-blue treatment (minor stylistic difference from source outline).
- Assets: existing wordmark and official vector icon family reused. Empty photo slots display the app's no-photo state; no fake catch photographs or decorative raster substitutes were added. Live map style and fixture location differ from source terrain.
- Copy/state: English throughout. Five-day weather-derived scores replace unsupported hourly estimates; hourly values, water depth, moon, waves, audio, region/avatar editing and GPX remain explicitly unavailable. Those intentional capability limits were authorized by the user's prior design request. Summary uses actual session aggregation and does not present a predicted Best bite as observed fact.

### Post-fix evidence and acceptance
- Original device screenshots and UI trees: `qa/final-device.*`, `qa/final-forecastDetail.*`, `qa/final-tripSummary.*`, `qa/final-profileSetup.*`. Devices was checked on the first v0.16.2 build; its UI did not change in the follow-up layout fix. The other three were recaptured from the final-layout APK.
- Combined full views: `qa/compare-final-{device,forecastDetail,tripSummary,profileSetup}.png`. Focused header/metrics views: corresponding `qa/focus-final-*.png` files. These are genuine source/native paired inputs, not separate images reviewed from memory.
- Lower-scroll comparisons: `qa/v0162-compare-lower-forecastDetail.png`, `qa/v0162-compare-lower-tripSummary.png`, `qa/v0162-compare-lower-profileSetup.png`; corresponding raw screenshots/UI trees use `qa/v0162-lower-*`.
- Forecast factors, data-source/change-view controls, session charts, edit/share/export controls and profile Continue/Back remain reachable above the system/navigation boundary after scrolling.
- Summary UI accessibility data confirms five in-session catches, histogram `[0,0,0,0,0,5]`, and lure totals `{Wobbler=2, Jig=1, Not recorded=1, Other lures=1}`. The older sixth fixture catch is excluded from summary totals.
- Safe interaction: tapped Imperial in the isolated preview and visually confirmed its selected state. Real account/preferences/points were not modified by fixture checks.
- Final APK update installed successfully. MainActivity restored to Home with `qa_keep_awake=false`; global device settings untouched.

### Completed checklist and residual gaps
- Completed: watch removal; source/native full and focused comparisons; three P2 fixes and post-fix recaptures; lower-scroll checks; preview unit selection; build/lint/signature; 14 pure-Java checks; normal-app restoration.
- Empty aggregation is unit-tested, but empty forecast/summary rendering was not recaptured in this follow-up. Profile name-update/confirmation-email network flows, gallery photos, large font scaling and landscape are not newly certified by this pass.
- Fresh Flic offline/locked-screen testing was not performed by the agent for v0.16.2. Latest hardware evidence remains the user's v0.16.1 confirmation. Flic event code was unchanged in this follow-up.
- P3: heavier headings, exact icon silhouettes/blue tint, and more compact activity cards remain minor visual refinements. Unimplemented features are intentionally labelled, not asserted as functional.

final result: passed
