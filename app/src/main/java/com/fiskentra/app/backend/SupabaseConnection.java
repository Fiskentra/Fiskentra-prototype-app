package com.fiskentra.app.backend;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight connectivity check for the configured Supabase project. */
public final class SupabaseConnection {
    public interface Listener {
        void onResult(boolean connected, String message);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public void check(Listener listener) {
        if (!SupabaseConfig.isConfigured()) {
            listener.onResult(false, "Supabase is not configured");
            return;
        }

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(SupabaseConfig.url() + "/rest/v1/");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setRequestProperty("apikey", SupabaseConfig.publishableKey());
                connection.setRequestProperty("Accept", "application/json");
                int status = connection.getResponseCode();
                boolean ok = status >= 200 && status < 400;
                listener.onResult(ok, ok ? "Fiskentra cloud connected" : "Supabase HTTP " + status);
            } catch (IOException e) {
                listener.onResult(false, "Cloud unavailable");
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    public void close() {
        executor.shutdownNow();
    }
}
