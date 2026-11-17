package com.fiskentra.app.backend;

import com.fiskentra.app.data.PointLedger;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/** Durable account consent, immutable backup jobs and restore ownership; contains no image bytes or tokens. */
public final class AccountBackupLedger {
    public static final class Job {
        public final String owner, actorId, backupId, fingerprint, snapshotPath, status;
        public final int attempts;
        public final long createdAt, retryAt;
        private Job(String owner, String actorId, JSONObject value) throws Exception {
            this.owner = owner; this.actorId = actorId;
            backupId = value.getString("backupId"); fingerprint = value.getString("fingerprint");
            snapshotPath = value.getString("snapshotPath"); status = value.getString("status");
            attempts = value.getInt("attempts"); createdAt = value.getLong("createdAt"); retryAt = value.getLong("retryAt");
        }
    }

    private final PointLedger.Disk disk;
    private JSONObject state;
    private boolean available;

    public AccountBackupLedger(PointLedger.Disk disk) {
        this.disk = disk;
        try {
            String original = disk.read();
            JSONObject loaded = original == null ? new JSONObject().put("version", 1)
                    .put("actorId", UUID.randomUUID().toString()).put("accounts", new JSONObject())
                    .put("claims", new JSONObject()).put("restores", new JSONObject()) : new JSONObject(original);
            validate(loaded);
            if (original == null) disk.write(loaded.toString());
            state = loaded; available = true;
        } catch (Exception failure) {
            // An unreadable/incomplete journal is never replaced with an empty ownership registry.
            state = null; available = false;
        }
    }

    public synchronized boolean available() { return available; }
    public synchronized String actorId() { return available ? state.optString("actorId") : ""; }
    public synchronized boolean enabled(String owner) {
        JSONObject account = account(owner);
        return account != null && account.optBoolean("enabled");
    }

    /** Called only for the explicit full-backup action, not merely on sign-in. */
    public synchronized void enable(String owner) { setEnabled(owner, true); }
    public synchronized void disable(String owner) { setEnabled(owner, false); }

    private void setEnabled(String owner, boolean enabled) {
        requireAvailable(); requireUuid(owner);
        try {
            JSONObject next = copy(); mutableAccount(next, owner).put("enabled", enabled); commit(next);
        } catch (Exception failure) { throw failure(failure); }
    }

    public synchronized boolean needsBackup(String owner, String fingerprint) {
        requireHash(fingerprint);
        JSONObject account = account(owner);
        return account != null && account.optBoolean("enabled")
                && (account.has("job") || !fingerprint.equals(account.optString("lastFingerprint")));
    }

    /** Persists a job before HTTP. A newer capture cannot replace a response-loss retry's immutable data. */
    public synchronized Job claim(String owner, String fingerprint, String snapshotPath, long now) {
        requireAvailable(); requireUuid(owner); requireHash(fingerprint); requirePath(snapshotPath);
        if (!enabled(owner)) return null;
        try {
            JSONObject next = copy(); JSONObject account = mutableAccount(next, owner);
            JSONObject job = account.optJSONObject("job");
            if (job == null) {
                if (fingerprint.equals(account.optString("lastFingerprint"))) return null;
                job = new JSONObject().put("backupId", UUID.randomUUID().toString()).put("fingerprint", fingerprint)
                        .put("snapshotPath", snapshotPath).put("createdAt", nonnegative(now))
                        .put("attempts", 0).put("retryAt", 0L).put("status", "pending");
                account.put("job", job);
            }
            String status = job.getString("status");
            if ("auth".equals(status) || "conflict".equals(status) || "permanent".equals(status)
                    || job.getLong("retryAt") > nonnegative(now)) return null;
            job.put("attempts", Math.min(Integer.MAX_VALUE, (long) job.getInt("attempts") + 1))
                    .put("status", "uploading");
            commit(next);
            return new Job(owner, state.getString("actorId"), job);
        } catch (Exception failure) { throw failure(failure); }
    }

    /** A copy of staged work; callers must validate the path against their private staging root. */
    public synchronized Job pending(String owner) {
        JSONObject account = account(owner);
        if (account == null || !account.has("job")) return null;
        try { return new Job(owner, state.getString("actorId"), account.getJSONObject("job")); }
        catch (Exception failure) { throw failure(failure); }
    }

