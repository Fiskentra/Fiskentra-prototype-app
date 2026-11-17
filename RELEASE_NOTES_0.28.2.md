# Fiskentra 0.28.2 — account point sync and wind instrument

Prepared 7 November 2026 (Europe/Riga). Version **0.28.2**, versionCode **43**.

Current APK: `artifacts/Fiskentra-0.28.2-cloud-wind-debug.apk`.

SHA-256: `9161a90180c56f1091bd8ee0b0a815cc466fbb5d6748868e4b95409ea98ac594`.

This Android candidate has passed real account **Upload → Restore** on Galaxy A33 / API 36: three points restored with zero conflicts. The reviewed SQL was applied manually by the user, live catalog/grant checks and anonymous REST denial passed, and the user-run live transaction smoke passed **22/22 conditions**. The current point-sync repair gates are complete. Clean-install/second-device recovery, physical Flic sessions and wider field acceptance remain open; this is not yet an accepted external beta.

## Wind on the active Google map

- Wind is a fixed north-up instrument below the compass, rather than a symbol pinned to a geographic coordinate. It shows speed, incoming direction, observation time/age and current/saved/offline state. Its arrow points downwind.
- The selected point chooses the sample; otherwise the app uses a fresh GPS location or the map centre. This is one Open-Meteo observation, not an interpolated regional wind field.
- The instrument uses measured height and the available map viewport. It hides when a sheet, keyboard or bottom navigation leaves insufficient room; the layer details remain available. Compass, connection information and map controls reserve separate space.
- Existing depth contours, sea marks, local catch heatmap and past-trip layers remain available. Their coverage and boat-route limitations in [0.28](RELEASE_NOTES_0.28.md) still apply.

## Account-scoped points and details

The Android client now provides explicit **Upload points** and **Restore points** actions for a signed-in account. Signing in alone does not adopt pre-existing local records. After an account is explicitly connected, its queued changes can retry in the background while that account is signed in.

The portable payload contains point coordinates when present, original event time, notes, appearance, favorite status, weather and catch details. **Photo files, private device photo paths, trip geometry and trip associations remain local.** This is not a complete device backup, photo backup or trip restore.

- Requests use the user's bearer token and the account endpoint; the client checks the session owner, configured project, HTTPS and redirect policy. Restore uses ordered UUID keyset pages.
- A separate durable `account-sync-v1.json` journal retains account ownership, operation IDs and server versions. Switching accounts cannot adopt another account's linked points or send its pending work under the new account.
- Local revisions are checked atomically during restore. Local edits, tombstones and partial imports survive retries and restarts; an older response cannot confirm a newer local edit. A damaged cloud journal disables cloud work while local records remain available.
- Account deletion keeps an identity/version tombstone and sends an empty personal-data payload. Deletion confirmation distinguishes account-linked, never-uploaded, legacy and damaged-journal cases. A deletion belonging to another account waits for its original account and network access.
- Two-device version conflicts preserve local content and pending work. There is **no conflict-resolution UI yet**; a conflict must not be presented as fully synced or resolved by silently overwriting either side.
- The old anonymous device-header transport remains blocked. Existing points, legacy receipts, the old queue and earlier server copies are not automatically migrated, sent, completed or deleted by this new path.

The reviewed additive SQL is [account_points_proposed.sql](supabase/account_points_proposed.sql). Its SHA-256 is `77cb1a823f5a6cf4d476d76b50b52c82abd4c67dd8329f1caaad22fe934f2f6f`. It defines owner-scoped RLS and an optimistic, idempotent RPC while leaving legacy tables untouched. The user applied the complete SQL through the SQL Editor. The supplied live catalog result confirms RLS enabled, three policies, authenticated SELECT/INSERT/UPDATE, no hard DELETE, no anonymous table/RPC access and a SECURITY INVOKER RPC. Independently, anonymous REST reading returned HTTP **401 / SQLSTATE 42501**, and authenticated USB Upload → Restore passed for three points.

The user then executed the complete live transaction smoke with **ROLLBACK: 22/22 conditions passed**. It checked a NOSUPERUSER/NOBYPASSRLS role, A's own CRUD, B's denied cross-owner SELECT/UPDATE/RPC, denied owner reassignment, denied anonymous table/RPC access, CAS and response-loss idempotency, rejected stale edits/resurrection, empty tombstone payload with its CHECK, and denied hard deletion. It created no Auth users and left no transaction fixtures behind. A second existing Auth user was available, but **B's positive own-CRUD path and JWT signature validation were not tested by this SQL smoke**. This is live database-role/RLS evidence, not a fresh install or a second-device client test.

