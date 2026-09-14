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
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;
    private final SharedPreferences appearance;

    public PointStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        appearance = context.getSharedPreferences("field_map", Context.MODE_PRIVATE);
    }

    public SavedPoint add(double lat, double lon, String type, String note) {
        synchronized (LOCK) {
            List<SavedPoint> points = new ArrayList<>(all());
            long now = System.currentTimeMillis();
            if (!com.fiskentra.app.model.FieldNavigation.validCoordinate(lat, lon)) throw new IllegalArgumentException("Invalid coordinates");
            long id = now;
            for (SavedPoint existing : points) id = Math.max(id, existing.id + 1);
            SavedPoint point = new SavedPoint(id, lat, lon, now, type, note == null ? "" : note)
                    .withAppearance("", appearance.getString(type + "_symbol", ""),
                            appearance.getInt(type + "_color", 0), appearance.getInt(type + "_size", 18));
            points.add(point);
            write(points);
            return point;
        }
    }

    public List<SavedPoint> all() {
        synchronized (LOCK) {
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
                            o.optString("title", ""), o.optString("symbol", ""),
                            o.optInt("color", 0), o.optInt("size", 18)));
                }
            } catch (Exception ignored) {
                // Corrupt local prototype data should not make the app unusable.
            }
            Collections.sort(out, (a, b) -> Long.compare(b.timestamp, a.timestamp));
            return out;
        }
    }

    public void delete(long id) {
        synchronized (LOCK) {
            List<SavedPoint> points = new ArrayList<>(all());
            points.removeIf(p -> p.id == id);
            write(points);
        }
    }

    public SavedPoint updateWeather(long id, WeatherSnapshot weather) {
        synchronized (LOCK) {
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
    }

    public SavedPoint updateCatchDetails(long id, CatchDetails details) {
        synchronized (LOCK) {
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
    }

    public SavedPoint updateMetadata(long id, String title, String type, String note) {
        synchronized (LOCK) {
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
    }

    public SavedPoint find(long id) {
        synchronized (LOCK) {
            for (SavedPoint point : all()) {
                if (point.id == id) return point;
            }
            return null;
        }
    }

    public SavedPoint style(long id, String title, String symbol, int color, int size) {
        synchronized (LOCK) {
            List<SavedPoint> points = new ArrayList<>(all());
            for (int i = 0; i < points.size(); i++) {
                if (points.get(i).id != id) continue;
                SavedPoint updated = points.get(i).withAppearance(title, symbol, color, size);
                points.set(i, updated);
                write(points);
                return updated;
            }
            return null;
        }
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
                o.put("symbol", p.symbol); o.put("color", p.color); o.put("size", p.size);
                if (p.weather != null) o.put("weather", p.weather.toJson());
                if (p.catchDetails != null) o.put("catch_details", p.catchDetails.toJson());
                array.put(o);
            }
            prefs.edit().putString(KEY, array.toString()).apply();
        } catch (Exception ignored) { }
    }
}
