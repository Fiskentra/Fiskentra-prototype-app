# Fiskentra 0.28.3 — private photo and trip backups

Prepared 7 November 2026 (Europe/Riga). Version **0.28.3**, versionCode **44**.

APK: `artifacts/Fiskentra-0.28.3-full-backup-debug.apk`.

SHA-256: `1984299e6ab68129232f13686e261cea9ac5d65b85ed8ebab51088ce3fbabf66`.

The Android candidate adds versioned account backups containing original saved photo files, saved point details, trip routes and point-to-trip links. It retains the separate point/details sync introduced in [0.28.2](RELEASE_NOTES_0.28.2.md). **The requested implementation, build, update install, live full-backup/history/restore path and live access checks are complete.** The phone's real backup contains three points, no saved photos and no archived trips. Exact photo/route/link recovery into a fresh local destination is covered by isolated fixtures; a physical second-phone or clean-install transfer with real photos/trips is not claimed.

## Using full backups

1. Sign into the same Fiskentra account on the source phone.
2. Open **Saved → SYNC → BACK UP PHOTOS & TRIPS**. The first explicit action enables full backups for that account. Wait for a completed copy; a queued or failed transfer is not a completed backup.
3. After consent, changes can be backed up while Fiskentra's process is running and connectivity is available. This is not an Android WorkManager job guaranteed to run after the process is killed. **PAUSE AUTOMATIC BACKUPS** pauses future work without deleting completed cloud copies.
4. On the destination phone, sign into the same account and choose **RESTORE A FULL BACKUP**, then select a completed version by date/device and counts. The point-details-only actions do not restore photo files or routes.

Full restore verifies the files before importing them. It adds missing records, preserves existing edits and reports conflicts. It never clears local storage to make a restore appear successful. The current UI does not offer automatic conflict resolution or a command to overwrite a newer local edit.

## What is preserved

- Saved points and their portable details, original event times, appearance, favorite state, weather and catch details.
- Photo **bytes as stored by Fiskentra**, without transport recompression, orientation rewriting or format conversion. SHA-256 and length checks cover the complete original and each chunk. This does not recover a gallery original that was never stored by Fiskentra.
- Saved trip start/end metadata, weather and the ordered route samples, timestamps and segment breaks. Portable identities restore point-to-trip links even when the destination allocates different numeric IDs.
- An unfinished/current track is included as a recoverable saved route. Restore records its interrupted/paused state and **does not start GPS recording or a foreground service automatically**.

Login credentials, Android permissions, Flic pairing, callback receipts and device-only paths are not portable content. Photo references are rewritten to verified private files on the destination phone. This is a backup of the listed user content, not a clone of the entire Android installation.

## Transfer and failure behavior

The new path uses a private `fiskentra-backups-v1` Storage bucket and immutable `account_backups` manifests. It does not modify the legacy `saved_points` or the separate `account_points` schema. The application uses its public client configuration plus the signed-in user's bearer; service-role keys and public/signed download links are not used.

- Originals and snapshot JSON are split into content-addressed chunks up to **4 MiB**. Repeated chunks can be reused, while ordered references preserve repeated bytes within a file.
- An object is accepted only after a private readback matches its length and SHA-256. The manifest is committed only after all required objects are present; the client then reads the committed manifest back before acknowledging the backup locally.
- Pending jobs have durable, stable backup IDs and staged content. Lost HTTP responses and process restarts retry the same immutable copy. An acknowledgement for an older copy cannot confirm later local edits.
- A separate durable ownership registry protects point, trip and current-track identities. Changing accounts cannot adopt another account's linked records. Restore reserves portable ownership before import; embedded identities make a crash between local insertion and journal acknowledgement safe to retry.
- Downloaded files and snapshot inventories are verified before local import. Missing/corrupt source photos, incomplete manifests, changed source bytes, disk errors, quota errors and damaged journals stop completion and retain the originals. Partial restore progress is resumable; it is not presented as a complete restore.
- Completed versions remain available in history. **Deleting local content does not erase an earlier full backup.** There is no cloud-backup deletion UI, retention policy or automatic object garbage collection in this candidate. Storage usage can grow; quota failures remain visible and no paid-plan upgrade is performed automatically.

## Limits

| Bound | Limit |
|---|---|
| Chunk/object | 4 MiB |
| Manifest metadata | 1 MiB |
| Snapshot JSON | 64 MiB |
| One original file | 2 GiB |
| Snapshot plus original photo bytes | 64 GiB |

