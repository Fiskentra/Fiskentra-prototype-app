package com.fiskentra.app.navigation;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.fiskentra.app.model.BacktrackProgress;
import com.fiskentra.app.model.BacktrackRoute;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serialized durable writes off the UI thread; closing cancels delivery, never the pending write. */
public final class BacktrackStore implements AutoCloseable {
    public interface Load { void result(BacktrackProgress progress, boolean failed); }
    public interface Write { void result(boolean saved); }
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final SharedPreferences progress;
    private String savedRouteKey;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    public BacktrackStore(Context context) { prefs = context.getSharedPreferences("backtrack_v1", Context.MODE_PRIVATE); progress = context.getSharedPreferences("backtrack_progress_v1", Context.MODE_PRIVATE); }
    public void load(Load callback) {
        IO.execute(() -> {
            BacktrackProgress result = null; boolean failed = false;
            try { result = BacktrackCodec.decode(prefs.getString("session", "")); if (result != null) { savedRouteKey = BacktrackCodec.key(result.route); result = BacktrackCodec.restoreProgress(result, progress.getString("progress", "")); } } catch (Exception e) { failed = true; }
            BacktrackProgress value = result; boolean error = failed;
            main.post(() -> { if (!closed) callback.result(value, error); });
        });
    }
    public void save(BacktrackRoute route, BacktrackProgress.Snapshot state, Write callback) {
        IO.execute(() -> {
            boolean success;
            try {
                String key = BacktrackCodec.key(route);
                success = true;
                if (!key.equals(savedRouteKey)) { success = prefs.edit().putString("session", BacktrackCodec.encode(route, state)).commit(); if (success) savedRouteKey = key; }
                if (success) success = progress.edit().putString("progress", BacktrackCodec.encodeProgress(route, state)).commit();
            }
            catch (Exception error) { success = false; }
            boolean saved = success; main.post(() -> { if (!closed && callback != null) callback.result(saved); });
        });
    }
    public void clear(Write callback) {
        IO.execute(() -> { boolean saved = prefs.edit().remove("session").commit(); if (saved) { savedRouteKey = null; saved = progress.edit().remove("progress").commit(); } boolean result = saved; main.post(() -> { if (!closed && callback != null) callback.result(result); }); });
    }
    @Override public void close() { closed = true; }
}
