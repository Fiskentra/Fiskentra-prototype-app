package com.fiskentra.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import com.fiskentra.app.model.WeatherSnapshot;

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
    private final SharedPreferences prefs;

    public TrackStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isActive() { return prefs.getBoolean(KEY_ACTIVE, false); }

    public void start() {
        prefs.edit()
                .putBoolean(KEY_ACTIVE, true)
                .putString(KEY_POINTS, "[]")
                .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                .remove(KEY_STOPPED_AT)
                .remove(KEY_START_WEATHER)
                .remove(KEY_END_WEATHER)
                .apply();
    }

    public void stop() {
        prefs.edit()
                .putBoolean(KEY_ACTIVE, false)
                .putLong(KEY_STOPPED_AT, System.currentTimeMillis())
                .apply();
    }

    public long startedAt() { return prefs.getLong(KEY_STARTED_AT, 0L); }

    public long stoppedAt() { return prefs.getLong(KEY_STOPPED_AT, 0L); }

    public WeatherSnapshot startWeather() { return weather(KEY_START_WEATHER); }

    public WeatherSnapshot endWeather() { return weather(KEY_END_WEATHER); }

    public void updateStartWeather(WeatherSnapshot weather) {
        putWeather(KEY_START_WEATHER, weather);
    }

    public void updateEndWeather(WeatherSnapshot weather) {
        putWeather(KEY_END_WEATHER, weather);
    }

    public synchronized void add(Location location) {
        List<double[]> points = points();
        if (!points.isEmpty()) {
            double[] last = points.get(points.size() - 1);
            float[] distance = new float[1];
            Location.distanceBetween(last[0], last[1], location.getLatitude(), location.getLongitude(), distance);
            if (distance[0] < 8f) return;
        }
        points.add(new double[]{location.getLatitude(), location.getLongitude(), System.currentTimeMillis()});
        JSONArray array = new JSONArray();
        try {
            for (double[] p : points) {
                JSONObject o = new JSONObject();
                o.put("lat", p[0]); o.put("lon", p[1]); o.put("time", p[2]); array.put(o);
            }
            prefs.edit().putString(KEY_POINTS, array.toString()).apply();
        } catch (Exception ignored) { }
    }

    public synchronized List<double[]> points() {
        ArrayList<double[]> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_POINTS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                out.add(new double[]{o.getDouble("lat"), o.getDouble("lon"), o.optDouble("time", 0)});
            }
        } catch (Exception ignored) { }
        return out;
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
