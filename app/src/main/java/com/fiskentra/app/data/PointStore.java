package com.fiskentra.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.fiskentra.app.model.SavedPoint;
import com.fiskentra.app.model.WeatherSnapshot;
import com.fiskentra.app.model.CatchDetails;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PointStore {
    private static final String PREFS = "fiskentra_points";
    private static final String KEY = "saved_points";
    private final SharedPreferences prefs;

    public PointStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized SavedPoint add(double lat, double lon, String type, String note) {
        List<SavedPoint> points = new ArrayList<>(all());
        long now = System.currentTimeMillis();
        SavedPoint point = new SavedPoint(now, lat, lon, now, type, note == null ? "" : note);
        points.add(point);
        write(points);
        return point;
    }

    public synchronized List<SavedPoint> all() {
        ArrayList<SavedPoint> out = new ArrayList<>();
        String json = prefs.getString(KEY, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                out.add(new SavedPoint(
                        o.getLong("id"), o.getDouble("lat"), o.getDouble("lon"),
                        o.getLong("time"), o.optString("type", "Moment"), o.optString("note", ""),
                        WeatherSnapshot.fromJson(o.optJSONObject("weather")),
                        CatchDetails.fromJson(o.optJSONObject("catch_details")),
                        o.optString("title", "")));
            }
        } catch (Exception ignored) {
            // Corrupt local prototype data should not make the app unusable.
        }
        Collections.sort(out, (a, b) -> Long.compare(b.timestamp, a.timestamp));
        return out;
    }

    public synchronized void delete(long id) {
        List<SavedPoint> points = new ArrayList<>(all());
        points.removeIf(p -> p.id == id);
        write(points);
    }

    public synchronized SavedPoint updateWeather(long id, WeatherSnapshot weather) {
        List<SavedPoint> points = new ArrayList<>(all());
        SavedPoint updated = null;
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).id != id) continue;
            updated = points.get(i).withWeather(weather);
            points.set(i, updated);
            break;
        }
        if (updated != null) write(points);
        return updated;
    }

    public synchronized SavedPoint updateCatchDetails(long id, CatchDetails details) {
        List<SavedPoint> points = new ArrayList<>(all());
        SavedPoint updated = null;
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).id != id) continue;
            updated = points.get(i).withCatchDetails(details);
            points.set(i, updated);
            break;
        }
        if (updated != null) write(points);
        return updated;
    }

    public synchronized SavedPoint updateMetadata(long id, String title, String type, String note) {
        String error = com.fiskentra.app.model.PointMetadata.validate(title, type, note);
        if (!error.isEmpty()) throw new IllegalArgumentException(error);
        List<SavedPoint> points = new ArrayList<>(all());
        SavedPoint updated = null;
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).id != id) continue;
            updated = points.get(i).withMetadata(
                    com.fiskentra.app.model.PointMetadata.clean(title), type,
                    com.fiskentra.app.model.PointMetadata.clean(note));
            points.set(i, updated);
            break;
        }
        if (updated != null) write(points);
        return updated;
    }

    public synchronized SavedPoint find(long id) {
        for (SavedPoint point : all()) {
            if (point.id == id) return point;
        }
        return null;
    }

    private void write(List<SavedPoint> points) {
        JSONArray array = new JSONArray();
        try {
            for (SavedPoint p : points) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("lat", p.latitude);
                o.put("lon", p.longitude);
                o.put("time", p.timestamp);
                o.put("type", p.type);
                o.put("note", p.note);
                if (!p.title.isEmpty()) o.put("title", p.title);
                if (p.weather != null) o.put("weather", p.weather.toJson());
                if (p.catchDetails != null) o.put("catch_details", p.catchDetails.toJson());
                array.put(o);
            }
            prefs.edit().putString(KEY, array.toString()).apply();
        } catch (Exception ignored) { }
    }
}
