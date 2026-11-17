package com.fiskentra.app.backend;

import android.content.Context;
import com.fiskentra.app.data.FishingDayStore;
import com.fiskentra.app.data.PointLedger;
import com.fiskentra.app.data.PointStore;
import com.fiskentra.app.data.TrackStore;
import com.fiskentra.app.model.FishingDay;
import com.fiskentra.app.model.SavedPoint;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict capture and additive, crash-retry-safe import of user records and original photo bytes. */
public final class BackupLocalStore {
    public interface PointIdentities {
        String prepare(String owner, SavedPoint point);
        String owner(long localId);
        boolean current(String owner);
    }
    public static final class Snapshot {
        public final JSONObject data;
        public final Map<String, File> photos;
        public final int pointCount, tripCount, photoCount, excludedForeign;
        public final long photoBytes;
        Snapshot(JSONObject data, Map<String, File> photos, int excluded) throws Exception {
            this.data = data; this.photos = Collections.unmodifiableMap(new LinkedHashMap<>(photos));
            pointCount = data.getJSONArray("points").length(); tripCount = data.getJSONArray("trips").length();
            photoCount = photos.size(); excludedForeign = excluded;
            long bytes = 0; JSONArray rows = data.getJSONArray("photos");
            for (int i = 0; i < rows.length(); i++) bytes = Math.addExact(bytes, rows.getJSONObject(i).getLong("bytes"));
            photoBytes = bytes;
        }
    }
    public static final class RestoreResult {
        public final int points, trips, photos, conflicts;
        public final boolean complete;
        RestoreResult(int points, int trips, int photos, int conflicts, boolean complete) {
            this.points = points; this.trips = trips; this.photos = photos; this.conflicts = conflicts; this.complete = complete;
        }
    }
    private final File filesDir;
    private final PointLedger points;
    private final FishingDayStore trips;
    private final TrackStore track;
    private final AccountBackupLedger registry;
    private final PointIdentities pointIdentities;

    public BackupLocalStore(Context context, AccountBackupLedger registry) {
        this(context.getApplicationContext().getFilesDir(), new PointStore(context).ledger(),
                new FishingDayStore(context), new TrackStore(context), registry, androidIdentities(context));
    }

    private static PointIdentities androidIdentities(Context context) {
        Context app = context.getApplicationContext();
        return new PointIdentities() {
            @Override public String prepare(String owner, SavedPoint point) { return PointSyncQueue.get(app).prepareBackupPointId(owner, point); }
            @Override public String owner(long id) { return PointSyncQueue.get(app).backupOwnerOf(id); }
            @Override public boolean current(String owner) { return PointSyncQueue.get(app).isBackupAccountCurrent(owner); }
        };
    }

    /** Dependency injection keeps the exact importer testable with isolated disks/preferences. */
    public BackupLocalStore(File filesDir, PointLedger points, FishingDayStore trips, TrackStore track,
            AccountBackupLedger registry, PointIdentities pointIdentities) {
        this.filesDir = filesDir; this.points = points; this.trips = trips; this.track = track;
        this.registry = registry; this.pointIdentities = pointIdentities;
    }

