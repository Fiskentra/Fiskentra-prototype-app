package com.fiskentra.app.backend;

import android.content.Context;
import android.content.SharedPreferences;

import com.fiskentra.app.model.SavedPoint;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Syncs locally saved prototype points to Fiskentra's Supabase REST API. */
public final class SupabasePointSync {
    public interface Listener {
        void onResult(boolean synced, String message);
    }

    private static final String PREFS = "fiskentra_cloud";
    private static final String INSTALL_ID = "install_id";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;

    public SupabasePointSync(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void sync(SavedPoint point, Listener listener) {
        if (!SupabaseConfig.isConfigured()) {
            listener.onResult(false, "Cloud sync skipped: Supabase is not configured");
            return;
        }

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                byte[] body = payload(point).getBytes(StandardCharsets.UTF_8);
                URL url = new URL(SupabaseConfig.url() + "/rest/v1/saved_points");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setDoOutput(true);
                connection.setRequestProperty("apikey", SupabaseConfig.publishableKey());
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Prefer", "return=minimal");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    listener.onResult(true, "Last point synced to Supabase");
                } else if (status == 409) {
                    listener.onResult(true, "Last point was already synced");
                } else {
                    listener.onResult(false, "Supabase point sync HTTP " + status);
                }
            } catch (Exception e) {
                listener.onResult(false, "Point saved locally; cloud sync pending");
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    public void close() {
        executor.shutdownNow();
    }

    private String payload(SavedPoint point) throws Exception {
        String installId = installId();
        JSONObject json = new JSONObject();
        json.put("id", installId + "-" + point.id);
        json.put("device_id", installId);
        json.put("local_id", point.id);
        json.put("latitude", point.latitude);
        json.put("longitude", point.longitude);
        json.put("recorded_at", iso(point.timestamp));
        json.put("type", point.type);
        json.put("note", point.note == null ? "" : point.note);
        return json.toString();
    }

    private synchronized String installId() {
        String existing = prefs.getString(INSTALL_ID, "");
        if (existing != null && !existing.trim().isEmpty()) return existing;
        String created = UUID.randomUUID().toString();
        prefs.edit().putString(INSTALL_ID, created).apply();
        return created;
    }

    private static String iso(long time) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(time));
    }
}
