package com.fiskentra.app.backend;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import com.fiskentra.app.FiskentraApplication;
import com.fiskentra.app.data.PointLedger;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Full versioned backups. A manifest is published only after every original byte is verified. */
public final class AccountBackupManager {
    public interface Listener { void onResult(boolean complete, String message); }
    public interface HistoryListener { void onResult(JSONArray rows, String error); }
    public interface Observer { void onBackupChanged(); }
    private final Context context;
    private final SupabaseAuthManager auth;
    private final AccountBackupLedger ledger;
    private final BackupLocalStore local;
    private final AccountBackupTransport remote;
    private final File directory, staging, restores;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Set<Observer> observers = new CopyOnWriteArraySet<>();
    // Objects cannot be overwritten/deleted by clients. A failed commit invalidates this cache.
    private final Set<String> verifiedUploads = new HashSet<>();
    private volatile String messageOwner = "", message = "";
    private volatile boolean busy;
    private final java.util.concurrent.atomic.AtomicBoolean changeScheduled = new java.util.concurrent.atomic.AtomicBoolean();

    public static AccountBackupManager get(Context context) {
        return ((FiskentraApplication) context.getApplicationContext()).getBackupManager();
    }
    public AccountBackupManager(Context context) {
        this.context = context.getApplicationContext();
        auth = ((FiskentraApplication) this.context).getAuthManager();
        directory = new File(this.context.getFilesDir(), "full-backup-v1");
        staging = new File(directory, "staging"); restores = new File(directory, "restore");
        ledger = new AccountBackupLedger(new PointLedger.AtomicDisk(new File(this.context.getFilesDir(), "account-backups-v1.json")));
        local = new BackupLocalStore(this.context, ledger); remote = new AccountBackupTransport(auth);
        worker.scheduleWithFixedDelay(this::automatic, 5, 60, TimeUnit.SECONDS);
        ConnectivityManager manager = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager != null) try {
            manager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) worker.execute(AccountBackupManager.this::automatic);
                }
            });
        } catch (RuntimeException ignored) { /* The periodic retry remains available. */ }
    }
    private String owner() { SupabaseAuthManager.Session session = auth.session(); return session == null ? "" : session.userId; }
    private void guard(String owner) throws IOException {
        if (owner.isEmpty() || !auth.isCurrentAccount(owner)) throw new IOException("Account changed; backup or restore paused");
        if (!ledger.available()) throw new IOException("Backup journal needs recovery; original data is retained");
    }
    public void addObserver(Observer observer) { if (observer != null) observers.add(observer); }
    public void removeObserver(Observer observer) { observers.remove(observer); }
    public boolean enabled() { return ledger.enabled(owner()); }
    public void localDataChanged() {
        String owner = owner();
        if (owner.isEmpty() || !ledger.enabled(owner)) return;
        publish(owner, "New changes waiting for full backup · previous completed copies retained");
        if (changeScheduled.compareAndSet(false, true)) worker.schedule(() -> {
            changeScheduled.set(false); automatic();
        }, 2, TimeUnit.SECONDS);
    }
    public String status() {
        String owner = owner();
        if (!ledger.available()) return "Full backup unavailable · original files retained";
        if (owner.isEmpty()) return "Sign in to back up photos and trips";
        if (messageOwner.equals(owner) && !message.isEmpty()) return message;
        if (!ledger.enabled(owner)) return "Full backup not enabled · photos and trips on this phone";
        AccountBackupLedger.Job pending = ledger.pending(owner);
        if (pending != null) return online() ? "Full backup pending · retry available" : "Full backup queued · waiting for internet";
        if (ledger.lastCompletedAt(owner) > 0) return "Full backup completed · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(ledger.lastCompletedAt(owner)));
        return "Full backup enabled · preparing first copy";
    }
    public void backup(Listener listener) {
        final String owner = owner();
        worker.execute(() -> {
            try {
                guard(owner); ledger.enable(owner); ledger.retry(owner);
                busy = true; boolean complete = cycle(owner);
                deliver(listener, complete, status());
            } catch (Exception failure) { failure(owner, failure); deliver(listener, false, status()); }
            finally { busy = false; notifyObservers(); }
        });
    }
    public void pause(Listener listener) {
        final String owner = owner(); worker.execute(() -> {
            try { guard(owner); ledger.disable(owner); publish(owner, "Automatic full backups paused · existing cloud copies retained"); deliver(listener, true, status()); }
            catch (Exception failure) { failure(owner, failure); deliver(listener, false, status()); }
        });
    }
    public void list(String beforeCreatedAt, String beforeId, HistoryListener listener) {
        final String owner = owner(); worker.execute(() -> {
            try { guard(owner); JSONArray rows = remote.list(owner, beforeCreatedAt, beforeId, 30); guard(owner); listener.onResult(rows, ""); }
            catch (Exception failure) { listener.onResult(new JSONArray(), safeMessage(failure)); }
        });
    }
    public void restore(String backupId, Listener listener) {
        final String owner = owner(); worker.execute(() -> {
            File restoreDirectory = null;
            try {
                guard(owner); UUID.fromString(backupId); busy = true;
                publish(owner, "Downloading backup · local data has not been replaced");
                JSONObject manifest = remote.manifest(owner, backupId); BackupFiles.validateManifest(manifest); guard(owner);
                restoreDirectory = new File(new File(restores, owner), backupId); ensureInside(restores, restoreDirectory);
                File chunks = new File(restoreDirectory, "chunks"); if (!chunks.isDirectory() && !chunks.mkdirs()) throw new IOException("Not enough restore storage");
                Map<String, File> downloaded = new HashMap<>();
                JSONArray inventory = manifest.getJSONArray("blobs");
                for (int i = 0; i < inventory.length(); i++) {
                    guard(owner); JSONObject blob = inventory.getJSONObject(i); String sha = blob.getString("sha256"); long bytes = blob.getLong("bytes");
                    File file = new File(chunks, sha); remote.getBlob(owner, sha, bytes, file); downloaded.put(sha, file);
                    publish(owner, "Downloading backup files · " + (i + 1) + "/" + inventory.length());
                }
                BackupFiles.ChunkSource resolver = (sha, bytes) -> {
                    File file = downloaded.get(sha); if (file == null) throw new IOException("Backup is incomplete; originals retained");
                    BackupFiles.verify(file, sha, bytes); return file;
                };
                File snapshotFile = new File(restoreDirectory, "snapshot.json");
                BackupFiles.assemble(manifest.getJSONObject("snapshot"), snapshotFile, resolver);
                JSONObject data = BackupFiles.readJson(snapshotFile, BackupFiles.MAX_SNAPSHOT_BYTES);
                validateSnapshotInventory(owner, manifest, data);
                Map<String, File> photos = new HashMap<>(); JSONArray descriptors = manifest.getJSONArray("photos");
                for (int i = 0; i < descriptors.length(); i++) {
                    guard(owner); JSONObject descriptor = descriptors.getJSONObject(i); String sha = descriptor.getString("sha256");
                    File file = new File(new File(restoreDirectory, "photos"), sha);
                    BackupFiles.assemble(descriptor, file, resolver); photos.put(sha, file);
                }
                // All downloads/lengths/checksums are complete before the first local import.
                guard(owner); publish(owner, "Restoring verified photos and trips · existing edits are protected");
                BackupLocalStore.RestoreResult result = local.restore(owner, backupId, data, photos); guard(owner);
                String summary = result.complete ? "Backup restored · " + result.points + " points, " + result.photos + " photos, " + result.trips + " trips"
                        : "Restore kept existing data · " + result.conflicts + " conflicts need attention; cloud copy retained";
                publish(owner, summary); deliver(listener, result.complete, summary);
                if (result.complete) removeInside(restores, restoreDirectory);
            } catch (Exception failure) { failure(owner, failure); deliver(listener, false, status()); }
            finally { busy = false; notifyObservers(); }
        });
    }
    private void automatic() {
        String owner = owner();
        if (busy || owner.isEmpty() || !ledger.available() || !ledger.enabled(owner) || !online()) return;
        try { busy = true; cycle(owner); } catch (Exception failure) { failure(owner, failure); }
        finally { busy = false; notifyObservers(); }
    }
    private boolean cycle(String owner) throws Exception {
        guard(owner);
        AccountBackupLedger.Job pending = ledger.pending(owner);
        if (pending != null && !send(owner, pending)) return false;
        guard(owner); publish(owner, "Checking original photos and recorded trips");
        BackupLocalStore.Snapshot snapshot = local.capture(owner); guard(owner);
        String fingerprint = BackupCodec.fingerprint(snapshot.data);
        if (!ledger.needsBackup(owner, fingerprint)) {
            publish(owner, snapshot.excludedForeign > 0 ? "Account backup up to date · other-account items stayed separate"
                    : "Full backup up to date · originals, routes and point links included"); return true;
        }
        File path = stage(owner, snapshot, fingerprint); guard(owner);
        AccountBackupLedger.Job request = ledger.claim(owner, fingerprint, path.getAbsolutePath(), System.currentTimeMillis());
        if (request == null) { removeInside(staging, path.getParentFile()); return false; }
        if (!online()) { publish(owner, "Full backup queued offline · cloud copy is not complete yet"); return false; }
        boolean complete = sendClaimed(owner, request);
        if (complete && snapshot.excludedForeign > 0) publish(owner, "Account backup complete · other-account items stayed separate");
        return complete;
    }
    private File stage(String owner, BackupLocalStore.Snapshot snapshot, String fingerprint) throws Exception {
        File area = new File(staging, UUID.randomUUID().toString()); ensureInside(staging, area);
        if (!area.isDirectory() && !area.mkdirs()) throw new IOException("Not enough backup storage");
        try {
        byte[] serialized = CloudPointCodec.canonical(snapshot.data).getBytes(StandardCharsets.UTF_8);
        if (serialized.length > BackupFiles.MAX_SNAPSHOT_BYTES) throw new IOException("Trip metadata exceeds the supported backup size; local data retained");
        File snapshotFile = new File(area, "snapshot.json"); BackupFiles.write(snapshotFile, serialized);
        Map<String, File> blobs = new TreeMap<>(); File chunks = new File(area, "blobs");
        JSONObject snapshotDescriptor = BackupFiles.chunk(snapshotFile, fingerprint, chunks, blobs);
        JSONArray photos = new JSONArray();
        for (Map.Entry<String, File> photo : new TreeMap<>(snapshot.photos).entrySet()) {
            guard(owner); photos.put(BackupFiles.chunk(photo.getValue(), photo.getKey(), chunks, blobs));
        }
        JSONArray inventory = new JSONArray();
        for (Map.Entry<String, File> blob : blobs.entrySet()) inventory.put(new JSONObject().put("sha256", blob.getKey()).put("bytes", blob.getValue().length()));
        JSONObject counts = new JSONObject().put("points", snapshot.pointCount).put("trips", snapshot.tripCount).put("photos", snapshot.photos.size());
        JSONObject manifest = new JSONObject().put("schema", 1).put("source_id", ledger.actorId()).put("captured_at", System.currentTimeMillis())
                .put("device_label", (Build.MANUFACTURER + " " + Build.MODEL).substring(0, Math.min(128, (Build.MANUFACTURER + " " + Build.MODEL).length())))
                .put("snapshot", snapshotDescriptor).put("photos", photos).put("blobs", inventory).put("counts", counts);
        BackupFiles.validateManifest(manifest); validateSnapshotInventory(owner, manifest, snapshot.data);
        File result = new File(area, "manifest.json"); BackupFiles.write(result, manifest.toString().getBytes(StandardCharsets.UTF_8)); return result;
        } catch (Exception failure) {
            try { removeInside(staging, area); } catch (Exception ignored) { }
            throw failure;
        }
    }
    private boolean send(String owner, AccountBackupLedger.Job pending) throws Exception {
        guard(owner);
        if (!online()) { publish(owner, "Full backup queued · waiting for internet"); return false; }
        AccountBackupLedger.Job request = ledger.claim(owner, pending.fingerprint, pending.snapshotPath, System.currentTimeMillis());
        if (request == null) { publish(owner, "Full backup pending · retry or sign in again if required"); return false; }
        return sendClaimed(owner, request);
    }
    private boolean sendClaimed(String owner, AccountBackupLedger.Job request) throws Exception {
        String phase = "read_staged_manifest";
        try {
            guard(owner); File path = new File(request.snapshotPath); ensureInside(staging, path);
            JSONObject manifest = BackupFiles.readJson(path, BackupFiles.MAX_MANIFEST_BYTES); BackupFiles.validateManifest(manifest);
            if (!request.fingerprint.equals(manifest.getJSONObject("snapshot").getString("sha256"))
                    || !request.actorId.equals(manifest.getString("source_id"))) throw new IOException("Staged backup identity changed; originals retained");
            JSONObject stagedData = BackupFiles.verifiedSnapshot(new File(path.getParentFile(), "snapshot.json"), manifest, owner);
            validateSnapshotInventory(owner, manifest, stagedData);
            phase = "storage_configuration"; remote.verifyBucket(owner);
            JSONArray blobs = manifest.getJSONArray("blobs");
            for (int i = 0; i < blobs.length(); i++) {
                guard(owner); JSONObject descriptor = blobs.getJSONObject(i); String sha = descriptor.getString("sha256");
                File file = new File(new File(path.getParentFile(), "blobs"), sha); BackupFiles.verify(file, sha, descriptor.getLong("bytes"));
                String cacheKey = owner + ":" + sha;
                if (!verifiedUploads.contains(cacheKey)) { phase = "upload_and_verify_chunk"; remote.putBlob(owner, sha, file); verifiedUploads.add(cacheKey); }
                publish(owner, "Uploading verified backup files · " + (i + 1) + "/" + blobs.length());
            }
            phase = "publish_manifest"; guard(owner); remote.commit(owner, request.backupId, manifest); guard(owner);
            phase = "verify_manifest";
            JSONObject confirmed = remote.manifest(owner, request.backupId);
            if (!CloudPointCodec.canonical(manifest).equals(CloudPointCodec.canonical(confirmed))) throw new IOException("Cloud backup confirmation did not match; local copy retained");
            ledger.finish(request, true, "", System.currentTimeMillis());
            publish(owner, "Full backup complete · " + manifest.getJSONObject("counts").getInt("photos") + " photos and " + manifest.getJSONObject("counts").getInt("trips") + " trips verified");
            try { removeInside(staging, path.getParentFile()); } catch (Exception ignored) { /* Cloud ACK is durable; private cache cleanup is not a failed backup. */ }
            return true;
        } catch (Exception failure) {
            verifiedUploads.clear();
            String category = failure instanceof AccountBackupTransport.Failure ? ((AccountBackupTransport.Failure) failure).category : "failed";
            if (com.fiskentra.app.BuildConfig.DEBUG) {
                AccountBackupTransport.Failure remoteFailure = failure instanceof AccountBackupTransport.Failure ? (AccountBackupTransport.Failure) failure : null;
                timber.log.Timber.tag("FiskentraBackup").w("Failed phase=%s category=%s http=%d code=%s", phase, category,
                        remoteFailure == null ? 0 : remoteFailure.httpStatus, remoteFailure == null ? "" : remoteFailure.serverCode);
            }
            ledger.finish(request, false, category, System.currentTimeMillis()); throw failure;
        }
    }
    private static void validateSnapshotInventory(String owner, JSONObject manifest, JSONObject data) throws Exception {
        if (data.getInt("schema") != 1 || !owner.equals(data.getString("owner_id"))) throw new IOException("Backup belongs to another account or unsupported version");
        Map<String, Long> declared = new HashMap<>(), found = new HashMap<>();
        JSONArray photos = manifest.getJSONArray("photos"), actual = data.getJSONArray("photos");
        for (int i = 0; i < photos.length(); i++) declared.put(photos.getJSONObject(i).getString("sha256"), photos.getJSONObject(i).getLong("bytes"));
        for (int i = 0; i < actual.length(); i++) {
            JSONObject photo = actual.getJSONObject(i);
            if (found.put(photo.getString("sha256"), photo.getLong("bytes")) != null) throw new IOException("Duplicate snapshot photo");
        }
        JSONObject counts = manifest.getJSONObject("counts");
        if (!declared.equals(found) || counts.getInt("points") != data.getJSONArray("points").length()
                || counts.getInt("trips") != data.getJSONArray("trips").length()) throw new IOException("Backup inventory does not match its data");
    }
    private boolean online() {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network network = manager.getActiveNetwork(); NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
    private void failure(String owner, Exception failure) { publish(owner, safeMessage(failure)); }
    private static String safeMessage(Exception failure) {
        if (failure instanceof AccountBackupTransport.Failure) return failure.getMessage();
        String text = failure.getMessage();
        // I/O/parser messages can contain private paths or payloads. Return only our controlled text.
        if (failure instanceof IOException && text != null && !text.contains("/") && !text.contains("\\") && text.length() <= 180) return text;
        return "Full backup or restore interrupted · original data retained; retry to continue";
    }
    private void publish(String owner, String text) { messageOwner = owner; message = text; notifyObservers(); }
    private void notifyObservers() { for (Observer observer : observers) try { observer.onBackupChanged(); } catch (RuntimeException ignored) { } }
    private static void deliver(Listener listener, boolean complete, String text) { if (listener != null) listener.onResult(complete, text); }
    private static void ensureInside(File root, File file) throws Exception {
        String base = root.getCanonicalPath() + File.separator;
        if (!file.getCanonicalPath().startsWith(base)) throw new IOException("Invalid private backup staging path");
    }
    private static void removeInside(File root, File file) throws Exception {
        ensureInside(root, file);
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) for (File child : children) removeInside(root, child);
        if (file.exists() && !file.delete()) throw new IOException("Backup completed; temporary files need cleanup");
    }
}