    public Snapshot capture(String owner) throws Exception {
        BackupCodec.uuid(owner);
        requireCurrent(owner);
        if (!registry.available()) throw new IllegalStateException("Backup ownership journal needs recovery");
        PointLedger.Snapshot source = points.snapshot();
        List<FishingDay> days = trips.allForBackup();
        JSONObject liveTrack = track.snapshotForBackup();
        Set<Long> privateTripIds = new HashSet<>();
        for (SavedPoint point : source.points) if (foreignPoint(owner, point) && point.tripId > 0) privateTripIds.add(point.tripId);
        if (liveTrack != null) {
            long started = liveTrack.getLong("started_at");
            for (FishingDay day : days) if (privateTripIds.contains(day.id) && started >= day.startedAt
                    && (day.endedAt == 0 || started <= day.endedAt)) privateTripIds.add(started);
        }
        JSONArray tripRows = new JSONArray(), pointRows = new JSONArray(), photoRows = new JSONArray();
        Map<Long, String> tripKeys = new HashMap<>();
        Map<String, File> photoFiles = new LinkedHashMap<>();
        Map<String, Long> photoSizes = new LinkedHashMap<>();
        int excluded = 0;
        for (FishingDay day : days) {
            requireCurrent(owner);
            String[] recovered = recoveredIdentity(day.cloudIdentity);
            String kind = recovered == null ? "trip" : recovered[1];
            if ((recovered != null && !owner.equals(recovered[0])) || foreignClaim(owner, kind, day.id)
                    || privateTripIds.contains(day.id)) { excluded++; continue; }
            String key = registry.identity(owner, kind, day.id, recovered == null ? "" : recovered[2]);
            if (key.isEmpty()) { excluded++; continue; }
            JSONObject row = BackupCodec.trip(day).put("key", key).put("kind", kind);
            tripRows.put(row); tripKeys.put(day.id, key);
        }
        if (liveTrack != null) {
            requireCurrent(owner);
            long id = liveTrack.getLong("started_at");
            if (foreignClaim(owner, "track", id) || privateTripIds.contains(id)) excluded++;
            else {
                // The latest TrackStore is often also archived as a FishingDay. Exact equality
                // preserves its identity/associations while avoiding a second visible copy.
                String duplicate = matchingTrip(tripRows, liveTrack);
                if (duplicate != null) tripKeys.putIfAbsent(id, duplicate);
                else {
                    String key = registry.identity(owner, "track", id, "");
                    if (key.isEmpty()) excluded++;
                    else { tripRows.put(new JSONObject(liveTrack.toString()).put("key", key).put("kind", "track")); tripKeys.putIfAbsent(id, key); }
                }
            }
        }
        for (SavedPoint point : source.points) {
            requireCurrent(owner);
            if (foreignPoint(owner, point) || foreignClaim(owner, "point", point.id)) { excluded++; continue; }
            String preferred = pointIdentities.prepare(owner, point);
            if (preferred.isEmpty()) { excluded++; continue; }
            String key = registry.identity(owner, "point", point.id, preferred);
            if (key.isEmpty()) { excluded++; continue; }
            JSONObject row = new JSONObject().put("key", key).put("point", BackupCodec.point(point));
            String tripKey = tripKeys.get(point.tripId);
            if (tripKey != null) row.put("trip_key", tripKey);
            if (point.catchDetails != null && !point.catchDetails.localPhotoPath.isEmpty()) {
                File file = sourcePhoto(point.catchDetails.localPhotoPath);
                long size = file.length();
                if (size <= 0) throw new IllegalStateException("A photo is empty; backup was not published");
                String hash = BackupCodec.digest(file);
                if (!file.isFile() || file.length() != size) throw new IllegalStateException("A photo changed during backup; retrying is safe");
                row.put("photo_sha256", hash); photoFiles.put(hash, file); photoSizes.put(hash, size);
            }
            pointRows.put(row);
        }
        List<String> photoKeys = new ArrayList<>(photoFiles.keySet()); Collections.sort(photoKeys);
        for (String key : photoKeys) photoRows.put(new JSONObject().put("sha256", key).put("bytes", photoSizes.get(key)));
        JSONObject data = new JSONObject().put("schema", 1).put("owner_id", owner).put("points", sorted(pointRows))
                .put("trips", sorted(tripRows)).put("photos", photoRows);
        validate(owner, data);
        requireCurrent(owner);
        if (source.revision != points.snapshot().revision) throw new IllegalStateException("Points changed during backup; retrying is safe");
        return new Snapshot(data, photoFiles, excluded);
    }

