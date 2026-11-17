import com.fiskentra.app.backend.AccountBackupLedger;
import com.fiskentra.app.data.PointLedger;
import org.json.JSONObject;

import java.io.IOException;
import java.util.UUID;

/** Real JSON/disk-boundary tests for full-backup consent, immutable retries and ownership recovery. */
public final class AccountBackupLedgerTest {
    private static final String A = "11111111-1111-4111-8111-111111111111";
    private static final String B = "22222222-2222-4222-8222-222222222222";
    private static final String H1 = "1".repeat(64), H2 = "2".repeat(64), H3 = "3".repeat(64);
    private static int checks;
    private static void check(boolean result, String label) { checks++; if (!result) throw new AssertionError(label); }
    private static void expectFailure(Runnable action, String label) {
        try { action.run(); throw new AssertionError(label); }
        catch (IllegalArgumentException | IllegalStateException expected) { check(true, label); }
    }
    private static final class Disk implements PointLedger.Disk {
        String text; boolean failRead, failWrite; int writes;
        public String read() throws IOException { if (failRead) throw new IOException("simulated read failure"); return text; }
        public void write(String value) throws IOException {
            if (failWrite) { failWrite = false; throw new IOException("simulated atomic write failure"); }
            text = value; writes++;
        }
    }
    public static void main(String[] args) throws Exception {
        consentAndImmutableJobs(); ownershipAndRestoreRecovery(); diskFailureBoundaries(); corruptionFailsClosed();
        System.out.println("AccountBackupLedger: " + checks + " checks PASS");
    }

    private static void consentAndImmutableJobs() {
        Disk disk = new Disk(); AccountBackupLedger ledger = new AccountBackupLedger(disk);
        check(ledger.available() && !ledger.enabled(A) && !ledger.enabled(B), "a fresh account never opts into full backup merely by signing in");
        String actor = ledger.actorId(); UUID.fromString(actor);
        check(ledger.claim(A, H1, "capture/one/manifest.json", 0) == null && disk.writes == 1, "unconsented capture cannot create work");
        ledger.enable(A);
        check(ledger.enabled(A) && !ledger.enabled(B) && ledger.needsBackup(A, H1), "full backup consent is per account");
        AccountBackupLedger.Job first = ledger.claim(A, H1, "capture/one/manifest.json", 100);
        check(first != null && first.owner.equals(A) && first.actorId.equals(actor) && first.attempts == 1, "job identity is durable before the first HTTP request");
        check(ledger.pendingCount(A) == 1 && ledger.pendingCount(B) == 0, "another account cannot see or claim A's pending work");
        ledger.disable(A);
        check(ledger.claim(A, H1, "ignored", 1000) == null && ledger.pending(A).backupId.equals(first.backupId), "disabling pauses, preserving staged work");
        ledger.enable(A);
        AccountBackupLedger.Job newerCapture = ledger.claim(A, H2, "capture/two/manifest.json", 200);
        check(newerCapture.backupId.equals(first.backupId) && newerCapture.fingerprint.equals(H1)
                && newerCapture.snapshotPath.equals(first.snapshotPath), "new local edits cannot replace an immutable response-loss retry");
        ledger = new AccountBackupLedger(disk);
        AccountBackupLedger.Job replay = ledger.claim(A, H2, "capture/two/manifest.json", 300);
        check(ledger.actorId().equals(actor) && replay.backupId.equals(first.backupId) && replay.fingerprint.equals(H1), "restart replays the same backup and actor identity");
        ledger.finish(replay, true, "", 400);
        check(ledger.pending(A) == null && ledger.lastCompletedId(A).equals(first.backupId) && ledger.lastCompletedAt(A) == 400,
                "successful response acknowledges exactly its immutable snapshot");
        check(!ledger.needsBackup(A, H1) && ledger.needsBackup(A, H2), "an old acknowledgement cannot mark edits made during upload as backed up");
        check(ledger.claim(A, H1, "not-needed", 500) == null, "unchanged data does not create duplicate backups");
        AccountBackupLedger.Job second = ledger.claim(A, H2, "capture/two/manifest.json", 500);
        check(!second.backupId.equals(first.backupId), "later content gets a distinct immutable backup ID");
        ledger.finish(first, true, "", 600);
        check(ledger.pending(A).backupId.equals(second.backupId) && ledger.lastFingerprint(A).equals(H1), "a stale reply cannot complete a newer pending backup");
        ledger.enable(B); AccountBackupLedger.Job other = ledger.claim(B, H3, "capture/B/manifest.json", 500);
        ledger.finish(second, false, "failed", 1000);
        check(ledger.pending(B).backupId.equals(other.backupId), "A's failure cannot mutate B's independent job");
        long retryAt = ledger.pending(A).retryAt;
        check(retryAt > 1000 && ledger.claim(A, H3, "ignored", retryAt - 1) == null, "network failures keep the snapshot under bounded retry delay");
        AccountBackupLedger.Job retry = ledger.claim(A, H3, "ignored", retryAt);
        check(retry.backupId.equals(second.backupId) && retry.fingerprint.equals(H2), "retry uses the original bytes even when current data changed again");
        ledger.finish(retry, false, "auth", retryAt);
        check(ledger.claim(A, H3, "ignored", Long.MAX_VALUE) == null && ledger.status(A).equals("auth"), "expired account access does not spin HTTP retries");
        ledger.retry(A); AccountBackupLedger.Job authRetry = ledger.claim(A, H3, "ignored", 0);
        check(authRetry.backupId.equals(second.backupId), "explicit retry after sign-in preserves the operation ID");
        ledger.finish(authRetry, false, "conflict", 0); ledger.retry(A);
        check(ledger.claim(A, H3, "ignored", Long.MAX_VALUE) == null && ledger.status(A).equals("conflict"), "an immutable server-content conflict is never silently overwritten");
        ledger.finish(other, false, "permanent", 0);
        check(ledger.claim(B, H3, "ignored", Long.MAX_VALUE) == null, "permanent configuration failure stops background retries");
        ledger.retry(B); AccountBackupLedger.Job fixed = ledger.claim(B, H3, "ignored", 0);
        ledger.finish(fixed, true, "", 100);
        check(!ledger.needsBackup(B, H3) && ledger.status(B).equals("complete"), "manual retry can complete after configuration is repaired");
    }

