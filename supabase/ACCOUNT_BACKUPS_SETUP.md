# Private immutable account backups

The Android backup transport uses the existing account's user JWT and public client key. No service-role key belongs in the application. The existing `account_points` and legacy point tables are separate and are not modified by this setup.

## Server setup

1. In the verified Fiskentra project's Storage Dashboard, create **fiskentra-backups-v1** as a **private** bucket. Set its maximum object size to **4194304 bytes / 4 MiB** and allow **application/octet-stream**. Buckets and files are managed through the [Storage API/Dashboard](https://supabase.com/docs/guides/storage/schema/design); SQL below only reads their metadata and creates policies.
2. Review and apply [account_backups_proposed.sql](account_backups_proposed.sql). It creates `account_backups`, its validation/commit functions and bucket-specific policies. It is additive and intended to run once after checking that those names do not already exist.
3. Verify the bucket remains private and test account A, account B and anonymous access. The SQL uses restrictive guards so pre-existing broad permissive policies cannot grant access to the protected backup bucket or permit overwriting/removing its objects. Permissions on unrelated buckets are preserved.

Private downloads use the user's authorization header and [Storage RLS](https://supabase.com/docs/guides/storage/serving/downloads). The transport does not create public or signed download links.

## Protocol and limits

- Original photo bytes and snapshot JSON are split into chunks up to **4 MiB**, addressed by SHA-256 at `<owner UUID>/<chunk SHA-256>`. There is no image conversion or quality reduction in the transport.
- Standard Storage upload uses POST with `x-upsert: false`. [Supabase may report an existing path as HTTP 400 or 409](https://supabase.com/docs/guides/storage/uploads/standard-uploads). Both a new upload and an existing path are accepted only after a complete private download matches the expected length and SHA-256.
- Restore downloads into a staged file, verifies its length and SHA-256, synchronizes the file, and publishes it without replacing a different existing local file. Account changes and redirects stop the transfer.
- A manifest is published only after all referenced objects exist with the expected metadata. Its snapshot and photo descriptors have complete ordered chunk lists, exact byte sums and a distinct required blob inventory. Original files can be up to **2 GiB** each; the manifest is limited to **1 MiB**, and total original-file bytes to **64 GiB**. Exceeding a limit fails the new backup instead of silently omitting data.
- Backups are immutable. Stable backup IDs make response-loss retries idempotent; a different manifest cannot replace the same backup ID. There is no automatic deletion, retention pruning or object garbage collection in this protocol.
- The validation function and commit RPC are SECURITY INVOKER with an empty search path. The INSERT policy invokes the complete validator, so direct inserts cannot publish an incomplete manifest by bypassing the RPC. Storage metadata proves existence/size; the client's readback supplies the byte-hash check.

## Local verification

`scripts/Test-AccountBackupTransport.ps1` exercises the real production transport with intercepted HTTPS, fixture sessions and temporary binary files. It never loads real credentials or opens a socket.

`tests/AccountBackupsSqlTest.mjs` runs the unchanged SQL against the workspace's existing isolated PGlite runtime. It uses minimal Auth/Storage metadata fixtures with non-bypass A/B/anonymous roles, deliberately broad pre-existing Storage policies, malformed manifests and rollback probes. Its report is written to `build/account-backups-sql-tests/report.json`. It does not emulate JWT signatures, real object storage or concurrent database backends.
