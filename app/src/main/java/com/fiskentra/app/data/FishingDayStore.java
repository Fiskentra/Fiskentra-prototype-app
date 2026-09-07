package com.fiskentra.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.fiskentra.app.model.FishingDay;
import com.fiskentra.app.model.WeatherSnapshot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/** Local-first storage for fishing-day sessions. */
public final class FishingDayStore {
    private static final String PREFS = "fiskentra_fishing_days";
    private static final String KEY = "sessions";

    private final SharedPreferences prefs;

    public FishingDayStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized FishingDay start() {
        FishingDay current = active();
        if (current != null) return current;

        long now = System.currentTimeMillis();
        FishingDay day = new FishingDay(now, now, 0L);
        List<FishingDay> sessions = new ArrayList<>(all());
        sessions.add(day);
        write(sessions);
        return day;
    }

    public synchronized FishingDay stop() {
        FishingDay current = active();
        if (current == null) return null;

        long now = System.currentTimeMillis();
        FishingDay finished = new FishingDay(
                current.id, current.startedAt, now, current.startWeather, current.endWeather);
        List<FishingDay> sessions = new ArrayList<>(all());
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id == current.id) {
                sessions.set(i, finished);
                break;
            }
        }
        write(sessions);
        return finished;
    }

    public synchronized FishingDay active() {
        for (FishingDay day : all()) {
            if (day.isActive()) return day;
        }
        return null;
    }

    public synchronized FishingDay updateStartWeather(long id, WeatherSnapshot weather) {
        return updateWeather(id, weather, true);
    }

    public synchronized FishingDay updateEndWeather(long id, WeatherSnapshot weather) {
        return updateWeather(id, weather, false);
    }

    public synchronized FishingDay updateRoute(long id, List<double[]> route) {
        List<FishingDay> sessions = new ArrayList<>(all());
        FishingDay updated = null;
        for (int i = 0; i < sessions.size(); i++) {
            FishingDay day = sessions.get(i);
            if (day.id != id) continue;
            updated = day.withRoute(route);
            sessions.set(i, updated);
            break;
        }
        if (updated != null) write(sessions);
        return updated;
    }

    public synchronized List<FishingDay> sessionsOnDate(long date) {
        ArrayList<FishingDay> out = new ArrayList<>();
        String target = dayKey(date);
        for (FishingDay day : all()) {
            if (target.equals(dayKey(day.startedAt))) out.add(day);
        }
        return out;
    }

    public synchronized List<FishingDay> all() {
        ArrayList<FishingDay> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.getJSONObject(i);
                out.add(new FishingDay(
                        value.getLong("id"),
                        value.getLong("started_at"),
                        value.optLong("ended_at", 0L),
                        WeatherSnapshot.fromJson(value.optJSONObject("start_weather")),
                        WeatherSnapshot.fromJson(value.optJSONObject("end_weather")),
                        routeFromJson(value.optJSONArray("route"))));
            }
        } catch (Exception ignored) {
            // A malformed prototype entry must not prevent opening the log.
        }
        Collections.sort(out, (a, b) -> Long.compare(b.startedAt, a.startedAt));
        return out;
    }

    private void write(List<FishingDay> sessions) {
        JSONArray array = new JSONArray();
        try {
            for (FishingDay day : sessions) {
                JSONObject value = new JSONObject();
                value.put("id", day.id);
                value.put("started_at", day.startedAt);
                value.put("ended_at", day.endedAt);
                if (day.startWeather != null) value.put("start_weather", day.startWeather.toJson());
                if (day.endWeather != null) value.put("end_weather", day.endWeather.toJson());
                if (!day.route.isEmpty()) {
                    JSONArray route = new JSONArray();
                    for (double[] point : day.route) {
                        JSONObject coordinate = new JSONObject();
                        coordinate.put("lat", point[0]);
                        coordinate.put("lon", point[1]);
                        coordinate.put("time", point[2]);
                        route.put(coordinate);
                    }
                    value.put("route", route);
                }
                array.put(value);
            }
            prefs.edit().putString(KEY, array.toString()).apply();
        } catch (Exception ignored) { }
    }

    private FishingDay updateWeather(long id, WeatherSnapshot weather, boolean start) {
        List<FishingDay> sessions = new ArrayList<>(all());
        FishingDay updated = null;
        for (int i = 0; i < sessions.size(); i++) {
            FishingDay day = sessions.get(i);
            if (day.id != id) continue;
            updated = start ? day.withStartWeather(weather) : day.withEndWeather(weather);
            sessions.set(i, updated);
            break;
        }
        if (updated != null) write(sessions);
        return updated;
    }

    private static String dayKey(long time) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        return calendar.get(Calendar.YEAR) + "-" + calendar.get(Calendar.DAY_OF_YEAR);
    }

    private static List<double[]> routeFromJson(JSONArray array) {
        ArrayList<double[]> route = new ArrayList<>();
        if (array == null) return route;
        try {
            for (int i = 0; i < array.length(); i++) {
                JSONObject point = array.getJSONObject(i);
                route.add(new double[]{point.getDouble("lat"), point.getDouble("lon"),
                        point.optDouble("time", 0d)});
            }
        } catch (Exception ignored) {
            route.clear();
        }
        return route;
    }
}
