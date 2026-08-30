package com.fiskentra.app.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Current conditions plus a seven-day forecast for one location. */
public final class WeatherForecast {
    public final long fetchedAt;
    public final double latitude;
    public final double longitude;
    public final String timezone;
    public final WeatherSnapshot current;
    public final List<ForecastDay> days;

    public WeatherForecast(long fetchedAt, double latitude, double longitude,
            String timezone, WeatherSnapshot current, List<ForecastDay> days) {
        this.fetchedAt = fetchedAt;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timezone = timezone == null ? "" : timezone;
        this.current = current;
        this.days = Collections.unmodifiableList(new ArrayList<>(days));
    }

    public JSONObject toJson() throws Exception {
        JSONObject value = new JSONObject();
        value.put("fetched_at", fetchedAt);
        value.put("latitude", latitude);
        value.put("longitude", longitude);
        value.put("timezone", timezone);
        if (current != null) value.put("current", current.toJson());
        JSONArray daily = new JSONArray();
        for (ForecastDay day : days) daily.put(day.toJson());
        value.put("days", daily);
        return value;
    }

    public static WeatherForecast fromJson(JSONObject value) {
        if (value == null) return null;
        try {
            ArrayList<ForecastDay> days = new ArrayList<>();
            JSONArray daily = value.optJSONArray("days");
            if (daily != null) {
                for (int i = 0; i < daily.length(); i++) {
                    ForecastDay day = ForecastDay.fromJson(daily.optJSONObject(i));
                    if (day != null) days.add(day);
                }
            }
            return new WeatherForecast(
                    value.getLong("fetched_at"),
                    value.getDouble("latitude"),
                    value.getDouble("longitude"),
                    value.optString("timezone", ""),
                    WeatherSnapshot.fromJson(value.optJSONObject("current")),
                    days);
        } catch (Exception ignored) {
            return null;
        }
    }
}