    public RestoreResult restore(String owner, String backupId, JSONObject data, Map<String, File> verifiedPhotos) throws Exception {
        BackupCodec.uuid(owner); BackupCodec.uuid(backupId); validate(owner, data);
        requireCurrent(owner);
        if (!registry.available()) throw new IllegalStateException("Backup ownership journal needs recovery");
        // Validate the entire recovery set before touching any record. No missing-photo partial
        // import can be shown as successful, and corrupted downloads cannot replace real files.
        Map<String, String> photoPaths = installPhotos(owner, data.getJSONArray("photos"), verifiedPhotos);
        String fingerprint = BackupCodec.fingerprint(data);
        registry.beginRestore(owner, backupId, fingerprint);
        Map<String, Long> restoredTrips = new HashMap<>();
        Set<String> restoredPhotos = new HashSet<>();
        int restoredPointCount = 0, restoredTripCount = 0, conflicts = 0;
        JSONArray tripRows = data.getJSONArray("trips");
        for (int i = 0; i < tripRows.length(); i++) {
            requireCurrent(owner);
            JSONObject row = tripRows.getJSONObject(i); String key = row.getString("key"), kind = row.getString("kind");
            registry.reserveRestoreRecord(owner, backupId, kind, key, 0);
            String identity = "backup:" + owner + ":" + kind + ":" + key;
            FishingDay existing = trips.findCloudIdentity(identity);
            long mapped = registry.localId(owner, kind, key);
            if (existing == null && mapped > 0 && "trip".equals(kind)) existing = trips.findForBackup(mapped);
            if (existing == null && "track".equals(kind)) {
                JSONObject current = track.snapshotForBackup();
                if (current != null && mapped == current.getLong("started_at")) {
                    if (sameTrip(current, row)) {
                        registry.markRestored(owner, backupId, kind, key, mapped, BackupCodec.fingerprint(row));
                        restoredTrips.put(key, mapped); restoredTripCount++;
                    } else { conflicts++; restoredTrips.put(key, mapped); }
                    continue;
                }
            }
            if (existing != null) {
                if (sameTrip(BackupCodec.trip(existing), row)) {
                    registry.markRestored(owner, backupId, kind, key, existing.id, BackupCodec.fingerprint(row));
                    restoredTripCount++;
                } else conflicts++;
                restoredTrips.put(key, existing.id);
                continue;
            }
            FishingDay imported = trips.importRecovered(BackupCodec.trip(row), identity,
                    row.getBoolean("was_active"), row.optBoolean("was_paused"));
            registry.markRestored(owner, backupId, kind, key, imported.id, BackupCodec.fingerprint(row));
            restoredTrips.put(key, imported.id); restoredTripCount++;
        }
        JSONArray pointRows = data.getJSONArray("points");
        for (int i = 0; i < pointRows.length(); i++) {
            requireCurrent(owner);
            synchronized (points) {
            JSONObject row = pointRows.getJSONObject(i); String key = row.getString("key");
            registry.reserveRestoreRecord(owner, backupId, "point", key, 0);
            String event = CloudPointCodec.eventId(owner, key);
            SavedPoint existing = points.findEvent(event);
            long mapped = registry.localId(owner, "point", key);
            if (existing == null && mapped > 0 && points.isDeleted(mapped)) { conflicts++; continue; }
            if (existing == null && mapped > 0) existing = points.find(mapped);
            String photo = row.optString("photo_sha256"), tripKey = row.optString("trip_key");
            long tripId = tripKey.isEmpty() ? 0 : restoredTrips.getOrDefault(tripKey, 0L);
            if (!tripKey.isEmpty() && tripId == 0) { conflicts++; continue; }
            String path = photo.isEmpty() ? "" : photoPaths.get(photo);
            if (!photo.isEmpty()) {
                File installed = new File(path);
                // Photo cleanup uses this same record monitor. If a prior cleanup removed an
                // unreferenced staged target, repair it before atomically attaching the reference.
                if (!installed.isFile() || !photo.equals(BackupCodec.digest(installed))) {
                    JSONObject descriptor = photoDescriptor(data, photo);
                    photoPaths.putAll(installPhotos(owner, new JSONArray().put(descriptor), verifiedPhotos));
                    path = photoPaths.get(photo);
                }
            }
            SavedPoint restored;
            if (existing == null) {
                restored = points.insert(BackupCodec.point(owner, key, row.getJSONObject("point"), 1, tripId, path));
                if (restored == null) { conflicts++; continue; } // A durable local deletion receipt wins.
            } else {
                if (foreignPoint(owner, existing) || foreignClaim(owner, "point", existing.id)
                        || !CloudPointCodec.canonical(BackupCodec.point(existing)).equals(CloudPointCodec.canonical(row.getJSONObject("point")))) {
                    conflicts++; continue;
                }
                String existingPath = existing.catchDetails == null ? "" : existing.catchDetails.localPhotoPath;
                if (photo.isEmpty() && !existingPath.isEmpty()) { conflicts++; continue; }
                if (!photo.isEmpty() && !existingPath.isEmpty() && new File(existingPath).isFile()
                        && !photo.equals(BackupCodec.digest(sourcePhoto(existingPath)))) { conflicts++; continue; }
                if (existing.tripId != 0 && existing.tripId != tripId) { conflicts++; continue; }
                boolean needsPhoto = !photo.isEmpty() && (existingPath.isEmpty() || !new File(existingPath).isFile());
                boolean needsTrip = existing.tripId == 0 && tripId != 0;
                if (needsPhoto || needsTrip) {
                    // Once a full restore established a baseline, a later removal is a local edit,
                    // not permission to silently reattach the old cloud photo or trip.
                    String baseline = registry.importedFingerprint(owner, "point", key);
                    JSONObject currentRow = currentPointRow(existing, key, data);
                    // A surviving photo reference with missing bytes is repairable. An explicit
                    // empty path is a local removal and must remain a conflict against the baseline.
                    if (needsPhoto && !existingPath.isEmpty()) currentRow.put("photo_sha256", photo);
                    if (!baseline.isEmpty() && !baseline.equals(BackupCodec.fingerprint(currentRow))) {
                        conflicts++; continue;
                    }
                    final String finalPath = path; final long finalTrip = tripId;
                    restored = points.editIfRevision(existing.id, existing.revision, p -> {
                        SavedPoint changed = p;
                        if (needsPhoto) changed = changed.withCatchDetails(p.catchDetails.withLocalPhotoPath(finalPath));
                        if (needsTrip) changed = changed.withTripId(finalTrip);
                        return changed;
                    });
                    if (restored == null) { conflicts++; continue; }
                } else restored = existing;
            }
            registry.markRestored(owner, backupId, "point", key, restored.id, BackupCodec.fingerprint(row));
            if (!photo.isEmpty()) restoredPhotos.add(photo);
            restoredPointCount++;
            }
        }
        boolean complete = conflicts == 0;
        requireCurrent(owner);
        if (complete) registry.completeRestore(owner, backupId, fingerprint);
        return new RestoreResult(restoredPointCount, restoredTripCount, restoredPhotos.size(), conflicts, complete);
    }