    private static void ownershipAndRestoreRecovery() {
        Disk disk = new Disk(); AccountBackupLedger ledger = new AccountBackupLedger(disk);
        String point = UUID.randomUUID().toString(), trip = UUID.randomUUID().toString(), track = UUID.randomUUID().toString();
        check(ledger.identity(A, "point", 10, point).equals(point), "existing point sync UUID is preserved in full backup");
        check(ledger.identity(A, "point", 10, "").equals(point), "repeat capture preserves the original portable identity");
        check(ledger.identity(B, "point", 10, "").isEmpty() && ledger.owner("point", 10).equals(A), "switching accounts cannot adopt another account's local point or photo");
        String tripIdentity = ledger.identity(A, "trip", 10, trip);
        check(tripIdentity.equals(trip) && ledger.identity(A, "track", 10, track).equals(track), "point, completed trip and independent track identities use separate namespaces");
        final AccountBackupLedger own = ledger;
        expectFailure(() -> own.identity(A, "point", 10, UUID.randomUUID().toString()), "a conflicting point UUID cannot silently sever its existing account link");
        expectFailure(() -> own.identity(A, "point", 11, point), "two local records cannot claim the same owner/portable identity");
        check(ledger.localId(A, "point", point) == 10 && ledger.localId(B, "point", point) == 0, "remote lookup is scoped to its account");
        String backup = UUID.randomUUID().toString(), newPoint = UUID.randomUUID().toString();
        ledger.beginRestore(A, backup, H1);
        check(!ledger.enabled(A), "choosing restore does not silently enable future uploads");
        check(ledger.reserveRestoreRecord(A, backup, "point", newPoint, 0) == 0 && ledger.owner("point", 500).isEmpty(),
                "a new restore reserves portable ownership without guessing and claiming an unrelated local ID");
        check(ledger.restorePendingCount(A, backup) == 1, "the pre-import reservation is durable pending work");
        ledger = new AccountBackupLedger(disk);
        check(ledger.available() && ledger.restorePendingCount(A, backup) == 1 && ledger.localId(A, "point", newPoint) == 0,
                "crash before local import retains its reservation without inventing a record");
        final AccountBackupLedger afterCrash = ledger;
        expectFailure(() -> afterCrash.completeRestore(A, backup, H1), "a partial restore cannot be reported complete");
        ledger.markRestored(A, backup, "point", newPoint, 501, H2);
        check(ledger.owner("point", 501).equals(A) && ledger.localId(A, "point", newPoint) == 501
                && ledger.importedFingerprint(A, "point", newPoint).equals(H2), "successful idempotent import atomically attaches its actual ID and comparison baseline");
        check(ledger.identity(B, "point", 501, "").isEmpty(), "a restored A point never becomes B's backup after restart");
        ledger.completeRestore(A, backup, H1);
        check(ledger.restoreStatus(A, backup).equals("complete"), "restore completes only after every reservation is marked imported");
        ledger.beginRestore(A, backup, H1);
        check(ledger.reserveRestoreRecord(A, backup, "point", newPoint, 0) == 501, "rerunning the same backup resolves to the existing local record");
        check(ledger.restorePendingCount(A, backup) == 1 && ledger.importedFingerprint(A, "point", newPoint).equals(H2),
                "a new import attempt must confirm current local content while retaining its previous merge baseline");
        expectFailure(() -> afterCrash.completeRestore(A, backup, H1), "a newly detected local conflict cannot reuse a past completed marker");
        ledger.markRestored(A, backup, "point", newPoint, 501, H2); ledger.completeRestore(A, backup, H1);
        final AccountBackupLedger completed = ledger;
        expectFailure(() -> completed.beginRestore(A, backup, H3), "the same immutable backup ID cannot acquire different manifest data");
        expectFailure(() -> completed.completeRestore(A, backup, H3), "a mismatched restore acknowledgement does not clear work");
        String partialBackup = UUID.randomUUID().toString(), another = UUID.randomUUID().toString();
        ledger.beginRestore(A, partialBackup, H3); ledger.reserveRestoreRecord(A, partialBackup, "trip", another, 0);
        ledger.completeRestore(A, backup, H1);
        check(ledger.restoreStatus(A, backup).equals("complete") && ledger.restorePendingCount(A, partialBackup) == 1,
                "another backup's unfinished import does not block or accidentally complete this restore");
        String foreignBackup = UUID.randomUUID().toString(); ledger.beginRestore(B, foreignBackup, H1);
        final AccountBackupLedger foreign = ledger;
        expectFailure(() -> foreign.reserveRestoreRecord(B, foreignBackup, "point", UUID.randomUUID().toString(), 501), "a foreign restore cannot reserve an A-owned local record");
        expectFailure(() -> foreign.markRestored(B, foreignBackup, "trip", UUID.randomUUID().toString(), 999, H1), "local data cannot be acknowledged without its pre-import reservation");
        String newTrack = UUID.randomUUID().toString();
        ledger.reserveRestoreRecord(A, partialBackup, "track", newTrack, 0);
        ledger.markRestored(A, partialBackup, "track", newTrack, 800, H3);
        check(ledger.remoteId(A, "track", 800).equals(newTrack) && ledger.remoteId(A, "trip", 800).isEmpty(), "restoring a current track as an archive retains its track identity namespace");
    }

