package com.fiskentra.app.data;

import com.fiskentra.app.model.CatchDetails;
import com.fiskentra.app.model.SavedPoint;
import com.fiskentra.app.model.WeatherSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** One durable transaction contains visible records, tombstones, and ordered cloud operations.
 * The original preferences are never changed by migration. No Android dependency: recovery tests
 * reopen real files with the exact same implementation used on the device. */
public final class PointLedger {
    public interface Disk { String read() throws IOException; void write(String value) throws IOException; }
    public static final class AtomicDisk implements Disk {
        private final File file;
        public AtomicDisk(File file) { this.file = file; }
        public String read() throws IOException {
            return file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : null;
        }
        public void write(String value) throws IOException {
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create data directory");
            File pending = new File(parent, file.getName() + ".new");
            try (FileOutputStream out = new FileOutputStream(pending)) {
                out.write(value.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            // Do not fall back to deleting the old file before rename: that creates a loss window.
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
    public static final class Snapshot {
        public final long revision;
        public final List<SavedPoint> points;
        Snapshot(long revision, List<SavedPoint> points) {
            this.revision = revision;
            this.points = Collections.unmodifiableList(points);
        }
    }
    public static final class Operation {
        public final String operationId, kind, status, errorCategory, message;
        public final long pointId, revision, retryAt;
        public final int attempts;
        public final SavedPoint point;
        Operation(JSONObject value) throws Exception {
            operationId = value.getString("operationId"); kind = value.getString("kind");
            pointId = value.getLong("pointId"); revision = value.getLong("revision");
            status = value.getString("status"); attempts = value.optInt("attempts");
            retryAt = value.optLong("retryAt"); errorCategory = value.optString("errorCategory");
            message = value.optString("message"); point = decode(value.getJSONObject("point"));
        }
    }
    public interface Edit { SavedPoint apply(SavedPoint point); }
    private final Disk disk;
    private JSONObject state;
    private Snapshot snapshot;
    private final java.util.Map<Long, SavedPoint> pointIndex = new java.util.HashMap<>();
    private final java.util.Map<Long, String> statusIndex = new java.util.HashMap<>();
    private List<Operation> cachedOperations = Collections.emptyList();
    private int cachedPending;

    public PointLedger(Disk disk, String legacyPoints, String legacyTrips, String legacySync) {
        this.disk = disk;
        try {
            String stored = disk.read();
            if (stored == null) {
                JSONObject migrated = migrate(legacyPoints, legacyTrips, legacySync);
                disk.write(migrated.toString());
                state = migrated;
            } else {
                state = new JSONObject(stored);
                if (state.getInt("version") != 1) throw new IOException("Unsupported local data version");
            }
            rebuildSnapshot();
            rebuildOperations();
            // An interrupted request may have reached the server. Keep its original operation ID,
            // retry it before newer operations, and never downgrade attempted status to never sent.
            JSONObject recovery = cloneState(); boolean changed = false;
            for (int i = 0; i < operations(recovery).length(); i++) {
                JSONObject op = operations(recovery).getJSONObject(i);
                if ("in_flight".equals(op.getString("status"))) {
                    op.put("status", "pending").put("retryAt", 0L); changed = true;
                }
            }
            if (changed) commit(recovery);
        } catch (Exception error) { throw failure(error); }
    }

    public synchronized Snapshot snapshot() { return snapshot; }
    public synchronized SavedPoint find(long id) {
        return pointIndex.get(id);
    }
    public synchronized SavedPoint findEvent(String eventId) {
        for (SavedPoint point : snapshot.points) if (point.eventId.equals(eventId)) return point;
        return null;
    }
    public synchronized boolean isDeleted(long id) { return state.optJSONObject("deleted").has(String.valueOf(id)); }

    public synchronized SavedPoint insert(SavedPoint candidate) {
        SavedPoint existing = findEvent(candidate.eventId);
        if (existing != null) return existing;
        try {
            JSONObject next = cloneState();
            // Event receipts survive deletion, so a duplicate callback cannot recreate a point.
            if (next.getJSONObject("events").has(candidate.eventId)) return null;
            long id = Math.max(candidate.id, next.optLong("nextId", 1));
            SavedPoint point = new SavedPoint(id, candidate.latitude, candidate.longitude,
                    candidate.timestamp, candidate.type, candidate.note, candidate.weather,
                    candidate.catchDetails, candidate.title, candidate.symbol, candidate.color,
                    candidate.size, candidate.eventId, candidate.tripId, candidate.favorite, 1,
                    candidate.locationState, candidate.locatedAtUtc, candidate.locationAccuracy,
                    candidate.locationSource);
            next.put("nextId", id + 1);
            next.getJSONObject("events").put(point.eventId, id);
            next.getJSONObject("points").put(String.valueOf(id), encode(point));
            if (point.hasLocation()) append(next, point, "upsert");
            bumpData(next); commit(next); return find(id);
        } catch (Exception error) { throw failure(error); }
    }

    public synchronized SavedPoint edit(long id, Edit edit) {
        SavedPoint old = find(id);
        if (old == null || isDeleted(id)) return null;
        try {
            SavedPoint point = edit.apply(old).withRevision(old.revision + 1);
            if (point.id != old.id || !point.eventId.equals(old.eventId) || point.timestamp != old.timestamp)
                throw new IllegalArgumentException("An edit cannot replace event identity or time");
            JSONObject next = cloneState();
            next.getJSONObject("points").put(String.valueOf(id), encode(point));
            // Coalesce requests that certainly never left this device. An in-flight/retry request
            // retains its place before the edit/delete that follows it.
            skipNeverSent(next, id);
            if (point.hasLocation()) append(next, point, "upsert");
            bumpData(next); commit(next); return find(id);
        } catch (Exception error) { throw failure(error); }
    }

    public synchronized boolean delete(long id) {
        if (isDeleted(id)) return true;
        SavedPoint point = find(id);
        if (point == null) return true;
        try {
            JSONObject next = cloneState();
            boolean mayExistRemotely = next.getJSONObject("remote").has(String.valueOf(id));
            for (Operation op : operationList()) if (op.pointId == id && op.attempts > 0) mayExistRemotely = true;
            skipNeverSent(next, id);
            next.getJSONObject("deleted").put(String.valueOf(id),
                    new JSONObject().put("eventId", point.eventId).put("revision", point.revision + 1));
            next.getJSONObject("points").remove(String.valueOf(id));
            if (mayExistRemotely) append(next, point.withRevision(point.revision + 1), "delete");
            bumpData(next); commit(next); return true;
        } catch (Exception error) { throw failure(error); }
    }

    /** Claim is persisted before the HTTP request can start. One worker executes globally in order. */
    public synchronized Operation claim(long now) {
        try {
            JSONObject next = cloneState();
            java.util.HashSet<Long> blocked = new java.util.HashSet<>();
            for (int i = 0; i < operations(next).length(); i++) {
                JSONObject op = operations(next).getJSONObject(i);
                String status = op.getString("status");
                if ("done".equals(status) || "superseded".equals(status)) continue;
                long id = op.getLong("pointId");
                if (!blocked.add(id)) continue;
                if ("in_flight".equals(status) || "auth".equals(status) || "permanent".equals(status)
                        || op.optLong("retryAt") > now) continue;
                op.put("status", "in_flight").put("attempts", op.optInt("attempts") + 1);
                commit(next); return new Operation(op);
            }
            return null;
        } catch (Exception error) { throw failure(error); }
    }
    public synchronized void finish(String operationId, boolean success, String category, String message, long now) {
        try {
            JSONObject next = cloneState();
            for (int i = 0; i < operations(next).length(); i++) {
                JSONObject op = operations(next).getJSONObject(i);
                if (!operationId.equals(op.getString("operationId")) || !"in_flight".equals(op.getString("status"))) continue;
                String status = success ? "done" : ("auth".equals(category) ? "auth" : "permanent".equals(category) ? "permanent" : "failed");
                op.put("status", status).put("message", message).put("errorCategory", category)
                        .put("retryAt", success ? 0 : now + backoff(op.optInt("attempts")));
                if (success && "upsert".equals(op.getString("kind")))
                    next.getJSONObject("remote").put(String.valueOf(op.getLong("pointId")), op.getLong("revision"));
                if (success && "delete".equals(op.getString("kind")))
                    next.getJSONObject("deleted").getJSONObject(String.valueOf(op.getLong("pointId"))).put("confirmed", true);
                commit(next); return;
            }
        } catch (Exception error) { throw failure(error); }
    }
    public synchronized void retry() {
        try {
            JSONObject next = cloneState(); boolean changed = false;
            for (int i = 0; i < operations(next).length(); i++) {
                JSONObject op = operations(next).getJSONObject(i);
                if ("failed".equals(op.getString("status")) || "auth".equals(op.getString("status")) || "permanent".equals(op.getString("status"))) {
                    op.put("status", "pending").put("retryAt", 0); changed = true;
                }
            }
            if (changed) commit(next);
        } catch (Exception error) { throw failure(error); }
    }
    public synchronized int pendingCount() {
        return cachedPending;
    }
    public synchronized String status(long id) {
        if (isDeleted(id) && !statusIndex.containsKey(id)) return "deleted";
        String result = statusIndex.containsKey(id) ? statusIndex.get(id)
                : state.optJSONObject("remote").optLong(String.valueOf(id)) > 0 ? "synced" : "local";
        SavedPoint point = find(id);
        if ("synced".equals(result) && point != null
                && state.optJSONObject("remote").optLong(String.valueOf(id)) != point.revision) return "local";
        return result;
    }
    public synchronized List<Operation> operationList() {
        return cachedOperations;
    }
    public static long backoff(int attempts) { return Math.min(15 * 60_000L, 5_000L * (1L << Math.min(8, Math.max(0, attempts - 1)))); }
    private static void skipNeverSent(JSONObject state, long id) throws Exception {
        for (int i = 0; i < operations(state).length(); i++) {
            JSONObject op = operations(state).getJSONObject(i);
            if (op.getLong("pointId") == id && op.optInt("attempts") == 0 && "pending".equals(op.getString("status")))
                op.put("status", "superseded");
        }
    }
    private static void append(JSONObject state, SavedPoint point, String kind) throws Exception {
        operations(state).put(new JSONObject().put("operationId", UUID.randomUUID().toString())
                .put("pointId", point.id).put("revision", point.revision).put("kind", kind)
                .put("status", "pending").put("attempts", 0).put("retryAt", 0)
                .put("errorCategory", "").put("message", "").put("point", encode(point)));
    }
    private static JSONArray operations(JSONObject value) { return value.optJSONArray("operations"); }
    private static void bumpData(JSONObject value) throws Exception { value.put("revision", value.optLong("revision") + 1); }
    private JSONObject cloneState() throws Exception { return new JSONObject(state.toString()); }
    private void commit(JSONObject next) throws Exception {
        // Completed revision receipts live in remote/deleted. Remove redundant payload copies so
        // weather/appearance edits do not grow an unbounded operation history on the phone.
        JSONArray outstanding = new JSONArray();
        for (int i = 0; i < operations(next).length(); i++) {
            JSONObject op = operations(next).getJSONObject(i);
            if (!"done".equals(op.optString("status")) && !"superseded".equals(op.optString("status"))) outstanding.put(op);
        }
        next.put("operations", outstanding);
        disk.write(next.toString()); // On failure neither source of truth nor visible snapshot advances.
        state = next;
        if (snapshot == null || snapshot.revision != next.getLong("revision")) rebuildSnapshot();
        rebuildOperations();
    }
    private void rebuildSnapshot() throws Exception {
        List<SavedPoint> points = new ArrayList<>();
        JSONObject rows = state.getJSONObject("points");
        java.util.Iterator<String> keys = rows.keys();
        while (keys.hasNext()) points.add(decode(rows.getJSONObject(keys.next())));
        points.sort((a, b) -> { int t = Long.compare(b.timestamp, a.timestamp); return t != 0 ? t : Long.compare(b.id, a.id); });
        snapshot = new Snapshot(state.getLong("revision"), points);
        pointIndex.clear(); for (SavedPoint point : points) pointIndex.put(point.id, point);
    }
    private void rebuildOperations() throws Exception {
        List<Operation> result = new ArrayList<>();
        java.util.HashSet<Long> pending = new java.util.HashSet<>(); statusIndex.clear();
        for (int i = 0; i < operations(state).length(); i++) {
            Operation op = new Operation(operations(state).getJSONObject(i)); result.add(op);
            if ("superseded".equals(op.status)) continue;
            String previous = statusIndex.get(op.pointId);
            if (!"done".equals(op.status)) pending.add(op.pointId);
            if ("auth".equals(previous) || "failed".equals(previous) || "delete_failed".equals(previous)) continue;
            String label = "done".equals(op.status) ? "synced" : "auth".equals(op.status) ? "auth"
                    : "failed".equals(op.status) || "permanent".equals(op.status) ? ("delete".equals(op.kind) ? "delete_failed" : "failed")
                    : "delete".equals(op.kind) ? "deleting" : "in_flight".equals(op.status) ? "syncing" : "pending";
            statusIndex.put(op.pointId, label);
        }
        cachedOperations = Collections.unmodifiableList(result); cachedPending = pending.size();
    }
    private static JSONObject migrate(String legacy, String trips, String sync) throws Exception {
        JSONObject state = new JSONObject().put("version", 1).put("revision", 1).put("nextId", 1)
                .put("points", new JSONObject()).put("deleted", new JSONObject()).put("events", new JSONObject())
                .put("remote", new JSONObject()).put("operations", new JSONArray())
                .put("legacyBackup", new JSONObject().put("points", legacy).put("trips", trips).put("sync", sync));
        JSONArray rows = new JSONArray(legacy == null ? "[]" : legacy);
        JSONArray days = new JSONArray(trips == null ? "[]" : trips);
        JSONObject oldSync = new JSONObject(sync == null ? "{}" : sync);
        for (int i = 0; i < rows.length(); i++) {
            SavedPoint point = decode(rows.getJSONObject(i));
            if (point.tripId == 0) {
                long match = 0; int count = 0;
                for (int j = 0; j < days.length(); j++) {
                    JSONObject day = days.getJSONObject(j); long end = day.optLong("ended_at");
                    if (point.timestamp >= day.getLong("started_at") && (end == 0 || point.timestamp < end)) { match = day.getLong("id"); count++; }
                }
                if (count == 1) point = point.withTripId(match);
            }
            state.getJSONObject("points").put(String.valueOf(point.id), encode(point));
            state.getJSONObject("events").put(point.eventId, point.id);
            state.put("nextId", Math.max(state.getLong("nextId"), point.id + 1));
            // Legacy cloud receipts did not prove the current revision/fields. Re-upload core data.
            String previous = oldSync.optString(point.id + "_state");
            if (!previous.isEmpty()) state.getJSONObject("remote").put(String.valueOf(point.id), 0);
            if ("deleting".equals(previous) || "delete_failed".equals(previous)) {
                state.getJSONObject("points").remove(String.valueOf(point.id));
                state.getJSONObject("deleted").put(String.valueOf(point.id),
                        new JSONObject().put("eventId", point.eventId).put("revision", point.revision + 1));
                append(state, point.withRevision(point.revision + 1), "delete");
            } else if (point.hasLocation()) append(state, point, "upsert");
        }
        return state;
    }
    public static JSONObject encode(SavedPoint p) throws Exception {
        JSONObject o = new JSONObject().put("id", p.id).put("time", p.timestamp).put("type", p.type)
                .put("note", p.note).put("title", p.title).put("symbol", p.symbol).put("color", p.color).put("size", p.size)
                .put("eventId", p.eventId).put("tripId", p.tripId).put("favorite", p.favorite).put("revision", p.revision)
                .put("locationState", p.locationState).put("locatedAtUtc", p.locatedAtUtc).put("locationSource", p.locationSource);
        if (p.hasLocation()) o.put("lat", p.latitude).put("lon", p.longitude);
        if (Double.isFinite(p.locationAccuracy)) o.put("locationAccuracy", p.locationAccuracy);
        if (p.weather != null) o.put("weather", p.weather.toJson());
        if (p.catchDetails != null) o.put("catch_details", p.catchDetails.toJson());
        return o;
    }
    public static SavedPoint decode(JSONObject o) throws Exception {
        long id = o.getLong("id"), time = o.getLong("time");
        return new SavedPoint(id, o.optDouble("lat", Double.NaN), o.optDouble("lon", Double.NaN), time,
                o.optString("type", "Moment"), o.optString("note", ""), WeatherSnapshot.fromJson(o.optJSONObject("weather")),
                CatchDetails.fromJson(o.optJSONObject("catch_details")), o.optString("title", ""), o.optString("symbol", ""),
                o.optInt("color", 0), o.optInt("size", 18), o.optString("eventId", "legacy:" + id),
                o.optLong("tripId"), o.optBoolean("favorite"), o.optLong("revision", 1),
                o.optString("locationState", o.has("lat") && o.has("lon") ? "LOCATED" : "UNLOCATED"),
                o.optLong("locatedAtUtc", time), o.optDouble("locationAccuracy", Double.NaN), o.optString("locationSource", "legacy"));
    }
    private static IllegalStateException failure(Exception error) { return new IllegalStateException("Local save failed. Check free storage and try again.", error); }
}
