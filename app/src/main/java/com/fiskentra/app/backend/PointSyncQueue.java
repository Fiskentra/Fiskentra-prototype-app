package com.fiskentra.app.backend;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import com.fiskentra.app.FiskentraApplication;
import com.fiskentra.app.data.PointLedger;
import com.fiskentra.app.data.PointStore;
import com.fiskentra.app.model.SavedPoint;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Application-owned account worker; the legacy anonymous outbox is never sent. */
public final class PointSyncQueue {
    public interface Observer { void onSyncChanged(long pointId, String state, String message, int pendingCount); }
    public static final String PREFS = "fiskentra_point_sync_status";
    public static final String STATE_PENDING = "pending", STATE_SYNCING = "syncing", STATE_SYNCED = "synced",
            STATE_FAILED = "failed", STATE_DELETING = "deleting", STATE_DELETE_FAILED = "delete_failed";
    private final Context context;
    private final PointStore store;
    private final CloudSyncLedger journal;
    private final SupabaseAuthManager auth;
    private final AccountPointTransport remote;
    private final Set<Observer> observers = new CopyOnWriteArraySet<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private volatile String lastOwner = "", lastMessage = "";

    public static PointSyncQueue get(Context context) {
        return ((FiskentraApplication) context.getApplicationContext()).getPointSyncQueue();
    }
    public PointSyncQueue(Context context) {
        this.context = context.getApplicationContext(); store = new PointStore(this.context);
        journal = new CloudSyncLedger(new PointLedger.AtomicDisk(new File(this.context.getFilesDir(), "account-sync-v1.json")));
        auth = ((FiskentraApplication) this.context).getAuthManager(); remote = new AccountPointTransport(auth);
        ConnectivityManager connectivity = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity != null) try {
            connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { kick(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) kick();
                }
            });
        } catch (RuntimeException ignored) { }
        worker.scheduleWithFixedDelay(this::runSafely, 1, 5, TimeUnit.SECONDS);
    }
    private String currentOwner() { SupabaseAuthManager.Session session = auth.session(); return session == null ? "" : session.userId; }
    public boolean hasAccount() { return auth.isSignedIn(); }
    /** Called on the backup worker after explicit full-backup consent. No HTTP or fabricated ACK. */
    public String prepareBackupPointId(String owner, SavedPoint point) {
        if (!journal.available()) throw new IllegalStateException("Point ownership needs recovery before backup");
        if (!auth.isCurrentAccount(owner)) throw new IllegalStateException("Account changed; backup paused");
        String existing = journal.owner(point.id);
        if (!existing.isEmpty()) return owner.equals(existing) ? journal.remoteId(owner, point.id) : "";
        SavedPoint latest = store.find(point.id);
        if (latest == null) return "";
        journal.connect(owner, Collections.singletonList(latest));
        return journal.remoteId(owner, point.id);
    }
    public String backupOwnerOf(long localId) {
        if (!journal.available()) throw new IllegalStateException("Point ownership needs recovery before backup");
        return journal.owner(localId);
    }
    public boolean isBackupAccountCurrent(String owner) { return auth.isCurrentAccount(owner); }
    public boolean transferEnabled() { return CloudDataPolicy.accountSyncAvailable() && journal.connected(currentOwner()); }
    public String accountStatus() {
        if (!journal.available()) return "Cloud queue needs recovery · local points remain available";
        String owner = currentOwner();
        if (owner.isEmpty()) return "Sign in to sync points and details";
        if (!journal.connected(owner)) return "Signed in · choose Upload or Restore to connect points";
        int count = journal.pending(owner);
        if (count > 0) return count + " point operations queued"
                + (owner.equals(lastOwner) && !lastMessage.isEmpty() && !lastMessage.contains("synced") ? " · " + lastMessage : "");
        if (owner.equals(lastOwner) && !lastMessage.isEmpty()) return lastMessage;
        return "Point details synced · full backups checked separately";
    }
    public void addObserver(Observer observer) { if (observer != null) observers.add(observer); }
    public void removeObserver(Observer observer) { observers.remove(observer); }
    public void enqueue(SavedPoint point) {
        if (point == null) return; final String owner = currentOwner();
        AccountBackupManager.get(context).localDataChanged();
        worker.execute(() -> {
            try { SavedPoint latest = store.find(point.id); if (latest != null && !owner.isEmpty()) journal.enqueue(owner, latest); notifyObservers(point.id, ""); runSafely(); }
            catch (RuntimeException error) { notifyObservers(point.id, "Local record retained; cloud queue could not be saved"); }
        });
    }
    public void retryPending() { worker.execute(() -> { try { String owner = currentOwner(); if (!owner.isEmpty()) journal.retry(owner); runSafely(); } catch (RuntimeException error) { notifyObservers(-1, "Local records retained; cloud retry unavailable"); } }); }
    /** Explicit user consent; signing in alone never adopts local records or another account's queue. */
    public void syncWithCurrentAccount(SupabasePointSync.Listener listener) {
        final String owner = currentOwner();
        worker.execute(() -> {
            if (!journal.available()) { listener.onResult(false, accountStatus()); return; }
            if (owner.isEmpty() || !auth.isCurrentAccount(owner)) { listener.onResult(false, "Sign in to sync points"); return; }
            try {
                int added = journal.connect(owner, store.all()); journal.retry(owner);
                lastOwner = owner; lastMessage = "Points connected to this account · transfer queued";
                notifyObservers(-1, lastMessage); listener.onResult(true, added + " local points connected; full backups include photos and trips separately"); runSafely();
            } catch (RuntimeException error) { listener.onResult(false, "Local points retained; cloud queue could not be saved"); }
        });
    }
    public void restoreFromCurrentAccount(SupabasePointSync.Listener listener) {
        final String owner = currentOwner();
        worker.execute(() -> {
            if (!journal.available()) { listener.onResult(false, accountStatus()); return; }
            if (owner.isEmpty() || !auth.isCurrentAccount(owner)) { listener.onResult(false, "Sign in to restore points"); return; }
            int restored = 0, conflicts = 0;
            try {
                journal.connect(owner, Collections.emptyList());
                String cursor = "";
                while (true) {
                    JSONArray rows = remote.page(owner, cursor);
                    for (int i = 0; i < rows.length(); i++) {
                        if (!auth.isCurrentAccount(owner)) throw new AccountPointTransport.Failure("auth", "Account changed; restore paused");
                        JSONObject row = rows.getJSONObject(i); String remoteId = row.getString("point_id"); long version = row.getLong("version");
                        boolean deleted = row.getBoolean("deleted"); long id = journal.localId(owner, remoteId);
                        SavedPoint previous = id == 0 ? store.ledger().findEvent(CloudPointCodec.eventId(owner, remoteId)) : store.find(id);
                        // UI writes the local ledger before its queued cloud enqueue runs. Inspect
                        // that actual record now, then compare its revision again under the local lock.
                        if (id > 0 && previous != null) journal.enqueue(owner, previous);
                        if (id > 0 && deleted && store.ledger().isDeleted(id)) {
                            journal.acknowledgeDeleted(owner, id, version); restored++; continue;
                        }
                        if (id > 0 && previous != null && !deleted && version >= journal.remoteVersion(id)) {
                            SavedPoint downloaded = CloudPointCodec.decode(owner, remoteId, row.getJSONObject("payload"), previous.id);
                            JSONObject serverPayload = CloudPointCodec.encode(downloaded);
                            if (CloudPointCodec.canonical(CloudPointCodec.encode(previous)).equals(CloudPointCodec.canonical(serverPayload))) {
                                journal.restored(owner, remoteId, previous, version, false, serverPayload); restored++; continue;
                            }
                        }
                        if (id > 0 && !journal.canRestore(owner, id, version)) {
                            if (version > journal.remoteVersion(id)) conflicts++;
                            continue; // Never overwrite a dirty local edit/tombstone with downloaded data.
                        }
                        if (id == 0 && previous != null) {
                            JSONObject serverPayload = deleted ? new JSONObject() : CloudPointCodec.encode(
                                    CloudPointCodec.decode(owner, remoteId, row.getJSONObject("payload"), previous.id));
                            journal.adoptRestored(owner, remoteId, previous, version, deleted, serverPayload);
                            if ("conflict".equals(journal.status(owner, previous.id))) conflicts++; else restored++;
                            continue;
                        }
                        if (deleted) {
                            if (previous != null) {
                                if (store.ledger().deleteIfRevision(previous.id, previous.revision)) { journal.restored(owner, remoteId, previous, version, true); restored++; }
                                else conflicts++;
                            }
                            continue;
                        }
                        if (id > 0 && store.ledger().isDeleted(id)) continue;
                        SavedPoint incoming = CloudPointCodec.decode(owner, remoteId, row.getJSONObject("payload"), previous == null ? System.currentTimeMillis() : previous.id);
                        SavedPoint saved = previous == null ? store.ledger().insert(incoming)
                                : store.ledger().editIfRevision(previous.id, previous.revision, point -> CloudPointCodec.merge(point, incoming));
                        if (saved != null) { journal.restored(owner, remoteId, saved, version, false, CloudPointCodec.encode(incoming)); restored++; }
                        else conflicts++;
                    }
                    if (rows.length() < 200) break;
                    String next = rows.getJSONObject(rows.length() - 1).getString("point_id");
                    if (next.equals(cursor)) throw new IllegalStateException("Cloud pagination did not advance");
                    cursor = next;
                }
                lastOwner = owner; lastMessage = restored + " points restored · " + conflicts + " local conflicts preserved";
                notifyObservers(-1, lastMessage); listener.onResult(true, lastMessage + ". Use a full backup to restore photos and trips.");
            } catch (Exception error) {
                lastOwner = owner; lastMessage = (error instanceof AccountPointTransport.Failure ? error.getMessage() : "Restore interrupted")
                        + " · " + restored + " completed records retained; retry to continue";
                notifyObservers(-1, lastMessage); listener.onResult(false, lastMessage);
            }
        });
    }
    public void delete(SavedPoint point, SupabasePointSync.Listener listener) {
        worker.execute(() -> {
            try {
                if (point != null) { store.delete(point.id); journal.delete(point.id); AccountBackupManager.get(context).localDataChanged(); }
                String message = !journal.available() ? "Deleted from this phone; server copies may remain until cloud queue recovery"
                        : point != null && journal.mayExistRemotely(point.id)
                        ? "Deleted from this phone; deletion of its account copy waits for the original account and internet"
                        : "Deleted from this phone; any old device-based server copy is unchanged";
                if (point != null) notifyObservers(point.id, message);
                listener.onResult(true, message); runSafely();
            } catch (RuntimeException error) { listener.onResult(false, "Deletion could not be saved; please retry"); }
        });
    }
    public String deleteNotice(SavedPoint point) {
        if (!journal.available()) return "Delete this point from this phone? Cloud queue recovery is required before server copies can be changed.";
        if (point != null && journal.mayExistRemotely(point.id))
            return "Delete this point from this phone and queue deletion of its account copy? Cloud deletion needs sign-in to its original account and internet. Earlier full backups keep their historical copies. Older device-based server copies are unchanged.";
        return "Delete this point from this phone? No point upload has been confirmed for it. Earlier full backups and old device-based server copies keep their copies.";
    }
    public void clear(long id) { notifyObservers(id, ""); }
    public boolean hasValidatedInternet() {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false; Network active = manager.getActiveNetwork(); if (active == null) return false;
        NetworkCapabilities caps = manager.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
    public int pendingCount() { return journal.pending(currentOwner()); }
    public String state(long id) { return journal.status(currentOwner(), id); }
    public String pointStatus(SavedPoint point) {
        if (point == null) return "Saved on this phone";
        String state = state(point.id);
        if ("synced".equals(state)) return "Point details synced";
        if ("other_account".equals(state)) return "On this phone · linked to another account";
        if ("conflict".equals(state)) return "Cloud conflict · local changes retained";
        if ("auth".equals(state)) return "Sign in again to sync this point";
        if ("failed".equals(state) || "permanent".equals(state)) return "Cloud transfer failed · local point retained";
        if ("pending".equals(state) || "syncing".equals(state)) return "Point and details queued for this account";
        return "Saved on this phone · not connected to cloud";
    }
    private void kick() { worker.execute(this::runSafely); }
    private void runSafely() {
        if (!journal.available() || !CloudDataPolicy.accountSyncAvailable() || !hasValidatedInternet()) return;
        String owner = currentOwner(); if (owner.isEmpty() || !journal.connected(owner)) return;
        CloudSyncLedger.Request request = null;
        try {
            request = journal.claim(owner, System.currentTimeMillis(), store.ledger()); if (request == null) return;
            notifyObservers(request.localId, "Point sync in progress");
            long version = remote.send(request); journal.finish(request, version, "", System.currentTimeMillis());
            lastOwner = owner; lastMessage = "Point details synced · full backups checked separately";
            notifyObservers(request.localId, lastMessage); kick();
        } catch (Exception error) {
            String category = error instanceof AccountPointTransport.Failure ? ((AccountPointTransport.Failure) error).category : "failed";
            if (request != null) try { journal.finish(request, 0, category, System.currentTimeMillis()); } catch (RuntimeException ignored) { }
            lastOwner = owner; lastMessage = error instanceof AccountPointTransport.Failure ? error.getMessage() : "Cloud unavailable · local points retained; retry queued";
            notifyObservers(request == null ? -1 : request.localId, lastMessage);
        }
    }
    private void notifyObservers(long id, String message) {
        String state = id < 0 ? "" : state(id);
        for (Observer observer : observers) try { observer.onSyncChanged(id, state, message, pendingCount()); } catch (RuntimeException ignored) { }
    }
}