    private static void diskFailureBoundaries() {
        Disk disk = new Disk(); AccountBackupLedger ledger = new AccountBackupLedger(disk); String initial = disk.text;
        disk.failWrite = true;
        expectFailure(() -> ledger.enable(A), "failed consent persistence prevents enabling background upload");
        check(initial.equals(disk.text) && !ledger.enabled(A), "write failure leaves both old durable and in-memory state intact");
        ledger.enable(A); String beforeJob = disk.text; disk.failWrite = true;
        expectFailure(() -> ledger.claim(A, H1, "stage/a", 0), "a job is never returned before it is durable");
        check(beforeJob.equals(disk.text) && ledger.pending(A) == null, "failed job creation cannot strand an unrecorded HTTP operation");
        AccountBackupLedger.Job job = ledger.claim(A, H1, "stage/a", 0); String beforeAck = disk.text; disk.failWrite = true;
        expectFailure(() -> ledger.finish(job, true, "", 1), "server success is not presented as durable when its acknowledgement write fails");
        check(beforeAck.equals(disk.text) && ledger.pending(A).backupId.equals(job.backupId) && ledger.lastCompletedId(A).isEmpty(), "lost local ACK persistence keeps the idempotent retry job");
        String backup = UUID.randomUUID().toString(), point = UUID.randomUUID().toString(); ledger.beginRestore(A, backup, H1);
        String beforeReservation = disk.text; disk.failWrite = true;
        expectFailure(() -> ledger.reserveRestoreRecord(A, backup, "point", point, 0), "failed pre-import reservation stops local import");
        check(beforeReservation.equals(disk.text) && ledger.restorePendingCount(A, backup) == 0, "failed reservation leaves no half-written ownership");
        ledger.reserveRestoreRecord(A, backup, "point", point, 0); String beforeImported = disk.text; disk.failWrite = true;
        expectFailure(() -> ledger.markRestored(A, backup, "point", point, 123, H2), "failed import acknowledgement remains resumable");
        check(beforeImported.equals(disk.text) && ledger.owner("point", 123).isEmpty() && ledger.restorePendingCount(A, backup) == 1,
                "partial ACK cannot claim a local ID while losing its pending restore marker");
        ledger.markRestored(A, backup, "point", point, 123, H2); String beforeComplete = disk.text; disk.failWrite = true;
        expectFailure(() -> ledger.completeRestore(A, backup, H1), "completion is not claimed before its durable marker exists");
        check(beforeComplete.equals(disk.text) && ledger.restoreStatus(A, backup).equals("pending"), "failed completion preserves resumable progress");
        AccountBackupLedger reloaded = new AccountBackupLedger(disk); reloaded.completeRestore(A, backup, H1);
        check(reloaded.restoreStatus(A, backup).equals("complete") && reloaded.owner("point", 123).equals(A), "restart can finish the preserved restore without reassigning identity");
        Disk initialFailure = new Disk(); initialFailure.failWrite = true; AccountBackupLedger unavailable = new AccountBackupLedger(initialFailure);
        check(!unavailable.available() && initialFailure.text == null, "a failed initial journal write cannot enable an ephemeral actor");
    }

