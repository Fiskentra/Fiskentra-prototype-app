package com.fiskentra.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.fiskentra.app.model.FishingDay;

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
        FishingDay finished = new FishingDay(current.id, current.startedAt, now);
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
                        value.optLong("ended_at", 0L)));
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
                array.put(value);
            }
            prefs.edit().putString(KEY, array.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static String dayKey(long time) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        return calendar.get(Calendar.YEAR) + "-" + calendar.get(Calendar.DAY_OF_YEAR);
    }
}
