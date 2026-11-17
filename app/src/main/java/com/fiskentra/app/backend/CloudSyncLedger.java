package com.fiskentra.app.backend;

import com.fiskentra.app.data.PointLedger;
import com.fiskentra.app.model.SavedPoint;
import org.json.JSONObject;
import java.util.List;
import java.util.UUID;

/** Account ownership and durable requests are separate from the preserved legacy/device outbox. */
public final class CloudSyncLedger {
    public static final class Request {
        public final String owner, remoteId, operationId;
        public final long localId, expectedVersion;
        public final boolean deleted;
        public final JSONObject payload;
        Request(JSONObject entry, JSONObject job) throws Exception {
            owner = entry.getString("owner"); remoteId = entry.getString("remoteId"); localId = entry.getLong("localId");
            operationId = job.getString("operationId"); expectedVersion = job.getLong("expectedVersion");
            deleted = job.getBoolean("deleted"); payload = new JSONObject(job.getJSONObject("payload").toString());
        }
    }
    private final PointLedger.Disk disk;
    private JSONObject state;
    private boolean available;
    public CloudSyncLedger(PointLedger.Disk disk) {
        this.disk = disk;
        try {
            String text = disk.read();
            state = text == null ? new JSONObject().put("version", 1).put("accounts", new JSONObject()).put("points", new JSONObject()) : new JSONObject(text);
            if (state.getInt("version") != 1) throw new IllegalStateException("Unsupported cloud journal version");
            if (state.optJSONObject("accounts") == null || state.optJSONObject("points") == null) throw new IllegalStateException("Incomplete cloud journal");
            java.util.Iterator<String> accounts = state.getJSONObject("accounts").keys();
            while (accounts.hasNext()) { String owner = accounts.next(); requireOwner(owner); state.getJSONObject("accounts").getBoolean(owner); }
            java.util.HashSet<String> identities = new java.util.HashSet<>();
            java.util.Iterator<String> ids = state.getJSONObject("points").keys();
            while (ids.hasNext()) {
                String id = ids.next(); JSONObject entry = state.getJSONObject("points").getJSONObject(id);
                requireOwner(entry.getString("owner")); UUID.fromString(entry.getString("remoteId"));
                if (entry.getLong("localId") <= 0 || !id.equals(String.valueOf(entry.getLong("localId"))) || entry.optJSONObject("payload") == null)
                    throw new IllegalStateException("Invalid cloud ownership entry");
                if (!identities.add(entry.getString("owner") + ":" + entry.getString("remoteId")) || entry.getLong("remoteVersion") < 0)
                    throw new IllegalStateException("Invalid cloud record identity/version");
                entry.getBoolean("deleted"); entry.getString("status");
                CloudPointCodec.decode(entry.getString("owner"), entry.getString("remoteId"), entry.getJSONObject("payload"), entry.getLong("localId"));
                if (entry.has("job")) {
                    Request job = new Request(entry, entry.getJSONObject("job")); UUID.fromString(job.operationId);
                    if (job.expectedVersion < 0) throw new IllegalStateException("Invalid request version");
                    CloudPointCodec.decode(job.owner, job.remoteId, job.payload, job.localId);
                }
            }
            if (text == null) disk.write(state.toString());
            available = true;
        } catch (Exception error) { state = null; available = false; /* Keep the original file untouched. */ }
    }
    public synchronized boolean available() { return available; }
    public synchronized boolean connected(String owner) { return available && owner != null && !owner.isEmpty() && state.optJSONObject("accounts").optBoolean(owner); }
    public synchronized String owner(long id) { JSONObject entry = entry(id); return entry == null ? "" : entry.optString("owner"); }
    /** Full backups reuse point identities; they never invent a second cloud point. */
    public synchronized String remoteId(String owner, long id) {
        requireAvailable();
        JSONObject entry = entry(id);
        return entry != null && owner.equals(entry.optString("owner")) ? entry.optString("remoteId") : "";
    }
    public synchronized boolean mayExistRemotely(long id) {
        JSONObject entry = entry(id); return entry != null && (entry.optLong("remoteVersion") > 0 || entry.optInt("attempts") > 0);
    }
    /** Explicit action: only unowned local records can be connected. A different account never adopts them. */
    public synchronized int connect(String owner, List<SavedPoint> points) {
        requireAvailable();
        requireOwner(owner);
        try {
            JSONObject next = copy(); next.getJSONObject("accounts").put(owner, true); int added = 0;
            for (SavedPoint point : points) {
                JSONObject entry = next.getJSONObject("points").optJSONObject(String.valueOf(point.id));
                if (entry == null) {
                    if (!mayAdopt(owner, point)) continue;
                    entry = create(owner, point); next.getJSONObject("points").put(String.valueOf(point.id), entry); added++;
                }
                else if (owner.equals(entry.optString("owner"))) update(entry, point);
            }
            commit(next); return added;
        } catch (Exception error) { throw failure(error); }
    }
    /** Used for captures/edits after explicit account connection, never as a bulk background adoption. */
    public synchronized void enqueue(String owner, SavedPoint point) {
        if (point == null || !connected(owner)) return;
        try {
            JSONObject next = copy(); JSONObject entry = next.getJSONObject("points").optJSONObject(String.valueOf(point.id));
            if (entry == null) {
                if (!mayAdopt(owner, point)) return;
                next.getJSONObject("points").put(String.valueOf(point.id), create(owner, point));
            }
            else if (owner.equals(entry.optString("owner"))) update(entry, point);
            else return;
            commit(next);
        } catch (Exception error) { throw failure(error); }
    }
    /** Deletion stays attached to its original owner, even while signed out or using another account. */
    public synchronized void delete(long id) {
        if (!available) return; // The local ledger keeps the tombstone until cloud recovery.
        if (entry(id) == null) return;
        try { JSONObject next = copy(); next.getJSONObject("points").getJSONObject(String.valueOf(id)).put("deleted", true); commit(next); }
        catch (Exception error) { throw failure(error); }
    }
    public synchronized Request claim(String owner, long now, PointLedger local) {
        if (!connected(owner)) return null;
        try {
            JSONObject next = copy(); JSONObject entries = next.getJSONObject("points"); java.util.Iterator<String> keys = entries.keys();
            while (keys.hasNext()) {
                JSONObject entry = entries.getJSONObject(keys.next()); if (!owner.equals(entry.getString("owner"))) continue;
                SavedPoint point = local.find(entry.getLong("localId"));
                if (point != null) update(entry, point); else if (local.isDeleted(entry.getLong("localId"))) entry.put("deleted", true);
                if (!dirty(entry)) continue;
                String status = entry.optString("status");
                if ("auth".equals(status) || "conflict".equals(status) || "permanent".equals(status) || entry.optLong("retryAt") > now) continue;
                JSONObject job = entry.optJSONObject("job");
                if (job == null) {
                    job = new JSONObject().put("operationId", UUID.randomUUID().toString()).put("expectedVersion", entry.optLong("remoteVersion"))
                            .put("deleted", entry.getBoolean("deleted")).put("payload", new JSONObject(entry.getJSONObject("payload").toString()));
                    entry.put("job", job);
                }
                entry.put("attempts", entry.optInt("attempts") + 1).put("status", "syncing");
                commit(next); return new Request(entry, job);
            }
            // Capture silent edits/deletes without consuming any legacy operation.
            commit(next); return null;
        } catch (Exception error) { throw failure(error); }
    }
    public synchronized void finish(Request request, long remoteVersion, String category, long now) {
        requireAvailable();
        try {
            JSONObject next = copy(); JSONObject entry = next.getJSONObject("points").optJSONObject(String.valueOf(request.localId));
            if (entry == null || !request.owner.equals(entry.optString("owner"))) return;
            JSONObject job = entry.optJSONObject("job"); if (job == null || !request.operationId.equals(job.optString("operationId"))) return;
            if (remoteVersion > 0) {
                entry.put("remoteVersion", remoteVersion).put("ackPayload", CloudPointCodec.canonical(job.getJSONObject("payload")))
                        .put("ackDeleted", job.getBoolean("deleted")).put("status", "synced").put("attempts", 0).put("retryAt", 0);
                entry.remove("job");
            } else {
                entry.put("status", category).put("retryAt", now + PointLedger.backoff(entry.optInt("attempts")));
            }
            commit(next);
        } catch (Exception error) { throw failure(error); }
    }
    public synchronized void retry(String owner) {
        if (!available) return;
        try {
            JSONObject next = copy(); java.util.Iterator<String> keys = next.getJSONObject("points").keys();
            while (keys.hasNext()) { JSONObject entry = next.getJSONObject("points").getJSONObject(keys.next());
                if (owner.equals(entry.optString("owner")) && !"conflict".equals(entry.optString("status"))) entry.put("status", "pending").put("retryAt", 0);
            }
            commit(next);
        } catch (Exception error) { throw failure(error); }
    }
    public synchronized int pending(String owner) {
        if (!available) return 0;
        int count = 0; java.util.Iterator<String> keys = state.optJSONObject("points").keys();
        try { while (keys.hasNext()) { JSONObject entry = state.getJSONObject("points").getJSONObject(keys.next()); if (owner.equals(entry.optString("owner")) && dirty(entry)) count++; } }
        catch (Exception error) { throw failure(error); } return count;
    }
    public synchronized String status(String owner, long id) {
        JSONObject entry = entry(id); if (entry == null) return "local";
        if (!entry.optString("owner").equals(owner)) return "other_account";
        try { if (!dirty(entry)) return entry.optBoolean("deleted") ? "deleted" : "synced"; }
        catch (Exception error) { throw failure(error); }
        String status = entry.optString("status");
        return "synced".equals(status) || status.isEmpty() ? "pending" : status;
    }
    public synchronized long localId(String owner, String remoteId) {
        if (!available) return 0;
        java.util.Iterator<String> keys = state.optJSONObject("points").keys();
        while (keys.hasNext()) { JSONObject entry = state.optJSONObject("points").optJSONObject(keys.next()); if (owner.equals(entry.optString("owner")) && remoteId.equals(entry.optString("remoteId"))) return entry.optLong("localId"); }
        return 0;
    }
    public synchronized boolean canRestore(String owner, long id, long version) {
        if (!available) return false;
        JSONObject entry = entry(id); if (entry == null) return true;
        try { return owner.equals(entry.optString("owner")) && version >= entry.optLong("remoteVersion") && !dirty(entry); }
        catch (Exception error) { throw failure(error); }
    }
    /** Restore only after local persistence succeeds. Repeated downloads do not duplicate records. */
    public synchronized void restored(String owner, String remoteId, SavedPoint point, long version, boolean deleted) {
        try { restored(owner, remoteId, point, version, deleted, CloudPointCodec.encode(point)); }
        catch (Exception error) { throw failure(error); }
    }
    public synchronized void restored(String owner, String remoteId, SavedPoint point, long version, boolean deleted, JSONObject serverPayload) {
        requireAvailable();
        requireOwner(owner);
        try {
            JSONObject next = copy(); JSONObject entries = next.getJSONObject("points"); JSONObject previous = entries.optJSONObject(String.valueOf(point.id));
            if (previous != null && !owner.equals(previous.optString("owner"))) throw new IllegalStateException("Record belongs to another account");
            JSONObject entry = create(owner, point).put("remoteId", remoteId).put("remoteVersion", version).put("deleted", deleted)
                    .put("ackPayload", CloudPointCodec.canonical(serverPayload)).put("ackDeleted", deleted).put("status", "synced");
            if (dirty(entry)) entry.put("status", "conflict");
            entries.put(String.valueOf(point.id), entry); commit(next);
        } catch (Exception error) { throw failure(error); }
    }
    /** Crash recovery: link an already imported record without replacing edits made before journal persistence. */
    public synchronized void adoptRestored(String owner, String remoteId, SavedPoint point, long version, boolean serverDeleted, JSONObject serverPayload) {
        requireAvailable(); requireOwner(owner);
        try {
            JSONObject next = copy(); JSONObject entries = next.getJSONObject("points"); JSONObject previous = entries.optJSONObject(String.valueOf(point.id));
            if (previous != null && !owner.equals(previous.optString("owner"))) throw new IllegalStateException("Record belongs to another account");
            JSONObject entry = create(owner, point).put("remoteId", remoteId).put("remoteVersion", version)
                    .put("ackPayload", CloudPointCodec.canonical(serverPayload)).put("ackDeleted", serverDeleted).put("status", "synced");
            if (dirty(entry)) entry.put("status", "conflict");
            entries.put(String.valueOf(point.id), entry); commit(next);
        } catch (Exception error) { throw failure(error); }
    }
    public synchronized void acknowledgeDeleted(String owner, long id, long version) {
        requireAvailable();
        try {
            JSONObject next = copy(); JSONObject entry = next.getJSONObject("points").optJSONObject(String.valueOf(id));
            if (entry == null || !owner.equals(entry.optString("owner")) || version < entry.optLong("remoteVersion")) return;
            entry.put("remoteVersion", version).put("deleted", true).put("ackDeleted", true)
                    .put("ackPayload", CloudPointCodec.canonical(entry.getJSONObject("payload"))).put("status", "synced");
            entry.remove("job"); commit(next);
        } catch (Exception error) { throw failure(error); }
    }
    public synchronized long remoteVersion(long id) { JSONObject entry = entry(id); return entry == null ? 0 : entry.optLong("remoteVersion"); }
    private JSONObject entry(long id) { return available ? state.optJSONObject("points").optJSONObject(String.valueOf(id)) : null; }
    private static JSONObject create(String owner, SavedPoint point) throws Exception {
        JSONObject entry = new JSONObject().put("owner", owner).put("localId", point.id).put("remoteId", UUID.randomUUID().toString()).put("payload", CloudPointCodec.encode(point))
                .put("remoteVersion", 0).put("deleted", false).put("ackDeleted", false).put("ackPayload", "").put("status", "pending");
        if (point.eventId.startsWith("cloud:")) {
            String[] parts = point.eventId.split(":");
            entry.put("remoteId", parts[2]).put("status", "conflict"); // Recover the known identity through Restore, never create another account copy.
        }
        return entry;
    }
    private static boolean mayAdopt(String owner, SavedPoint point) {
        if (!point.eventId.startsWith("cloud:")) return true;
        String[] parts = point.eventId.split(":");
        if (parts.length != 3 || !owner.equals(parts[1])) return false;
        try { UUID.fromString(parts[1]); UUID.fromString(parts[2]); return true; }
        catch (IllegalArgumentException error) { return false; }
    }
    private static void update(JSONObject entry, SavedPoint point) throws Exception { if (!entry.optBoolean("deleted")) entry.put("payload", CloudPointCodec.encode(point)); }
    private static boolean dirty(JSONObject entry) throws Exception {
        if (entry.optBoolean("deleted") && entry.optLong("remoteVersion") == 0 && !entry.has("job") && entry.optInt("attempts") == 0) return false;
        return entry.has("job") || entry.optLong("remoteVersion") == 0 || entry.getBoolean("deleted") != entry.optBoolean("ackDeleted")
                || !CloudPointCodec.canonical(entry.getJSONObject("payload")).equals(entry.optString("ackPayload"));
    }
    private JSONObject copy() throws Exception { return new JSONObject(state.toString()); }
    private void commit(JSONObject next) throws Exception {
        requireAvailable();
        if (CloudPointCodec.canonical(next).equals(CloudPointCodec.canonical(state))) return;
        disk.write(next.toString()); state = next;
    }
    private void requireAvailable() { if (!available) throw new IllegalStateException("Cloud queue needs recovery; local records remain available"); }
    private static void requireOwner(String owner) { if (owner == null || !owner.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("Sign in to sync points"); }
    private static IllegalStateException failure(Exception error) { return new IllegalStateException("Cloud queue could not be saved; local points are unchanged", error); }
}