    private JSONObject currentPointRow(SavedPoint point, String key, JSONObject backup) throws Exception {
        JSONObject row = new JSONObject().put("key", key).put("point", BackupCodec.point(point));
        if (point.catchDetails != null && !point.catchDetails.localPhotoPath.isEmpty() && new File(point.catchDetails.localPhotoPath).isFile())
            row.put("photo_sha256", BackupCodec.digest(sourcePhoto(point.catchDetails.localPhotoPath)));
        for (int i = 0; i < backup.getJSONArray("trips").length(); i++) {
            JSONObject trip = backup.getJSONArray("trips").getJSONObject(i);
            if (registry.localId(backup.getString("owner_id"), trip.getString("kind"), trip.getString("key")) == point.tripId)
                row.put("trip_key", trip.getString("key"));
        }
        return row;
    }

    private boolean foreignPoint(String owner, SavedPoint point) {
        String claim = pointIdentities.owner(point.id);
        if (!claim.isEmpty() && !owner.equals(claim)) return true;
        if (point.eventId.startsWith("cloud:")) {
            String[] parts = point.eventId.split(":", -1);
            return parts.length != 3 || !owner.equals(parts[1]);
        }
        return false;
    }
    private boolean foreignClaim(String owner, String kind, long id) { String claim = registry.owner(kind, id); return !claim.isEmpty() && !owner.equals(claim); }
    private void requireCurrent(String owner) { if (!pointIdentities.current(owner)) throw new IllegalStateException("Account changed; backup or restore paused with local data retained"); }
    private File sourcePhoto(String path) throws Exception {
        File expected = new File(filesDir.getCanonicalFile(), "catch_photos"), root = expected.getCanonicalFile(), file = new File(path).getCanonicalFile();
        if (!expected.equals(root)) throw new IllegalStateException("Photo storage cannot be a symbolic link");
        if (!file.getPath().startsWith(root.getPath() + File.separator) || !file.isFile())
            throw new IllegalStateException("A referenced photo is missing or outside private photo storage");
        BackupCodec.photoFormat(file);
        return file;
    }

