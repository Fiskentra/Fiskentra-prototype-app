# Fiskentra Project Status

Last updated: 2026-08-16

## Current stage

- Version: `v0.4.1`
- Stage: Internal Prototype / Pre-Alpha
- Launch readiness: Not ready for public users
- Selected field button: Flic 2 Single Pack
- Product: https://flic.io/shop/flic-2-single-pack
- Retired hardware plan: BlueUP SafeX Lite

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
| `v0.4.1` | Record Flic 2 as the selected hardware and update the prototype | Current |
| `v0.5` | Integrate `flic2lib-android` and test the physical Flic 2 | Next |
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

## Flic integration boundary

The existing `FiskentraBleManager` is a generic BLE diagnostic utility. It must not be used to infer Flic 2 clicks from arbitrary GATT notifications. Production button actions will come from official `flic2lib-android` callbacks.
