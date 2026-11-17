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

    FishingDayStore(SharedPreferences preferences) { prefs = preferences; }

    public FishingDay start() {
        synchronized (FishingDayStore.class) {
            FishingDay current = active();
            if (current != null) return current;

            long now = System.currentTimeMillis();
            FishingDay day = new FishingDay(now, now, 0L);
            List<FishingDay> sessions = new ArrayList<>(all());
            sessions.add(day);
            write(sessions);
            return day;
        }
    }

    public FishingDay stop() {
        synchronized (FishingDayStore.class) {
            FishingDay current = active();
            if (current == null) return null;

            long now = System.currentTimeMillis();
            FishingDay finished = new FishingDay(
                    current.id, current.startedAt, now, current.startWeather, current.endWeather, current.route,
                    current.cloudIdentity, current.recoveredActive, current.recoveredPaused);
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
    }

    public FishingDay active() {
        synchronized (FishingDayStore.class) {
            for (FishingDay day : all()) {
                if (day.isActive()) return day;
            }
            return null;
        }
    }

    public FishingDay updateStartWeather(long id, WeatherSnapshot weather) {
        synchronized (FishingDayStore.class) {
            return updateWeather(id, weather, true);
        }
    }

    public FishingDay updateEndWeather(long id, WeatherSnapshot weather) {
        synchronized (FishingDayStore.class) {
            return updateWeather(id, weather, false);
        }
    }

    public FishingDay updateRoute(long id, List<double[]> route) {
        synchronized (FishingDayStore.class) {
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
    }

    public List<FishingDay> sessionsOnDate(long date) {
        synchronized (FishingDayStore.class) {
            ArrayList<FishingDay> out = new ArrayList<>();
            String target = dayKey(date);
            for (FishingDay day : all()) {
                if (target.equals(dayKey(day.startedAt))) out.add(day);
            }
            return out;
        }
    }

    public List<FishingDay> all() {
        synchronized (FishingDayStore.class) {
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
                            routeFromJson(value.optJSONArray("route")), value.optString("cloud_identity"),
                            value.optBoolean("recovered_active"), value.optBoolean("recovered_paused")));
                }
            } catch (Exception ignored) {
                // A malformed prototype entry must not prevent opening the log.
            }
            Collections.sort(out, (a, b) -> Long.compare(b.startedAt, a.startedAt));
            return out;
        }
    }

    /** Backup must fail, rather than silently publish a partially parsed trip collection. */
    public List<FishingDay> allForBackup() {
        synchronized (FishingDayStore.class) {
            try {
                JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
                List<FishingDay> result = new ArrayList<>();
                java.util.Set<Long> ids = new java.util.HashSet<>();
                java.util.Set<String> identities = new java.util.HashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject row = array.getJSONObject(i);
                    long id = row.getLong("id"), start = row.getLong("started_at"), end = row.optLong("ended_at");
                    if (id <= 0 || start <= 0 || (end != 0 && end < start) || !ids.add(id))
                        throw new IllegalArgumentException("Invalid trip identity or time");
                    String identity = row.optString("cloud_identity");
                    if (!identity.isEmpty() && !identities.add(identity)) throw new IllegalArgumentException("Duplicate trip identity");
                    result.add(new FishingDay(id, start, end, strictWeather(row, "start_weather"),
                            strictWeather(row, "end_weather"), strictRoute(row.has("route") ? row.getJSONArray("route") : null),
                            identity, row.optBoolean("recovered_active"), row.optBoolean("recovered_paused")));
                }
                result.sort((a, b) -> Long.compare(b.startedAt, a.startedAt));
                return Collections.unmodifiableList(result);
            } catch (Exception error) { throw new IllegalStateException("Trip storage needs recovery before cloud backup", error); }
        }
    }

    public FishingDay findForBackup(long id) {
        for (FishingDay day : allForBackup()) if (day.id == id) return day;
        return null;
    }

    public FishingDay findCloudIdentity(String identity) {
        for (FishingDay day : allForBackup()) if (day.cloudIdentity.equals(identity)) return day;
        return null;
    }

    /** Source identity and route are committed together, making crash retries idempotent. */
    public FishingDay importRecovered(FishingDay source, String identity, boolean wasActive, boolean wasPaused) {
        synchronized (FishingDayStore.class) {
            if (identity == null || identity.isEmpty()) throw new IllegalArgumentException("Missing restored trip identity");
            List<FishingDay> days = new ArrayList<>(allForBackup());
            for (FishingDay day : days) if (identity.equals(day.cloudIdentity)) return day;
            long id = Math.max(1, System.currentTimeMillis());
            for (FishingDay day : days) id = Math.max(id, day.id + 1);
            long end = source.endedAt;
            if (end == 0) {
                end = source.startedAt;
                for (double[] point : source.route) end = Math.max(end, (long) point[2]);
                // A recovered open recording is an archive and cannot start a foreground service.
                end = Math.max(source.startedAt + 1, end);
            }
            FishingDay imported = new FishingDay(id, source.startedAt, end, source.startWeather,
                    source.endWeather, source.route, identity, wasActive, wasPaused);
            days.add(imported); write(days); return imported;
        }
    }

    private void write(List<FishingDay> sessions) {
        JSONArray array = new JSONArray();
        try {
            for (FishingDay day : sessions) {
                JSONObject value = new JSONObject();
                value.put("id", day.id);
                value.put("started_at", day.startedAt);
                value.put("ended_at", day.endedAt);
                if (!day.cloudIdentity.isEmpty()) value.put("cloud_identity", day.cloudIdentity);
                if (day.recoveredActive) value.put("recovered_active", true);
                if (day.recoveredPaused) value.put("recovered_paused", true);
                if (day.startWeather != null) value.put("start_weather", day.startWeather.toJson());
                if (day.endWeather != null) value.put("end_weather", day.endWeather.toJson());
                if (!day.route.isEmpty()) {
                    JSONArray route = new JSONArray();
                    for (double[] point : day.route) {
                        JSONObject coordinate = new JSONObject();
                        coordinate.put("lat", point[0]);
                        coordinate.put("lon", point[1]);
                        coordinate.put("time", point[2]);
                        coordinate.put("segment", point.length > 3 && point[3] == 1);
                        route.put(coordinate);
                    }
                    value.put("route", route);
                }
                array.put(value);
            }
            if (!prefs.edit().putString(KEY, array.toString()).commit()) throw new IllegalStateException("Could not save fishing trip");
        } catch (Exception error) { throw new IllegalStateException("Could not save fishing trip", error); }
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
                        point.optDouble("time", 0d), point.optBoolean("segment") ? 1 : 0});
            }
        } catch (Exception ignored) {
            route.clear();
        }
        return route;
    }

    private static WeatherSnapshot strictWeather(JSONObject row, String key) throws Exception {
        if (!row.has(key)) return null;
        WeatherSnapshot weather = WeatherSnapshot.fromJson(row.getJSONObject(key));
        if (weather == null) throw new IllegalArgumentException("Invalid trip weather");
        weather.toJson(); // Reject non-finite values which cannot be represented losslessly as JSON.
        return weather;
    }

    private static List<double[]> strictRoute(JSONArray rows) throws Exception {
        List<double[]> route = new ArrayList<>();
        if (rows == null) return route;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject p = rows.getJSONObject(i);
            double lat = p.getDouble("lat"), lon = p.getDouble("lon"), time = p.getDouble("time");
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || !Double.isFinite(time)
                    || Math.abs(lat) > 90 || Math.abs(lon) > 180 || time <= 0)
                throw new IllegalArgumentException("Invalid trip route");
            route.add(new double[]{lat, lon, time, p.has("segment") && p.getBoolean("segment") ? 1 : 0});
        }
        return route;
    }
}
