package com.fiskentra.app.backend;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import com.fiskentra.app.R;
import com.fiskentra.app.data.PointLedger;
import com.fiskentra.app.data.PointStore;
import com.fiskentra.app.model.SavedPoint;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One process-wide worker. Durable per-point ordering is owned by PointLedger, not by a View. */
public final class PointSyncQueue {
    public interface Observer { void onSyncChanged(long pointId, String state, String message, int pendingCount); }
    public static final String PREFS = "fiskentra_point_sync_status";
    public static final String STATE_PENDING = "pending", STATE_SYNCING = "syncing", STATE_SYNCED = "synced",
            STATE_FAILED = "failed", STATE_DELETING = "deleting", STATE_DELETE_FAILED = "delete_failed";
    private static volatile PointSyncQueue instance;
    private final Context context;
    private final PointStore store;
    private final SupabasePointSync remote;
    private final Set<Observer> observers = new CopyOnWriteArraySet<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private boolean inFlight;

    public static PointSyncQueue get(Context context) {
        if (instance == null) synchronized (PointSyncQueue.class) {
            if (instance == null) instance = new PointSyncQueue(context.getApplicationContext());
        }
        return instance;
    }
    private PointSyncQueue(Context context) {
        this.context = context; store = new PointStore(context); remote = new SupabasePointSync(context);
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity != null) try {
            connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { kick(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) kick();
                }
            });
        } catch (RuntimeException ignored) { /* Scheduled and explicit retry remain available. */ }
        worker.scheduleWithFixedDelay(this::runSafely, 1, 5, TimeUnit.SECONDS);
    }
    public void addObserver(Observer observer) { if (observer != null) observers.add(observer); }
    public void removeObserver(Observer observer) { observers.remove(observer); }
    public void enqueue(SavedPoint point) {
        if (point == null || store.find(point.id) == null) return;
        // Insert/edit already committed the operation atomically. A stale callback cannot upsert.
        notifyObservers(point.id, pointStatus(point)); kick();
    }
    public void retryPending() { worker.execute(() -> { try { store.ledger().retry(); runSafely(); } catch (RuntimeException e) { notifyObservers(-1, context.getString(R.string.data_save_failed)); } }); }
    public void delete(SavedPoint point, SupabasePointSync.Listener listener) {
        worker.execute(() -> {
            try {
                if (point != null) store.delete(point.id);
                if (point != null) notifyObservers(point.id, context.getString(R.string.data_deleted_local));
                listener.onResult(true, context.getString(R.string.data_deleted_local)); runSafely();
            } catch (RuntimeException error) { listener.onResult(false, context.getString(R.string.data_save_failed)); }
        });
    }
    /** Compatibility method: durable receipts/tombstones must never be cleared with UI status. */
    public void clear(long id) { notifyObservers(id, ""); }
    public boolean hasValidatedInternet() { return remote.hasValidatedInternet(); }
    public int pendingCount() { return store.ledger().pendingCount(); }
    public String state(long id) { return store.ledger().status(id); }
    public String pointStatus(SavedPoint point) {
        if (point == null) return context.getString(R.string.data_saved_device);
        String state = state(point.id);
        if ("synced".equals(state)) return context.getString(R.string.data_coordinates_synced);
        if ("auth".equals(state)) return context.getString(R.string.data_sync_auth);
        if ("failed".equals(state) || "delete_failed".equals(state)) return context.getString(R.string.data_sync_failed);
        if ("pending".equals(state) || "syncing".equals(state)) return context.getString(R.string.data_pending_sync);
        return context.getString(R.string.data_saved_device);
    }
    private void kick() { worker.execute(this::runSafely); }
    private void runSafely() {
        try {
            if (inFlight || !hasValidatedInternet()) return;
            PointLedger.Operation operation = store.ledger().claim(System.currentTimeMillis());
            if (operation == null) return;
            inFlight = true; notifyObservers(operation.pointId, "");
            SupabasePointSync.Listener complete = (ok, message) -> worker.execute(() -> complete(operation, ok, message));
            if ("delete".equals(operation.kind)) remote.delete(operation.point, complete);
            else remote.sync(operation.point, complete);
        } catch (RuntimeException failure) { notifyObservers(-1, context.getString(R.string.data_save_failed)); }
    }
    private void complete(PointLedger.Operation operation, boolean success, String message) {
        try {
            String category = SupabasePointSync.errorCategory(message);
            store.ledger().finish(operation.operationId, success, category, message, System.currentTimeMillis());
            inFlight = false; notifyObservers(operation.pointId, message); runSafely();
        } catch (RuntimeException failure) {
            notifyObservers(operation.pointId, context.getString(R.string.data_save_failed));
            // Retry writing the receipt, not the HTTP request, while this process is alive.
            worker.schedule(() -> complete(operation, success, message), 5, TimeUnit.SECONDS);
        }
    }
    private void notifyObservers(long id, String message) {
        String state = id < 0 ? "" : state(id);
        // Legacy views still read this non-authoritative projection. The ledger is the source.
        if (id >= 0) context.getSharedPreferences(PREFS, 0).edit().putString(id + "_state", state)
                .putString(id + "_message", message).apply();
        for (Observer observer : observers) observer.onSyncChanged(id, state, message, pendingCount());
    }
}