    public synchronized int pendingCount(String owner) { return pending(owner) == null ? 0 : 1; }
    public synchronized String status(String owner) {
        if (!available) return "unavailable";
        JSONObject account = account(owner);
        if (account == null || !account.optBoolean("enabled")) return "disabled";
        JSONObject job = account.optJSONObject("job");
        return job == null ? account.optString("lastCompletedId").isEmpty() ? "idle" : "complete" : job.optString("status");
    }
    public synchronized String lastCompletedId(String owner) {
        JSONObject account = account(owner); return account == null ? "" : account.optString("lastCompletedId");
    }
    public synchronized String lastFingerprint(String owner) {
        JSONObject account = account(owner); return account == null ? "" : account.optString("lastFingerprint");
    }
    public synchronized long lastCompletedAt(String owner) {
        JSONObject account = account(owner); return account == null ? 0L : account.optLong("lastCompletedAt");
    }

    /** Old/signed-out replies cannot acknowledge another account, snapshot or newer pending operation. */
    public synchronized void finish(Job request, boolean success, String category, long now) {
        requireAvailable();
        if (request == null) return;
        try {
            JSONObject next = copy(); JSONObject account = next.getJSONObject("accounts").optJSONObject(request.owner);
            if (account == null || !state.getString("actorId").equals(request.actorId)) return;
            JSONObject job = account.optJSONObject("job");
            if (job == null || !matches(request, job)) return;
            if (success) {
                account.put("lastCompletedId", request.backupId).put("lastFingerprint", request.fingerprint)
                        .put("lastCompletedAt", nonnegative(now));
                account.remove("job");
            } else {
                String status = "auth".equals(category) || "conflict".equals(category) || "permanent".equals(category)
                        ? category : "failed";
                long delay = PointLedger.backoff(Math.max(1, job.getInt("attempts")));
                job.put("status", status).put("retryAt", addWithoutOverflow(nonnegative(now), delay));
            }
            commit(next);
        } catch (Exception failure) { throw failure(failure); }
    }

    /** Retains the same backup ID/path; an immutable-content conflict is never silently overwritten. */
    public synchronized void retry(String owner) {
        requireAvailable(); requireUuid(owner);
        try {
            JSONObject next = copy(); JSONObject account = next.getJSONObject("accounts").optJSONObject(owner);
            JSONObject job = account == null ? null : account.optJSONObject("job");
            if (job == null || "conflict".equals(job.optString("status"))) return;
            job.put("status", "pending").put("retryAt", 0L); commit(next);
        } catch (Exception failure) { throw failure(failure); }
    }

    public synchronized String owner(String kind, long localId) {
        requireKind(kind); requireLocalId(localId);
        JSONObject claim = available ? state.optJSONObject("claims").optJSONObject(localKey(kind, localId)) : null;
        return claim == null ? "" : claim.optString("owner");
    }

    /** Creates a durable claim for an explicit capture. A foreign account receives no identity. */
    public synchronized String identity(String owner, String kind, long localId, String preferredRemoteUuid) {
        requireAvailable(); requireUuid(owner); requireKind(kind); requireLocalId(localId);
        if (preferredRemoteUuid == null) preferredRemoteUuid = "";
        if (!preferredRemoteUuid.isEmpty()) requireUuid(preferredRemoteUuid);
        try {
            JSONObject existing = state.getJSONObject("claims").optJSONObject(localKey(kind, localId));
            if (existing != null) {
                if (!owner.equals(existing.getString("owner"))) return "";
                if (!preferredRemoteUuid.isEmpty() && !preferredRemoteUuid.equals(existing.getString("remoteId")))
                    throw new IllegalStateException("Backup identity disagrees with the point account identity");
                return existing.getString("remoteId");
            }
            String remote = preferredRemoteUuid.isEmpty() ? UUID.randomUUID().toString() : preferredRemoteUuid;
            JSONObject next = copy(); claimIdentity(next, owner, kind, localId, remote); commit(next); return remote;
        } catch (Exception failure) { throw failure(failure); }
    }

    public synchronized long localId(String owner, String kind, String remoteUuid) {
        requireUuid(owner); requireKind(kind); requireUuid(remoteUuid);
        return available ? findLocalId(state, owner, kind, remoteUuid) : 0L;
    }
    public synchronized String remoteId(String owner, String kind, long localId) {
        requireUuid(owner); requireKind(kind); requireLocalId(localId);
        JSONObject claim = available ? state.optJSONObject("claims").optJSONObject(localKey(kind, localId)) : null;
        return claim != null && owner.equals(claim.optString("owner")) ? claim.optString("remoteId") : "";
    }
    public synchronized String importedFingerprint(String owner, String kind, String remoteUuid) {
        long id = localId(owner, kind, remoteUuid);
        JSONObject claim = id == 0 ? null : state.optJSONObject("claims").optJSONObject(localKey(kind, id));
        return claim == null ? "" : claim.optString("importedFingerprint");
    }