    private static void corruptionFailsClosed() throws Exception {
        Disk original = new Disk(); AccountBackupLedger ledger = new AccountBackupLedger(original); ledger.enable(A);
        ledger.identity(A, "point", 1, UUID.randomUUID().toString()); ledger.claim(A, H1, "stage/a", 0);
        String valid = original.text;
        checkCorruption("{broken", "malformed JSON is preserved rather than discarded");
        checkCorruption(new JSONObject(valid).put("version", 99).toString(), "future journal versions cannot trigger an empty replacement");
        JSONObject missing = new JSONObject(valid); missing.remove("claims");
        checkCorruption(missing.toString(), "missing ownership registry disables cloud backup");
        JSONObject actor = new JSONObject(valid).put("actorId", "not-a-uuid");
        checkCorruption(actor.toString(), "invalid actor identity cannot create a new backup lineage");
        JSONObject owner = new JSONObject(valid); owner.getJSONObject("claims").getJSONObject("point:1").put("owner", "invalid");
        checkCorruption(owner.toString(), "invalid account ownership fails closed");
        JSONObject duplicate = new JSONObject(valid); JSONObject entry = new JSONObject(duplicate.getJSONObject("claims").getJSONObject("point:1").toString()).put("localId", 2);
        duplicate.getJSONObject("claims").put("point:2", entry);
        checkCorruption(duplicate.toString(), "duplicate portable identity mappings are not accepted");
        JSONObject fingerprint = new JSONObject(valid); fingerprint.getJSONObject("accounts").getJSONObject(A).getJSONObject("job").put("fingerprint", "bad");
        checkCorruption(fingerprint.toString(), "invalid staged content fingerprint is never uploaded");
        JSONObject ack = new JSONObject(valid); ack.getJSONObject("accounts").getJSONObject(A).put("lastFingerprint", H1);
        checkCorruption(ack.toString(), "incomplete completion receipts cannot hide pending work");
        Disk unreadable = new Disk(); unreadable.text = valid; unreadable.failRead = true;
        check(!new AccountBackupLedger(unreadable).available() && unreadable.text.equals(valid), "read failure preserves the original registry");
    }
    private static void checkCorruption(String text, String label) {
        Disk disk = new Disk(); disk.text = text; AccountBackupLedger damaged = new AccountBackupLedger(disk);
        check(!damaged.available() && disk.text.equals(text) && disk.writes == 0, label);
        expectFailure(() -> damaged.enable(B), "corrupt journal cannot silently enable B");
        check(damaged.pending(B) == null && damaged.actorId().isEmpty(), "corrupt ownership metadata is not exposed as fresh work");
    }
}