The manifest limit can be reached before the nominal 64 GiB aggregate bound, depending on the number of files/chunk references. Parsing snapshot JSON also needs memory beyond the chunk buffer. A limit failure stops the new backup rather than silently omitting files. Device storage, available memory, network conditions and the project's Storage quota remain practical limits.

## Backend setup and current verification

Setup is documented in [ACCOUNT_BACKUPS_SETUP.md](supabase/ACCOUNT_BACKUPS_SETUP.md); the additive SQL is [account_backups_proposed.sql](supabase/account_backups_proposed.sql). At this check its SHA-256 is `4be01911e4413d83387a7f79d5d834a2341fc843789117b12e740a5d52e0b4ec`.

The initial missing bucket was confirmed by a read-only user SQL result and correctly prevented completion. After the user created the correctly named private bucket, the same pending copy survived process relaunch and the APK update, resumed and published. The app then listed completed history and restored that version successfully. The point ledger and legacy point queue remained unchanged, there were no FATAL errors and no pending backup job remained. Automatic backup is enabled for the current account; account and backup state persist after an actual process restart.

The user ran the complete live role smoke with ROLLBACK: **32/32 PASS**. Under non-superuser/non-bypass A/B/anonymous roles it verified the existing private bucket and manifest inventory, A's commit and idempotent replay, immutable identity conflicts, prevention of incomplete/direct cross-owner inserts, UPDATE/DELETE denial, B isolation and anonymous denial. It created no Auth users or Storage rows; two temporary backup rows were rolled back, the original backup and Storage metadata row count were unchanged. B's positive own-CRUD and SQL-side JWT signature validation are outside that smoke; real authenticated Storage/PostgREST traffic is covered separately by the app's upload/history/restore. Independent anonymous HTTP checks also denied manifest access (401), the public-object route (400) and the anonymous private-object route (400).

| Check | Current result |
|---|---|
| Debug assembly | PASS; version 0.28.3 / code 44 |
| Debug and release Lint | **0 errors / 0 warnings** each |
| Core runner | Final **28/28 suites PASS** |
| Durable backup jobs, ownership and crash boundaries | **84 checks PASS** |
| Original bytes, chunking, corruption and file reconstruction | **59 checks PASS** |
| Exact local capture/import with isolated fresh destination | **77 checks PASS**, including photo/route/link fixtures; not a physical second phone |
| Production backup HTTP transport with intercepted HTTPS | **543 checks PASS**; no real credentials/network |
| Additive SQL in isolated PGlite | **61 checks PASS**, plus **10 live-smoke fixture cases PASS**; separate from live Storage verification |
| Update install on Galaxy A33 / API 36 | PASS: all **26/26 original private files identical immediately after upgrade** |
| Missing bucket and interrupted job recovery | PASS: not falsely completed while the bucket was absent; same staged pending copy resumed after bucket correction, process restart and APK update |
| Real full upload → history → restore | PASS: **3 points / 0 photos / 0 trips** for the current real dataset; no pending jobs, one complete restore journal, unchanged point-ledger/legacy-queue SHA, FATAL 0 |
| Account and backup state after actual relaunch | PASS; automatic backup remains enabled for the current account |
| Live backup A/B/anonymous database-role checks | **32/32 PASS with ROLLBACK**, zero Auth/Storage writes; two temporary backup rows rolled back |
| Independent anonymous manifest/public/private object HTTP | PASS: all denied, statuses **401 / 400 / 400** respectively |
| Physical clean-install / second-device recovery | **NOT RUN** |

Evidence: `artifacts/Fiskentra-0.28.3-build.json`, `build/recovery-checks/results.json`, the focused suite logs and `build/account-backups-sql-tests/report.json`. Private live evidence is retained locally under `../../.recovery/20261106T232725Z-full-backup/`, including `live-full-backup-device-result.json`, `live-backup-anonymous-http.json`, `live-backups-role-user-result.json` and screenshots. Keys, tokens, owner IDs and private record contents are not included in these release notes. Debug-only failure diagnostics record controlled phase/status/code fields, not credentials or payloads.

## Remaining acceptance

The current full-backup implementation and live gates have passed within the scopes above. Validate a physical second device with real original photos and recorded trips separately; the fresh-local fixture is not a physical-device test. Large histories, quota/disk failures on real phones, interrupted recording, prolonged background operation and local edit conflicts remain wider acceptance work. Physical Flic and navigation field checks belong to the earlier beta plan and are separate from this completed request. The Google-map/offline-area and safe marine-routing limitations from earlier releases are unchanged.