## Android and Lint

The candidate uses compile/target SDK **37**, minimum SDK **26**, Java source/target **17**, Gradle **9.5.0** and AGP **9.3.1**. Portable scripts use the installed JDK/SDK environment or the workspace `.tools` installation. MapLibre is **13.7.1**; Google Maps remains the active provider.

The original 117 Lint warnings were addressed through resource formatting/plurals, reusable drawing objects, application-owned services, accessible click handling, current dependencies/SDK, adaptive launcher assets and removal of unused resources. System Back uses the AndroidX dispatcher. The notification has a separate monochrome Fiskentra icon; the supplied launcher mark is preserved.

Programmatically created panels requiring a host and callbacks retain narrowly scoped, commented `ViewConstructor` annotations because XML inflation is unsupported. There is no blanket Lint disable or baseline masking the result. License notices are retained, including the Material Icons notice as an APK asset.

## Verification of this candidate

| Check | Result |
|---|---|
| Debug APK assembly | PASS; artifact and version recorded above |
| Debug Lint | **0 errors, 0 warnings** |
| Release Lint | **0 errors, 0 warnings**; this does not claim a production-signed release artifact |
| Standalone core runner | **24 suites PASS** |
| Actual account HTTP transport with fake HTTPS/session boundaries | **194 checks PASS**; no real network or credential files |
| Durable cloud ownership, replay, restore and failure cases | **48 checks PASS** |
| Reviewed SQL in isolated PGlite, using the SQL hash above | **53 checks PASS**; not a live Supabase test |
| Install/update on Galaxy A33 / API 36 | PASS: installed version 0.28.2 / code 43; all 24 private files identical immediately after installation |
| USB UI smoke | PASS: Map, wind below compass/details and Saved with three moments open; Back closes Layers, Home → Back returns to the launcher, and the app reopens. Zero FATAL errors observed; point/history files unchanged after smoke |
| Live SQL application | Applied manually by the user through SQL Editor; reported success |
| Live catalog and grants | PASS: RLS enabled, three policies, authenticated SELECT/INSERT/UPDATE, no hard DELETE, no anonymous table/RPC access, SECURITY INVOKER RPC |
| Anonymous account-table REST read | PASS: denied with HTTP 401 / SQLSTATE 42501 |
| Real authenticated Upload → Restore on Galaxy A33 | PASS: three restored, zero conflicts; three synced journal entries, versions `[1,1,1]`, zero jobs, all acknowledged payloads match; local point-ledger SHA unchanged and FATAL count zero |
| Process relaunch | PASS: account persists, Saved retains three moments, account summary and all three point statuses remain synced after an actual process restart |
| Live database-role/RLS and sync-protocol transaction smoke | **22/22 PASS**, user-run complete file with ROLLBACK; zero Auth users created. A own-CRUD and B/anon negative access checked; B positive own-CRUD and JWT signature validation excluded |
| Clean-install and second-device recovery | NOT RUN |

Local runner evidence: `build/recovery-checks/results.json` and per-suite logs. APK metadata: `artifacts/Fiskentra-0.28.2-build.json`. Private live/device evidence is retained locally under `../../.recovery/20261106T221807Z/`, including `live-rights-user-result.json`, `live-device-cloud-result.json`, `live-transaction-smoke-user-result.json` and `phone-cloud-restored.png`; these files are not published as release contents. Credentials and private record contents are not included in these notes.

## Remaining acceptance

Test clean-install/second-device recovery and B's positive own-CRUD path separately. The real point/details path and scoped live database-role smoke have passed; photos/tracks are still local and there is no conflict-resolution UI. Extend the USB smoke check to the remaining layouts and permission/service scenarios. Long Flic sessions, screen-off recording, real motion/boat navigation, large-font/TalkBack checks and multi-device conflicts remain separate acceptance work in [BETA_ROADMAP.md](BETA_ROADMAP.md). Google base maps still require connectivity; no downloadable Google map packs or verified safe marine routing are promised.