    /** Explicit restore permission does not automatically enable background uploads. */
    public synchronized void beginRestore(String owner, String backupId, String manifestFingerprint) {
        requireAvailable(); requireUuid(owner); requireUuid(backupId); requireHash(manifestFingerprint);
        try {
            JSONObject next = copy(); JSONObject restores = next.getJSONObject("restores");
            String key = restoreKey(owner, backupId); JSONObject restore = restores.optJSONObject(key);
            if (restore != null) {
                if (!manifestFingerprint.equals(restore.getString("fingerprint")))
                    throw new IllegalStateException("Immutable backup fingerprint changed");
                return;
            }
            restores.put(key, new JSONObject().put("owner", owner).put("backupId", backupId)
                    .put("fingerprint", manifestFingerprint).put("status", "pending").put("entries", new JSONObject()));
            commit(next);
        } catch (Exception failure) { throw failure(failure); }
    }

    /**
     * Registers owner+portable identity before local import. Use candidateLocalId=0 for a new record.
     * Such records MUST embed their owner/remote identity in their local event/trip data so a crash
     * before markRestored cannot make them appear unowned to another account.
     */
    public synchronized long reserveRestoreRecord(String owner, String backupId, String kind,
                                                   String remoteUuid, long candidateLocalId) {
        requireAvailable(); requireUuid(owner); requireUuid(backupId); requireKind(kind); requireUuid(remoteUuid);
        if (candidateLocalId < 0) throw new IllegalArgumentException("Invalid backup local identity");
        try {
            JSONObject next = copy(); JSONObject restore = requireRestore(next, owner, backupId);
            long mapped = findLocalId(next, owner, kind, remoteUuid);
            long chosen = mapped > 0 ? mapped : candidateLocalId;
            if (chosen > 0) claimIdentity(next, owner, kind, chosen, remoteUuid);
            JSONObject entries = restore.getJSONObject("entries"); String key = remoteKey(kind, remoteUuid);
            JSONObject entry = entries.optJSONObject(key);
            if (entry == null) entry = new JSONObject().put("kind", kind).put("remoteId", remoteUuid);
            entry.put("localId", chosen).put("imported", false).put("contentFingerprint", "");
            entries.put(key, entry); restore.put("status", "pending"); commit(next); return chosen;
        } catch (Exception failure) { throw failure(failure); }
    }

    public synchronized void markRestored(String owner, String backupId, String kind, String remoteUuid,
                                          long actualLocalId, String contentFingerprint) {
        requireAvailable(); requireUuid(owner); requireUuid(backupId); requireKind(kind); requireUuid(remoteUuid);
        requireLocalId(actualLocalId); requireHash(contentFingerprint);
        try {
            JSONObject next = copy(); JSONObject restore = requireRestore(next, owner, backupId);
            JSONObject entry = restore.getJSONObject("entries").optJSONObject(remoteKey(kind, remoteUuid));
            if (entry == null) throw new IllegalStateException("Restore identity was not reserved before import");
            long planned = entry.getLong("localId");
            if (planned > 0 && planned != actualLocalId) throw new IllegalStateException("Reserved restore identity changed");
            JSONObject claim = claimIdentity(next, owner, kind, actualLocalId, remoteUuid);
            claim.put("importedFingerprint", contentFingerprint);
            entry.put("localId", actualLocalId).put("imported", true).put("contentFingerprint", contentFingerprint);
            commit(next);
        } catch (Exception failure) { throw failure(failure); }
    }

    public synchronized int restorePendingCount(String owner, String backupId) {
        if (!available) return 0;
        requireUuid(owner); requireUuid(backupId);
        JSONObject restore = state.optJSONObject("restores").optJSONObject(restoreKey(owner, backupId));
        if (restore == null) return 0;
        int pending = 0; JSONObject entries = restore.optJSONObject("entries"); Iterator<String> keys = entries.keys();
        while (keys.hasNext()) if (!entries.optJSONObject(keys.next()).optBoolean("imported")) pending++;
        return pending;
    }
    public synchronized String restoreStatus(String owner, String backupId) {
        if (!available) return "unavailable";
        requireUuid(owner); requireUuid(backupId);
        JSONObject restore = state.optJSONObject("restores").optJSONObject(restoreKey(owner, backupId));
        return restore == null ? "none" : restore.optString("status");
    }
    public synchronized void completeRestore(String owner, String backupId, String manifestFingerprint) {
        requireAvailable(); requireUuid(owner); requireUuid(backupId); requireHash(manifestFingerprint);
        try {
            JSONObject next = copy(); JSONObject restore = requireRestore(next, owner, backupId);
            if (!manifestFingerprint.equals(restore.getString("fingerprint")))
                throw new IllegalStateException("Restore confirmation fingerprint changed");
            JSONObject entries = restore.getJSONObject("entries"); Iterator<String> keys = entries.keys();
            while (keys.hasNext()) if (!entries.getJSONObject(keys.next()).getBoolean("imported"))
                throw new IllegalStateException("Restore has unfinished records; local data was retained");
            restore.put("status", "complete"); commit(next);
        } catch (Exception failure) { throw failure(failure); }
    }

