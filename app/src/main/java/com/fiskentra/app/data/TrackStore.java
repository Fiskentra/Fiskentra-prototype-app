package com.fiskentra.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import com.fiskentra.app.model.WeatherSnapshot;
import com.fiskentra.app.model.TrackPointPolicy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class TrackStore {
    private static final String PREFS = "fiskentra_track";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_POINTS = "points";
    private static final String KEY_STARTED_AT = "started_at";
    private static final String KEY_STOPPED_AT = "stopped_at";
    private static final String KEY_START_WEATHER = "start_weather";
    private static final String KEY_END_WEATHER = "end_weather";
    private static final Object LOCK = new Object();
    private static String cachedJson;
    private static List<double[]> cachedPoints = java.util.Collections.emptyList();
    private final SharedPreferences prefs;

    public TrackStore(Context context) {
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    TrackStore(SharedPreferences preferences) {
        prefs = preferences;
    }

    public boolean isActive() { synchronized (LOCK) { return prefs.getBoolean(KEY_ACTIVE, false); } }

    public void start() {
        synchronized (LOCK) {
            prefs.edit()
                    .putBoolean(KEY_ACTIVE, true)
                    .putBoolean("paused", false).putBoolean("segment_pending", false).remove("last_fix")
                    .putString(KEY_POINTS, "[]")
                    .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                    .remove(KEY_STOPPED_AT)
                    .remove(KEY_START_WEATHER)
                    .remove(KEY_END_WEATHER)
                    .apply();
        }
    }

    public void stop() {
        synchronized (LOCK) {
            prefs.edit()
                    .putBoolean(KEY_ACTIVE, false)
                    .putBoolean("paused", false)
                    .putLong(KEY_STOPPED_AT, System.currentTimeMillis())
                    .apply();
        }
    }

    public long startedAt() { return prefs.getLong(KEY_STARTED_AT, 0L); }

    public long stoppedAt() { return prefs.getLong(KEY_STOPPED_AT, 0L); }
    public boolean isPaused() { return prefs.getBoolean("paused", false); }
    public void setPaused(boolean paused) {
        synchronized (LOCK) { if (isActive()) prefs.edit().putBoolean("paused", paused).putBoolean("segment_pending", true).apply(); }
    }

    /** Toggle recording without finishing the trip or discarding its route. */
    public String toggleRecording() {
        synchronized (LOCK) {
            if (!isActive()) {
                start();
                return "Track recording started";
            }
            boolean resume = isPaused();
            setPaused(!resume);
            return resume ? "Track recording resumed" : "Track recording paused";
        }
    }

    /** Commit the hold transition and its receipt together; duplicate callbacks cannot toggle twice. */
    public String toggleRecording(String eventId) {
        return toggleRecording(eventId, System.currentTimeMillis());
    }
    public String toggleRecording(String eventId, long occurredAtUtc) {
        synchronized (LOCK) {
            java.util.Set<String> receipts = new java.util.HashSet<>(prefs.getStringSet("hold_events", java.util.Collections.emptySet()));
            if (receipts.contains(eventId)) return isPaused() ? "Track recording paused" : isActive() ? "Track recording active" : "Track saved";
            receipts.add(eventId);
            SharedPreferences.Editor editor = prefs.edit().putStringSet("hold_events", receipts);
            String result;
            if (!isActive()) {
                editor.putBoolean(KEY_ACTIVE, true).putBoolean("paused", false).putBoolean("segment_pending", false)
                        .remove("last_fix").putString(KEY_POINTS, "[]").putLong(KEY_STARTED_AT, occurredAtUtc)
                        .remove(KEY_STOPPED_AT).remove(KEY_START_WEATHER).remove(KEY_END_WEATHER);
                result = "Track recording started";
            } else {
                boolean paused = !isPaused();
                editor.putBoolean("paused", paused).putBoolean("segment_pending", true);
                result = paused ? "Track recording paused" : "Track recording resumed";
            }
            if (!editor.commit()) throw new IllegalStateException("Could not save track recording state");
            return result;
        }
    }

    public WeatherSnapshot startWeather() { return weather(KEY_START_WEATHER); }

    public WeatherSnapshot endWeather() { return weather(KEY_END_WEATHER); }

    public void updateStartWeather(WeatherSnapshot weather) {
        putWeather(KEY_START_WEATHER, weather);
    }

    public void updateEndWeather(WeatherSnapshot weather) {
        putWeather(KEY_END_WEATHER, weather);
    }

    public boolean add(Location location) {
        if (!com.fiskentra.app.location.FiskentraLocationManager.isFresh(location)) return false;
        synchronized (LOCK) {
            if (!isActive()) return false;
            if (!location.hasAccuracy() || location.getAccuracy() > TrackPointPolicy.MAX_ACCURACY_METERS) {
                prefs.edit().putBoolean("segment_pending", true).apply();
                return false;
            }
            long previousFix = prefs.getLong("last_fix", 0);
            boolean gap = prefs.getBoolean("segment_pending", false)
                    || (previousFix > 0 && location.getTime() - previousFix > 60_000);
            prefs.edit().putLong("last_fix", location.getTime()).putBoolean("segment_pending", gap).apply();
            if (isPaused()) return false;
            List<double[]> points = new ArrayList<>(readPoints());
            long fixTime = location.getTime();
            if (!TrackPointPolicy.shouldRecord(points, prefs.getBoolean(KEY_ACTIVE, false),
                    prefs.getLong(KEY_STARTED_AT, 0L), location.getLatitude(), location.getLongitude(),
                    fixTime, location.hasAccuracy() ? location.getAccuracy() : 0f,
                    System.currentTimeMillis())) return false;
            points.add(new double[]{location.getLatitude(), location.getLongitude(), fixTime, gap ? 1 : 0});
            JSONArray array = new JSONArray();
            try {
                for (double[] p : points) {
                    JSONObject o = new JSONObject();
                    o.put("lat", p[0]); o.put("lon", p[1]); o.put("time", p[2]);
                    o.put("segment", p.length > 3 && p[3] == 1); array.put(o);
                }
                prefs.edit().putString(KEY_POINTS, array.toString()).putBoolean("segment_pending", false).apply();
                return true;
            } catch (Exception ignored) { return false; }
        }
    }

    public List<double[]> points() {
        synchronized (LOCK) { return readPoints(); }
    }

    private List<double[]> readPoints() {
        String serialized = prefs.getString(KEY_POINTS, "[]");
        if (serialized.equals(cachedJson)) return cachedPoints;
        ArrayList<double[]> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(serialized);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                out.add(new double[]{o.getDouble("lat"), o.getDouble("lon"), o.optDouble("time", 0), o.optBoolean("segment") ? 1 : 0});
            }
        } catch (Exception ignored) { }
        cachedJson = serialized;
        cachedPoints = java.util.Collections.unmodifiableList(out);
        return cachedPoints;
    }

    private WeatherSnapshot weather(String key) {
        try {
            return WeatherSnapshot.fromJson(new JSONObject(prefs.getString(key, "")));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void putWeather(String key, WeatherSnapshot weather) {
        if (weather == null) return;
        try {
            prefs.edit().putString(key, weather.toJson().toString()).apply();
        } catch (Exception ignored) { }
    }
}
