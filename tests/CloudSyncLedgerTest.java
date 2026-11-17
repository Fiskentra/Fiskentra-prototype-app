import com.fiskentra.app.backend.CloudPointCodec;
import com.fiskentra.app.backend.CloudSyncLedger;
import com.fiskentra.app.data.PointLedger;
import com.fiskentra.app.model.CatchDetails;
import com.fiskentra.app.model.SavedPoint;
import org.json.JSONObject;
import java.io.IOException;
import java.util.UUID;

/** Durable account ownership, response-loss replay, concurrent edits and safe point restore. */
public final class CloudSyncLedgerTest {
    private static final String A = "11111111-1111-4111-8111-111111111111", B = "22222222-2222-4222-8222-222222222222";
    private static int checks;
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private static final class Disk implements PointLedger.Disk {
        String text; boolean fail;
        public String read() { return text; }
        public void write(String value) throws IOException { if (fail) { fail = false; throw new IOException("simulated disk failure"); } text = value; }
    }
    private static SavedPoint point(long id) {
        return new SavedPoint(id, 56.1, 24.2, id, "Catch", "note", null,
                new CatchDetails("Pike", 62, 2.4, "spoon", "details", true, "/private/photo.jpg"),
                "Original", "fish", 0xFF33AA55, 22, "event:" + id, 77, true, 1, "LOCATED", id, 5, "gps");
    }
    public static void main(String[] args) throws Exception {
        Disk disk = new Disk(); CloudSyncLedger cloud = new CloudSyncLedger(disk);
        Disk localDisk = new Disk(); PointLedger local = new PointLedger(localDisk, "[]", "[]", "{}");
        SavedPoint first = local.insert(point(1000));
        cloud.enqueue(A, first);
        check(!cloud.connected(A) && cloud.owner(first.id).isEmpty(), "sign-in/capture alone does not adopt preexisting records");
        check(cloud.claim(A, 0, local) == null, "unconnected account cannot claim work");
        check(cloud.connect(A, local.snapshot().points) == 1, "explicit action connects unowned local points");
        check(cloud.connect(B, local.snapshot().points) == 0, "switching account cannot adopt A's local points");
        check(cloud.claim(B, Long.MAX_VALUE, local) == null && cloud.pending(B) == 0, "A's outbox never runs as B");
        check(cloud.status(B, first.id).equals("other_account"), "UI distinguishes another account's point");
        CloudSyncLedger.Request request = cloud.claim(A, 0, local);
        check(request.owner.equals(A) && request.expectedVersion == 0, "new request includes original account and empty-server version");
        UUID.fromString(request.remoteId); UUID.fromString(request.operationId);
        check(!request.payload.has("id") && !request.payload.has("tripId") && !request.payload.has("eventId"), "device record/trip identities are excluded");
        check(!request.payload.getJSONObject("catch_details").has("local_photo_path"), "device photo path is excluded");
        check(request.payload.getJSONObject("catch_details").getDouble("weight_kg") == 2.4, "catch details are included");
        String originalOp = request.operationId;
        cloud = new CloudSyncLedger(disk); request = cloud.claim(A, Long.MAX_VALUE, local);
        check(originalOp.equals(request.operationId) && request.expectedVersion == 0, "restart replays same operation after a lost HTTP response");
        cloud.finish(request, 1, "", 0);
        check(cloud.pending(A) == 0 && cloud.status(A, first.id).equals("synced"), "confirmed server version clears only that request");

        first = local.edit(first.id, p -> p.withMetadata("Edit one", p.type, p.note)); cloud.enqueue(A, first);
        request = cloud.claim(A, 0, local);
        check(request.expectedVersion == 1, "edit uses last confirmed server version");
        first = local.edit(first.id, p -> p.withMetadata("Edit two", p.type, p.note)); cloud.enqueue(A, first);
        cloud.finish(request, 2, "", 0);
        check(cloud.pending(A) == 1, "a reply for old content does not confirm a newer local edit");
        request = cloud.claim(A, 0, local);
        check(request.payload.getString("title").equals("Edit two") && request.expectedVersion == 2, "new edit follows old request in order");
        cloud.finish(request, 3, "", 0);
        check(cloud.canRestore(A, first.id, 3) && !cloud.canRestore(A, first.id, 2), "restore never rolls back a newer confirmed version");

        SavedPoint changed = local.edit(first.id, p -> p.withMetadata("Unsaved cloud edit", p.type, p.note)); cloud.enqueue(A, changed);
        check(!cloud.canRestore(A, first.id, 4) && !cloud.canRestore(A, first.id, 3), "restore preserves dirty local content, including same-base responses");
        request = cloud.claim(A, 0, local); cloud.finish(request, 0, "conflict", 1000);
        cloud.retry(A);
        check(cloud.claim(A, Long.MAX_VALUE, local) == null && cloud.status(A, first.id).equals("conflict"), "manual retry does not overwrite a cloud conflict");
        String before = disk.text;
        try { cloud.restored(B, request.remoteId, changed, 4, false); throw new AssertionError("cross-account restore accepted"); }
        catch (IllegalStateException expected) { check(before.equals(disk.text), "cross-account restore cannot mutate ownership or disk"); }

        SavedPoint fresh = local.insert(point(2000)); cloud.enqueue(B, fresh);
        CloudSyncLedger.Request uploadB = cloud.claim(B, 0, local); cloud.finish(uploadB, 1, "", 0);
        local.delete(fresh.id); cloud.delete(fresh.id);
        check(cloud.claim(A, Long.MAX_VALUE, local) == null, "account B's deletion does not leak into A");
        CloudSyncLedger.Request deletion = cloud.claim(B, 0, local);
        check(deletion.deleted && deletion.expectedVersion == 1, "uploaded point deletion persists as a versioned tombstone");
        cloud = new CloudSyncLedger(disk);
        CloudSyncLedger.Request replayDelete = cloud.claim(B, 0, local);
        check(replayDelete.operationId.equals(deletion.operationId), "interrupted deletion replays idempotently");
        cloud.finish(replayDelete, 2, "", 0);
        check(cloud.pending(B) == 0 && local.isDeleted(fresh.id), "confirmed delete keeps local tombstone");

        SavedPoint neverSent = local.insert(point(3000)); cloud.enqueue(B, neverSent);
        local.delete(neverSent.id); cloud.delete(neverSent.id);
        check(cloud.claim(B, Long.MAX_VALUE, local) == null, "never-uploaded deletion makes no request");

        JSONObject payload = CloudPointCodec.encode(point(4000));
        SavedPoint downloaded = CloudPointCodec.decode(B, UUID.randomUUID().toString(), payload, 9000);
        check(downloaded.id == 9000 && downloaded.tripId == 0 && downloaded.catchDetails.localPhotoPath.isEmpty(), "restore uses local ID and does not invent photo/trip backup");
        SavedPoint merged = CloudPointCodec.merge(point(4000), downloaded.withMetadata("Cloud name", downloaded.type, "remote note"));
        check(merged.title.equals("Cloud name") && merged.catchDetails.localPhotoPath.equals("/private/photo.jpg"), "restore merges portable edits while keeping a local photo");
        check(merged.eventId.equals("event:4000") && merged.tripId == 77 && merged.timestamp == 4000, "merge keeps device event time/identity/trip association");
        JSONObject unsafe = new JSONObject(payload.toString()).put("lat", 999);
        try { CloudPointCodec.decode(B, "x", unsafe, 1); throw new AssertionError("bad coordinate accepted"); }
        catch (IllegalArgumentException expected) { check(true, "malformed remote coordinates rejected before local insertion"); }
        check(CloudPointCodec.canonical(new JSONObject("{\"a\":1,\"b\":2}")).equals(CloudPointCodec.canonical(new JSONObject("{\"b\":2,\"a\":1}"))), "key order cannot generate phantom edits");

        SavedPoint race = local.insert(point(5000)); cloud.enqueue(B, race);
        CloudSyncLedger.Request baseline = cloud.claim(B, 0, local); cloud.finish(baseline, 1, "", 0);
        SavedPoint snapshot = local.find(race.id);
        local.edit(race.id, p -> p.withMetadata("new UI edit", p.type, p.note)); // enqueue is still waiting behind restore.
        cloud.enqueue(B, local.find(race.id));
        check(!cloud.canRestore(B, race.id, 2), "restore observes committed local edits even before their queued cloud enqueue");
        check(local.editIfRevision(race.id, snapshot.revision, p -> CloudPointCodec.merge(p, downloaded)) == null,
                "edit between snapshot and merge cannot be overwritten");
        check(!local.deleteIfRevision(race.id, snapshot.revision) && local.find(race.id).title.equals("new UI edit"),
                "remote tombstone cannot delete an edit newer than its comparison");
        JSONObject server = CloudPointCodec.encode(point(6000)); String importedRemote = UUID.randomUUID().toString();
        SavedPoint imported = local.insert(CloudPointCodec.decode(B, importedRemote, server, 6000));
        imported = local.edit(imported.id, p -> p.withMetadata("edited after partial import", p.type, p.note));
        cloud.adoptRestored(B, importedRemote, imported, 2, false, server);
        check(cloud.status(B, imported.id).equals("conflict") && local.find(imported.id).title.equals("edited after partial import"),
                "retry after crash between record import and journal save preserves intervening edits");

        String orphanRemote = UUID.randomUUID().toString(); SavedPoint orphan = local.insert(CloudPointCodec.decode(A, orphanRemote, server, 7000));
        Disk orphanDisk = new Disk(); CloudSyncLedger recovered = new CloudSyncLedger(orphanDisk);
        check(recovered.connect(B, java.util.Collections.singletonList(orphan)) == 0, "account B cannot adopt A's point from an interrupted restore");
        recovered.enqueue(B, orphan);
        check(recovered.owner(orphan.id).isEmpty(), "capture/edit path also rejects a foreign cloud-origin event");
        recovered.connect(A, java.util.Collections.singletonList(orphan));
        check(recovered.localId(A, orphanRemote) == orphan.id && recovered.status(A, orphan.id).equals("conflict"), "original owner recovers cloud identity without duplicating/uploading it");
        recovered.restored(A, orphanRemote, orphan, 2, false, server);
        check(recovered.pending(A) == 0, "already imported content equal to server can be acknowledged after a crash");
        local.delete(orphan.id); recovered.delete(orphan.id); recovered.acknowledgeDeleted(A, orphan.id, 3);
        check(recovered.pending(A) == 0 && local.isDeleted(orphan.id), "a confirmed remote tombstone reconciles a local deletion without resurrection");

        for (String broken : new String[] {"not-json", "{\"version\":2}", "{\"version\":1,\"points\":{}}", "{\"version\":1,\"accounts\":{},\"points\":{\"1\":{}}}"}) {
            Disk damaged = new Disk(); damaged.text = broken; CloudSyncLedger unavailable = new CloudSyncLedger(damaged);
            check(!unavailable.available() && !unavailable.connected(A) && unavailable.claim(A, 0, local) == null,
                    "damaged cloud journal disables only cloud, not local storage");
            unavailable.enqueue(A, orphan); unavailable.delete(orphan.id); unavailable.retry(A);
            check(damaged.text.equals(broken), "damaged ownership/outbox file is preserved without a silent reset");
        }
        Disk cannotCreate = new Disk(); cannotCreate.fail = true;
        CloudSyncLedger noStorage = new CloudSyncLedger(cannotCreate);
        check(!noStorage.available() && cannotCreate.text == null && local.snapshot().points.size() > 0,
                "initial cloud journal write failure keeps the local application usable");

        Disk failureDisk = new Disk(); CloudSyncLedger atomic = new CloudSyncLedger(failureDisk); String old = failureDisk.text; failureDisk.fail = true;
        try { atomic.connect(A, local.snapshot().points); throw new AssertionError("disk failure accepted"); }
        catch (IllegalStateException expected) { check(!atomic.connected(A) && old.equals(failureDisk.text), "failed ownership persistence cannot advance memory or disk"); }
        System.out.println("CloudSyncLedgerTest: " + checks + " checks passed");
    }
}