    private JSONObject account(String owner) {
        return available && owner != null ? state.optJSONObject("accounts").optJSONObject(owner) : null;
    }
    private static JSONObject mutableAccount(JSONObject target, String owner) throws Exception {
        JSONObject accounts = target.getJSONObject("accounts"); JSONObject account = accounts.optJSONObject(owner);
        if (account == null) {
            account = new JSONObject().put("enabled", false).put("lastFingerprint", "")
                    .put("lastCompletedId", "").put("lastCompletedAt", 0L);
            accounts.put(owner, account);
        }
        return account;
    }
    private static JSONObject claimIdentity(JSONObject target, String owner, String kind, long localId, String remoteUuid) throws Exception {
        JSONObject claims = target.getJSONObject("claims"); String key = localKey(kind, localId);
        JSONObject claim = claims.optJSONObject(key); long mapped = findLocalId(target, owner, kind, remoteUuid);
        if (mapped > 0 && mapped != localId) throw new IllegalStateException("Portable identity already has another local record");
        if (claim != null) {
            if (!owner.equals(claim.getString("owner")) || !remoteUuid.equals(claim.getString("remoteId")))
                throw new IllegalStateException("Local record belongs to another backup identity");
            return claim;
        }
        claim = new JSONObject().put("owner", owner).put("kind", kind).put("localId", localId)
                .put("remoteId", remoteUuid).put("importedFingerprint", "");
        claims.put(key, claim); return claim;
    }
    private static long findLocalId(JSONObject target, String owner, String kind, String remoteUuid) {
        JSONObject claims = target.optJSONObject("claims"); Iterator<String> keys = claims.keys();
        while (keys.hasNext()) {
            JSONObject value = claims.optJSONObject(keys.next());
            if (owner.equals(value.optString("owner")) && kind.equals(value.optString("kind"))
                    && remoteUuid.equals(value.optString("remoteId"))) return value.optLong("localId");
        }
        return 0L;
    }
    private static JSONObject requireRestore(JSONObject target, String owner, String backupId) throws Exception {
        JSONObject restore = target.getJSONObject("restores").optJSONObject(restoreKey(owner, backupId));
        if (restore == null) throw new IllegalStateException("Restore must be prepared before importing records");
        return restore;
    }
    private static boolean matches(Job request, JSONObject value) throws Exception {
        return request.backupId.equals(value.getString("backupId")) && request.fingerprint.equals(value.getString("fingerprint"))
                && request.snapshotPath.equals(value.getString("snapshotPath")) && request.createdAt == value.getLong("createdAt");
    }
    private static String localKey(String kind, long localId) { return kind + ":" + localId; }
    private static String remoteKey(String kind, String remoteUuid) { return kind + ":" + remoteUuid; }
    private static String restoreKey(String owner, String backupId) { return owner + ":" + backupId; }
    private JSONObject copy() throws Exception { return new JSONObject(state.toString()); }
    private void commit(JSONObject next) throws Exception {
        requireAvailable(); validate(next);
        if (next.toString().equals(state.toString())) return;
        disk.write(next.toString()); state = next;
    }
    private void requireAvailable() { if (!available) throw new IllegalStateException("Backup journal needs recovery; local data remains available"); }
    private static IllegalStateException failure(Exception cause) { return new IllegalStateException("Backup journal could not be saved; local data was retained", cause); }
    private static long nonnegative(long value) { return Math.max(0L, value); }
    private static long addWithoutOverflow(long value, long amount) { return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount; }
    private static void requireLocalId(long id) { if (id <= 0) throw new IllegalArgumentException("Invalid backup local identity"); }
    private static void requireUuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Invalid backup account/identity");
    }
    private static void requireKind(String kind) {
        if (!"point".equals(kind) && !"trip".equals(kind) && !"track".equals(kind)) throw new IllegalArgumentException("Unsupported backup record kind");
    }
    private static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid backup fingerprint");
    }
    private static void requirePath(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 4096 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid backup staging path");
    }
    private static void validate(JSONObject value) throws Exception {
        if (value.getInt("version") != 1) throw new IllegalStateException("Unsupported backup journal version");
        requireUuid(value.getString("actorId"));
        JSONObject accounts = value.getJSONObject("accounts"), claims = value.getJSONObject("claims"), restores = value.getJSONObject("restores");
        Iterator<String> accountKeys = accounts.keys();
        while (accountKeys.hasNext()) {
            String owner = accountKeys.next(); requireUuid(owner); JSONObject account = accounts.getJSONObject(owner);
            account.getBoolean("enabled"); String fingerprint = account.getString("lastFingerprint"), id = account.getString("lastCompletedId");
            if (fingerprint.isEmpty() != id.isEmpty() || account.getLong("lastCompletedAt") < 0) throw new IllegalStateException("Incomplete backup acknowledgement");
            if (!fingerprint.isEmpty()) { requireHash(fingerprint); requireUuid(id); }
            JSONObject job = account.optJSONObject("job");
            if (account.has("job") && job == null) throw new IllegalStateException("Invalid backup job");
            if (job != null) {
                requireUuid(job.getString("backupId")); requireHash(job.getString("fingerprint")); requirePath(job.getString("snapshotPath"));
                if (job.getLong("createdAt") < 0 || job.getInt("attempts") < 0 || job.getLong("retryAt") < 0)
                    throw new IllegalStateException("Invalid backup retry metadata");
                String status = job.getString("status");
                if (!"pending".equals(status) && !"uploading".equals(status) && !"failed".equals(status)
                        && !"auth".equals(status) && !"conflict".equals(status) && !"permanent".equals(status))
                    throw new IllegalStateException("Invalid backup status");
            }
        }
        Set<String> identities = new HashSet<>(); Iterator<String> claimKeys = claims.keys();
        while (claimKeys.hasNext()) {
            String key = claimKeys.next(); JSONObject claim = claims.getJSONObject(key);
            String owner = claim.getString("owner"), kind = claim.getString("kind"), remote = claim.getString("remoteId");
            long localId = claim.getLong("localId"); requireUuid(owner); requireKind(kind); requireUuid(remote); requireLocalId(localId);
            if (!key.equals(localKey(kind, localId)) || !identities.add(owner + ":" + remoteKey(kind, remote)))
                throw new IllegalStateException("Duplicate or invalid backup ownership");
            String imported = claim.getString("importedFingerprint"); if (!imported.isEmpty()) requireHash(imported);
        }
        Iterator<String> restoreKeys = restores.keys();
        while (restoreKeys.hasNext()) {
            String key = restoreKeys.next(); JSONObject restore = restores.getJSONObject(key);
            String owner = restore.getString("owner"), id = restore.getString("backupId"); requireUuid(owner); requireUuid(id);
            requireHash(restore.getString("fingerprint")); String status = restore.getString("status");
            if (!key.equals(restoreKey(owner, id)) || (!"pending".equals(status) && !"complete".equals(status)))
                throw new IllegalStateException("Invalid restore journal");
            JSONObject entries = restore.getJSONObject("entries"); Iterator<String> entryKeys = entries.keys();
            while (entryKeys.hasNext()) {
                String entryKey = entryKeys.next(); JSONObject entry = entries.getJSONObject(entryKey);
                String kind = entry.getString("kind"), remote = entry.getString("remoteId"); requireKind(kind); requireUuid(remote);
                long localId = entry.getLong("localId"); boolean imported = entry.getBoolean("imported");
                if (!entryKey.equals(remoteKey(kind, remote)) || localId < 0) throw new IllegalStateException("Invalid restore reservation");
                if (localId > 0) {
                    JSONObject claim = claims.optJSONObject(localKey(kind, localId));
                    if (claim == null || !owner.equals(claim.getString("owner")) || !remote.equals(claim.getString("remoteId")))
                        throw new IllegalStateException("Unowned restore reservation");
                }
                String content = entry.getString("contentFingerprint");
                if (imported) { if (localId == 0) throw new IllegalStateException("Incomplete imported identity"); requireHash(content); }
                else if (!content.isEmpty() || "complete".equals(status)) throw new IllegalStateException("Incomplete restore acknowledgement");
            }
        }
    }
}
