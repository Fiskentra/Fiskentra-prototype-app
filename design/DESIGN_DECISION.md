# Fiskentra design decision

## Design context

- Objective: unite fishing forecast, offline field navigation and catch memory in one Android product.
- Primary audience: anglers who plan at home, operate one-handed outdoors and review results later; secondary audience includes hunters, hikers, mushroom pickers and tourists.
- Primary communication job: answer “What should I do next?” within one glance.
- Core tension: anglers want rich data, but outdoors they have wet hands, glare, weak connectivity and very little attention.
- Product advantage: Fiskentra can turn a physical Flic press into a geo-tagged field event, then connect that event to forecast, map and journal context.
- Desired action: glance, decide, capture — without stopping the activity.

## Shared information architecture

1. **Today** — location readiness, bite window, weather hazards, active trip, Flic state and three capture actions.
2. **Forecast** — 3-hour / 5-day weather, species forecast, factor explanation, moon, geomagnetic and wave data, provider selection and barometer correction.
3. **Map** — online/offline map layers, depth, track recording, saved points, accuracy radius, route/voice guidance, ruler, manual coordinates and GPX exchange.
4. **Journal** — calendar, catch details, tackle, photos, audio notes, trip history, statistics, sync, sharing, news and offline library.
5. **More** — map-pack management, cloud backup, Wear OS, Flic pairing/mapping, units, permissions and safety.

## Ten directions

| # | Direction | Structural idea | Best use |
|---|---|---|---|
| 01 | Expedition Command | Dense status-first command center | Current Android MVP evolution |
| 02 | Quiet Water | Calm progressive disclosure | Beginners and leisure anglers |
| 03 | Chartplotter | Map is the operating system | Boat and sonar-adjacent use |
| 04 | One-Hand Signal | Capture control dominates every screen | Flic-led field operation |
| 05 | Field Notebook | Chronology and memory lead | Catch diary and trip storytelling |
| 06 | Weather Window | Time and conditions lead | Forecast-heavy planning |
| 07 | Tactical Grid | Maximum density and contrast | Expert expeditions and harsh weather |
| 08 | Sonar Glass | Depth and water data lead | Lake, reservoir and boat fishing |
| 09 | Camp Circle | Trips become shareable stories | Clubs, families and guides |
| 10 | Adaptive Compass | UI changes between Plan / Field / Review | Long-term product direction |

## Recommendation

Use **10 Adaptive Compass** as the product architecture. It is the only direction that resolves the fundamental conflict between data-rich planning and low-attention field use without hiding capability. Borrow:

- high-contrast state language and compact cards from 01;
- full-screen map behavior from 03;
- the persistent capture dock from 04;
- journal chronology from 05;
- factor transparency from 06;
- depth visualization from 08.

For a practical v0.6 implementation, start with the 01 visual system and add the 04 capture dock. Introduce the Plan / Field / Review mode switch only after forecast, offline maps and journal data exist.

## Functional acceptance matrix

The four representative screens in every concept deliberately cover the following:

- GPS and accuracy state; current coordinates; save current position.
- Single press = Catch; double press = Waypoint; hold = Tackle change.
- Start/stop trip, background recording target and visible track state.
- Typed saved points, selected-point focus, point edit/delete and cloud status.
- 3-hour and 5-day forecast; three-provider selection; device barometer correction.
- Bite score per species with weather/astronomy factor explanation.
- Moon, geomagnetic activity and wave height/period/length.
- Online and offline map layers, depths, historical/topographic/custom formats and opacity.
- Photos, audio comments, names and descriptions for points/tracks.
- Voice guidance to a point, straight-line bearing, remaining distance and accuracy radius.
- GPX/KML import/export, sharing, manual coordinates and distance ruler.
- Calendar, catch statistics, notes, fishing news and offline fish/tackle library.
- Local-first operation, cloud backup/sync recovery, Wear OS and Flic device management.

