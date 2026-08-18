# Fiskentra Project Status

Last updated: 2026-08-18

## Current stage

- Version: `v0.5.2`
- Stage: Internal Prototype / Pre-Alpha
- Launch readiness: Not ready for public users
- Selected field button: Flic 2 Single Pack
- Product: https://flic.io/shop/flic-2-single-pack
- Retired hardware plan: BlueUP SafeX Lite
- Build status: Debug APK compiles successfully
- Physical Flic 2 validation: Ready for user test
- Physical Flic 2 callbacks: Confirmed working by user on 2026-08-18
- Map marker hotfix: Saved points now render above the live GPS dot and overlapping points spread visibly
- Map marker follow-up: The live GPS position is reserved, rendered last and remains visible inside overlapping saved-point clusters

## Selected hardware

| Property | Flic 2 |
|---|---|
| Triggers | Push, double push, hold |
| Connection | Bluetooth 5 LE, direct Android support |
| Battery | Replaceable CR2032, advertised up to 3 years |
| Advertised indoor range | Up to 50 m |
| Water protection | IP44 splashproof; not waterproof |
| Android integration | Official `flic2lib-android` SDK |

## Button mapping

| Flic action | Fiskentra action | Saved point type |
|---|---|---|
| Single press | Register a catch | `Catch` |
| Double press | Save a waypoint | `Waypoint` |
| Hold | Record a tackle change | `Tackle change` |

## Roadmap to launch

| Version | Goal | Status |
|---|---|---|
| `v0.4.1` | Record Flic 2 as the selected hardware and update the prototype | Complete |
| `v0.5` | Integrate `flic2lib-android` and test the physical Flic 2 | Current · SDK complete, physical test pending |
| `v0.6` | Support reliable button events with the app backgrounded or phone locked | Planned |
| `v0.7` | Add fishing day log and calendar | Planned |
| `v0.8` | Add weather to saved points and trips | Planned |
| `v0.9` | Add catch details | Planned |
| `v0.10` | Polish offline storage and sync recovery | Planned |
| `v0.11` | Improve fishing maps and layers | Planned |
| `v0.12` | Add user profiles and authentication | Planned |
| `v0.13` | Add settings | Planned |
| `v0.14` | Add onboarding | Planned |
| `v0.15` | Closed beta | Planned |
| `v1.0` | Official public launch | Planned |

## v0.5 implementation

- Official `flic2lib-android` 2.0.1 dependency and JitPack repository added.
- The SDK manager initializes at application startup.
- Previously paired Fiskentra buttons reconnect when the foreground app starts.
- The Device screen pairs a new Flic 2 through the official SDK.
- Single, double and hold callbacks map to `Catch`, `Waypoint` and `Tackle change`.
- Queued events older than 15 seconds are ignored, preventing an old press from saving a point with a fresh GPS position.
- The existing `FiskentraBleManager` remains dormant source for diagnostics only. It is not used by the app UI and cannot create points.

## Physical test gate

1. Install the v0.5 debug APK on the Android phone and grant Location and Nearby devices.
2. Open Device, tap `PAIR FLIC 2`, then hold the physical button for 6 seconds until it glows.
3. Accept Android's `Pair & connect` dialog.
4. With a current GPS fix, test single press, double press and hold and confirm the three expected saved point types.
5. Walk out of range and return, then confirm the button reconnects without creating a stale point.

Background and locked-phone reliability is intentionally the separate v0.6 foreground-service phase.
