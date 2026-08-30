package com.fiskentra.app.backend;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.fiskentra.app.data.PointStore;
import com.fiskentra.app.model.SavedPoint;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Process-wide, local-first queue for saved-point uploads and deletes.
 *
 * A single instance prevents the Activity and the Flic foreground service from uploading the same
 * point concurrently. Pending points are retried whenever Android validates an internet network.
 */
public final class PointSyncQueue {
    public interface Observer {
        void onSyncChanged(long pointId, String state, String message, int pendingCount);
    }

    public static final String PREFS = "fiskentra_point_sync_status";
    public static final String STATE_PENDING = "pending";
    public static final String STATE_SYNCING = "syncing";
    public static final String STATE_SYNCED = "synced";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_DELETING = "deleting";
    public static final String STATE_DELETE_FAILED = "delete_failed";

    private static final long STALE_SYNC_MS = 20_000L;
    private static volatile PointSyncQueue instance;

    private final Object runLock = new Object();
    private final Set<Long> attemptedInRun = new HashSet<>();
    private final Set<Observer> observers = new CopyOnWriteArraySet<>();
    private final PointStore pointStore;
    private final SharedPreferences prefs;
    private final SupabasePointSync remote;
    private final ConnectivityManager connectivityManager;

    private boolean running;
    private boolean rerunRequested;

    public static PointSyncQueue get(Context context) {
        PointSyncQueue current = instance;
        if (current != null) return current;
        synchronized (PointSyncQueue.class) {
            if (instance == null) instance = new PointSyncQueue(context.getApplicationContext());
            return instance;
        }
    }

    private PointSyncQueue(Context context) {
        pointStore = new PointStore(context);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        remote = new SupabasePointSync(context);
        connectivityManager = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        recoverStaleStates();
        registerNetworkRecovery();
        if (hasValidatedInternet()) retryPending();
    }

    public void addObserver(Observer observer) {
        if (observer != null) observers.add(observer);
    }

    public void removeObserver(Observer observer) {
        if (observer != null) observers.remove(observer);
    }

    /** Marks a new or edited local point for upload, then starts the queue when online. */
    public void enqueue(SavedPoint point) {
        if (point == null) return;
        String current = state(point.id);
        if (STATE_DELETING.equals(current) || STATE_DELETE_FAILED.equals(current)) return;
        if (STATE_SYNCING.equals(current)) {
            setState(point.id, STATE_PENDING, "newer local changes queued");
            synchronized (runLock) {
                rerunRequested = true;
            }
        } else if (!hasValidatedInternet()) {
            setState(point.id, STATE_FAILED, "offline · automatic retry queued");
        } else {
            setState(point.id, STATE_PENDING, "queued for Supabase");
        }
        notifyObservers(point.id);
        if (hasValidatedInternet()) requestRun();
    }

    /** Retries every unsynced local point without requiring the Saved screen to be open. */
    public void retryPending() {
        recoverStaleStates();
        if (!hasValidatedInternet()) return;
        requestRun();
    }

    /** Queues cloud deletion behind any active upload so the upload cannot recreate the row. */
    public void delete(SavedPoint point, SupabasePointSync.Listener listener) {
        if (point == null) {
            listener.onResult(false, "Point is no longer available");
            return;
        }
        setState(point.id, STATE_DELETING, "Deleting from cloud...");
        notifyObservers(point.id);
        remote.delete(point, (deleted, message) -> {
            if (!deleted) {
                setState(point.id, STATE_DELETE_FAILED, message);
                notifyObservers(point.id);
            }
            listener.onResult(deleted, message);
        });
    }

    public void clear(long pointId) {
        prefs.edit()
                .remove(key(pointId, "state"))
                .remove(key(pointId, "message"))
                .remove(key(pointId, "updated_at"))
                .apply();
        notifyObservers(pointId);
    }

    public boolean hasValidatedInternet() {
        return remote.hasValidatedInternet();
    }

    public int pendingCount() {
        recoverStaleStates();
        int count = 0;
        for (SavedPoint point : pointStore.all()) {
            if (shouldSync(point.id)) count++;
        }
        return count;
    }

