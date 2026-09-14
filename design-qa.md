# Navigation design QA — v0.24

Source visual truth: `qa/map-v024/reference.png`, copied from `C:/Users/Gebruiker/Downloads/Codex Image Sep 14, 2026, 01_48_56 AM.png`.

Implementation first capture: `qa/map-v024/driving-first.png` (actually Walking, the user's saved profile). Both images were opened together in the same tool comparison input.

Viewport: source 845×1860, device 1080×2376 at Android density 3 (360 dp wide). Compare app content after excluding native status/navigation bars; source proportions are normalized to the 360 dp content width (845 px → 360 dp). The device has larger system font scaling. This is a native Android screen, not a browser or a scaffolded web prototype.

State: active route, satellite layer. Real device geography, right turn, connected Bluetooth and actual trip data differ from the illustrative left-turn/parking mock. Existing Fiskentra symbol + wordmark and weather access are intentionally retained from the user's prior instructions. Parking and a verified final walking leg are not invented for an arbitrary route endpoint.

## Comparison history

First comparison:
- [P2] Guidance panel too wide/left aligned relative to source. Fix: cap its width at 224 dp, align it next to the compass, and allow smaller screens to constrain it.
- [P2] Maneuver glyph thinner than target. Fix: weight the original Material Icons path with a 1-unit round stroke; do not replace it with a font arrow.
- [P2] Unnamed path repeated `Turn right` beneath `Turn right`. Fix: use actual provider street names or explicit `Unnamed path` / `Unnamed road`.
- Compact mode row, outlined/filled travel modes, time/distance row and blue/navy colors are present. Steps opens the existing real maneuver list. Walking/Driving switching verified.

## Required fidelity surfaces

- Typography: system sans-serif, bold 18 sp maneuver and 20 sp route total, quieter 11/12 sp distance and street. Long streets may wrap over two lines. System font settings retained.
- Spacing: compact two-row lower panel, divider, separate maneuver panel next to compass, unchanged bottom app navigation; map receives the freed space.
- Colors: navy #001e2e, cyan #00a7ff, active blue #0059e8, muted #b7c9e6; matches the reference palette.
- Assets: live MapLibre satellite map and original Fiskentra brand asset; official Material Icons converted mechanically into Android vectors. Existing app icons reused for Steps and audio. No raster map or fabricated POIs.
- Content: live distance, ETA, maneuver, street and route endpoint gap. No live-traffic claim. Voice is opt-in, engine-dependent and foreground only.

Post-fix comparison: `qa/map-v024/driving-refined.png` opened together with the original reference in the same image comparison input. Active Driving now displays the real street on its own line, the heavier direction arrow and narrower guidance card. The source's left turn and shorter sample journey differ intentionally from the live right turn / 24.7 km route. No remaining actionable P0/P1/P2 visual findings in the requested navigation controls.

Focused regions: the guidance and footer were checked in the readable full-resolution pair (distance/action/street hierarchy, arrow weight, divider, selected modes, audio icon and ETA all legible); no additional cropped artifact was needed.

Interactions: Walking route displayed; Driving selected and real Driving Steps opened; voice toggle entered enabled state with the available engine, then muted. Route restoration after update verified. Foreground-only speech is additionally enforced by the activity lifecycle. Actual audible instructions and road travel remain physical test gaps.

P3/accepted differences: accessibility font sizing makes the guidance card slightly wider and route summary taller than the mock; real endpoint-gap text needs a second line. Native compass follows actual heading; existing clustered markers reflect the user's point density. Original brand lockup/weather controls deliberately retained. Final images keep native system bars, which are excluded from content comparison.

Implementation checklist: all three initial visual findings fixed and compared again; final build/lint and 63 relevant JVM checks pass. All seven `qa/Test-WeatherSwipe.ps1` checks pass with the new navigation UI. Final APK installed successfully on OPPO CPH2609 without resetting app data.

final result: passed