    private Map<String, String> installPhotos(String owner, JSONArray descriptors, Map<String, File> sources) throws Exception {
        Map<String, String> installed = new HashMap<>();
        // Hash every file before publishing even the first target photo.
        for (int i = 0; i < descriptors.length(); i++) {
            requireCurrent(owner);
            JSONObject row = descriptors.getJSONObject(i); String hash = row.getString("sha256"); File file = sources.get(hash);
            if (file == null || !file.isFile() || file.length() != row.getLong("bytes") || !hash.equals(BackupCodec.digest(file)))
                throw new IllegalStateException("A backup photo is missing or corrupt; local records were not changed");
            BackupCodec.photoFormat(file);
        }
        File expected = new File(filesDir.getCanonicalFile(), "catch_photos"), directory = expected.getCanonicalFile();
        if (!expected.equals(directory)) throw new IllegalStateException("Unsafe photo directory");
        if (descriptors.length() > 0 && !directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Photo storage is unavailable");
        for (int i = 0; i < descriptors.length(); i++) {
            requireCurrent(owner);
            JSONObject row = descriptors.getJSONObject(i); String hash = row.getString("sha256"); File file = sources.get(hash);
            File target = new File(directory, hash + ".original").getCanonicalFile();
            if (!directory.equals(target.getParentFile())) throw new IllegalStateException("Unsafe restored photo path");
            if (target.exists()) {
                if (!target.isFile() || target.length() != row.getLong("bytes") || !hash.equals(BackupCodec.digest(target)))
                    throw new IllegalStateException("An existing restored photo needs recovery; it was not overwritten");
            } else {
                File pending = File.createTempFile("restore-", ".partial", directory);
                try {
                    try (FileInputStream in = new FileInputStream(file); FileOutputStream out = new FileOutputStream(pending)) {
                        byte[] buffer = new byte[64 * 1024]; int n;
                        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                        out.getFD().sync();
                    }
                    if (pending.length() != row.getLong("bytes") || !hash.equals(BackupCodec.digest(pending))) throw new IllegalStateException("Photo changed during restore");
                    Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } finally { if (pending.exists() && !pending.delete()) pending.deleteOnExit(); }
            }
            installed.put(hash, target.getAbsolutePath());
        }
        return installed;
    }

    private static String[] recoveredIdentity(String identity) {
        if (identity.isEmpty()) return null;
        String[] parts = identity.split(":", -1);
        if (parts.length != 4 || !"backup".equals(parts[0]) || !("trip".equals(parts[2]) || "track".equals(parts[2])))
            throw new IllegalStateException("Invalid restored trip ownership");
        BackupCodec.uuid(parts[1]); BackupCodec.uuid(parts[3]); return new String[]{parts[1], parts[2], parts[3]};
    }
    private static JSONObject tripPayload(JSONObject row) throws Exception { JSONObject result = new JSONObject(row.toString()); result.remove("key"); result.remove("kind"); return result; }
    private static boolean sameTrip(JSONObject a, JSONObject b) throws Exception { return CloudPointCodec.canonical(tripPayload(a)).equals(CloudPointCodec.canonical(tripPayload(b))); }
    private static String matchingTrip(JSONArray trips, JSONObject track) throws Exception {
        for (int i = 0; i < trips.length(); i++) if (sameTrip(trips.getJSONObject(i), track)) return trips.getJSONObject(i).getString("key");
        return null;
    }
    private static JSONArray sorted(JSONArray input) throws Exception {
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < input.length(); i++) rows.add(input.getJSONObject(i));
        rows.sort((a, b) -> a.optString("key").compareTo(b.optString("key")));
        JSONArray result = new JSONArray(); for (JSONObject row : rows) result.put(row); return result;
    }
    private static JSONObject photoDescriptor(JSONObject data, String hash) throws Exception {
        JSONArray photos = data.getJSONArray("photos");
        for (int i = 0; i < photos.length(); i++) if (hash.equals(photos.getJSONObject(i).getString("sha256"))) return photos.getJSONObject(i);
        throw new IllegalArgumentException("Missing restored photo descriptor");
    }