    private void requestRun() {
        synchronized (runLock) {
            if (running) {
                rerunRequested = true;
                return;
            }
            running = true;
            rerunRequested = false;
            attemptedInRun.clear();
        }
        syncNext();
    }

    private void syncNext() {
        if (!hasValidatedInternet()) {
            finishRun();
            return;
        }
        SavedPoint next = nextPendingPoint();
        if (next == null) {
            finishRun();
            return;
        }
        synchronized (runLock) {
            attemptedInRun.add(next.id);
        }
        setState(next.id, STATE_SYNCING, "Syncing to Supabase");
        notifyObservers(next.id);
        remote.sync(next, (synced, message) -> {
            String current = state(next.id);
            // An edit or delete made while this request was running owns the newer state.
            if (STATE_SYNCING.equals(current)) {
                setState(next.id, synced ? STATE_SYNCED : STATE_FAILED, message);
                notifyObservers(next.id);
            }
            syncNext();
        });
    }

    private SavedPoint nextPendingPoint() {
        List<SavedPoint> points = pointStore.all();
        synchronized (runLock) {
            for (SavedPoint point : points) {
                if (!attemptedInRun.contains(point.id) && shouldSync(point.id)) return point;
            }
        }
        return null;
    }

    private void finishRun() {
        boolean rerun;
        synchronized (runLock) {
            running = false;
            attemptedInRun.clear();
            rerun = rerunRequested;
            rerunRequested = false;
        }
        notifyObservers(-1L);
        if (rerun && hasValidatedInternet()) requestRun();
    }

    private boolean shouldSync(long pointId) {
        String state = state(pointId);
        return !STATE_SYNCED.equals(state)
                && !STATE_SYNCING.equals(state)
                && !STATE_DELETING.equals(state)
                && !STATE_DELETE_FAILED.equals(state);
    }

    private void recoverStaleStates() {
        long now = System.currentTimeMillis();
        for (SavedPoint point : pointStore.all()) {
            String state = state(point.id);
            long updatedAt = prefs.getLong(key(point.id, "updated_at"), point.timestamp);
            if (now - updatedAt <= STALE_SYNC_MS) continue;
            if (STATE_SYNCING.equals(state)) {
                setState(point.id, STATE_FAILED, "sync interrupted · automatic retry queued");
            } else if (STATE_DELETING.equals(state)) {
                setState(point.id, STATE_DELETE_FAILED, "delete interrupted · try again");
            }
        }
    }

    private void registerNetworkRecovery() {
        if (connectivityManager == null) return;
        try {
            connectivityManager.registerDefaultNetworkCallback(
                    new ConnectivityManager.NetworkCallback() {
                        @Override public void onAvailable(Network network) {
                            retryPending();
                        }

                        @Override public void onCapabilitiesChanged(
                                Network network, NetworkCapabilities capabilities) {
                            if (capabilities.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                                retryPending();
                            }
                        }
                    });
        } catch (RuntimeException ignored) {
            // Manual retry and Activity resume remain available on restricted Android devices.
        }
    }

    private String state(long pointId) {
        return prefs.getString(key(pointId, "state"), "");
    }

    private void setState(long pointId, String state, String message) {
        prefs.edit()
                .putString(key(pointId, "state"), state)
                .putString(key(pointId, "message"), message == null ? "" : message)
                .putLong(key(pointId, "updated_at"), System.currentTimeMillis())
                .apply();
    }

    private void notifyObservers(long pointId) {
        String state = pointId < 0L ? "" : state(pointId);
        String message = pointId < 0L ? "" : prefs.getString(key(pointId, "message"), "");
        int pending = pendingCountWithoutRecovery();
        for (Observer observer : observers) {
            observer.onSyncChanged(pointId, state, message, pending);
        }
    }

    private int pendingCountWithoutRecovery() {
        int count = 0;
        for (SavedPoint point : pointStore.all()) {
            if (shouldSync(point.id)) count++;
        }
        return count;
    }

    private static String key(long pointId, String field) {
        return pointId + "_" + field;
    }
}
