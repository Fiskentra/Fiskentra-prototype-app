# Active map development

The user selected the v0.20 working version as the base for Fiskentra map development on 2026-09-07.

Continue map work in **map-v021/**, branch **codex/field-map-v021**. The original top-level checkout used the v0.5.2 baseline.

As of 7 November 2026, the active candidate is **0.28.3 / versionCode 44**, using Google Maps, a wind instrument below the compass, separate point/details sync and private immutable full backups of original stored photos, routes and point links. Explicitly enable full backup; restore a completed version under the same account. Unfinished tracks restore as saved routes without starting recording. Build/Lint, final 28/28 core suites and focused isolated tests pass. **Real full upload → history → restore, live 32/32 role smoke with ROLLBACK and anonymous HTTP denial pass.** All 26 private files matched immediately after upgrade; the same pending copy survived bucket correction, process relaunch and APK update. Final jobs are zero, the restore journal is complete, local point/legacy-queue hashes are unchanged and FATAL is zero. Account/backup state persist after relaunch. Real-device content is three points with no photos/trips; exact photo/route/link recovery is tested with fresh-local fixtures, not a physical second phone or clean installation. Local conflicts/foreign owners are protected; past copies survive local deletion. Backup deletion/retention and conflict-resolution UI remain absent, and process lifetime/size/quota bounds remain. Wider beta/Flic/field acceptance is separate. Walking/Driving and explicit offline-area downloads remain unavailable in the active flow. New development follows the product split and closed-beta plan below.

- [Free and Premium](https://github.com/Fiskentra/Fiskentra-prototype-app/blob/codex/field-map-v021/PRODUCT_TIERS.md)
- [Closed-beta work plan and acceptance](https://github.com/Fiskentra/Fiskentra-prototype-app/blob/codex/field-map-v021/BETA_ROADMAP.md)
- [Current release and active map](https://github.com/Fiskentra/Fiskentra-prototype-app/blob/codex/field-map-v021/README.md)
- [0.28.3 original-photo/trip backups and verification](https://github.com/Fiskentra/Fiskentra-prototype-app/blob/codex/field-map-v021/RELEASE_NOTES_0.28.3.md)
- [0.28.2 account sync and wind history](https://github.com/Fiskentra/Fiskentra-prototype-app/blob/codex/field-map-v021/RELEASE_NOTES_0.28.2.md)
- [0.28.1 recovery history](https://github.com/Fiskentra/Fiskentra-prototype-app/blob/codex/field-map-v021/RELEASE_NOTES_0.28.1.md)
- [0.28 layers and validation](https://github.com/Fiskentra/Fiskentra-prototype-app/blob/codex/field-map-v021/RELEASE_NOTES_0.28.md)
- [0.27 Google Maps migration](https://github.com/Fiskentra/Fiskentra-prototype-app/blob/codex/field-map-v021/RELEASE_NOTES_0.27.md)
- Current APK: `map-v021/artifacts/Fiskentra-0.28.3-full-backup-debug.apk`

The earlier navigation design, road routing and MapLibre offline reports in this directory preserve development history. Their checks and APKs do not establish readiness of the current Google-based beta.

The existing v0.20 worktree at `C:/Fiskentra/Fiskentra-v0.8-worktree` was read and preserved; its uncommitted v0.20 GPX work was copied into the new branch's baseline commit `c1fcd63`.