    private static void validate(String owner, JSONObject data) throws Exception {
        if (data.getInt("schema") != 1 || !owner.equals(data.getString("owner_id"))) throw new IllegalArgumentException("Backup belongs to another account or format");
        Set<String> tripKeys = new HashSet<>(), pointKeys = new HashSet<>(), hashes = new HashSet<>(), referenced = new HashSet<>();
        JSONArray trips = data.getJSONArray("trips"), points = data.getJSONArray("points"), photos = data.getJSONArray("photos");
        for (int i = 0; i < trips.length(); i++) {
            JSONObject row = trips.getJSONObject(i); String key = row.getString("key"), kind = row.getString("kind"); BackupCodec.uuid(key);
            if (!("trip".equals(kind) || "track".equals(kind)) || !tripKeys.add(key)) throw new IllegalArgumentException("Invalid backup trip identity");
            BackupCodec.trip(row);
        }
        for (int i = 0; i < photos.length(); i++) {
            JSONObject row = photos.getJSONObject(i); String hash = row.getString("sha256"); BackupCodec.hash(hash);
            if (row.getLong("bytes") <= 0 || !hashes.add(hash)) throw new IllegalArgumentException("Invalid backup photo descriptor");
        }
        for (int i = 0; i < points.length(); i++) {
            JSONObject row = points.getJSONObject(i); String key = row.getString("key"); BackupCodec.uuid(key);
            if (!pointKeys.add(key)) throw new IllegalArgumentException("Duplicate backup point identity");
            BackupCodec.validatePoint(row.getJSONObject("point"));
            if (row.has("trip_key") && !tripKeys.contains(row.getString("trip_key"))) throw new IllegalArgumentException("Missing backup trip association");
            if (row.has("photo_sha256")) {
                String photo = row.getString("photo_sha256"); BackupCodec.hash(photo);
                if (!hashes.contains(photo) || !row.getJSONObject("point").has("catch_details")) throw new IllegalArgumentException("Missing backup photo");
                referenced.add(photo);
            }
        }
        if (!hashes.equals(referenced)) throw new IllegalArgumentException("Unreferenced backup photo descriptor");
    }
}
