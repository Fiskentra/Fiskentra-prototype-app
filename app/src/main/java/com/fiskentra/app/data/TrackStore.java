package com.fiskentra.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class TrackStore {
    private static final String PREFS = "fiskentra_track";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_POINTS = "points";
    private final SharedPreferences prefs;

    public TrackStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isActive() { return prefs.getBoolean(KEY_ACTIVE, false); }

    public void start() {
        prefs.edit().putBoolean(KEY_ACTIVE, true).putString(KEY_POINTS, "[]").apply();
    }

    public void stop() { prefs.edit().putBoolean(KEY_ACTIVE, false).apply(); }

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
}
