package com.fiskentra.app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FishingDay {
    public final long id;
    public final long startedAt;
    public final long endedAt;
    public final WeatherSnapshot startWeather;
    public final WeatherSnapshot endWeather;
    public final List<double[]> route;

    public FishingDay(long id, long startedAt, long endedAt) {
        this(id, startedAt, endedAt, null, null);
    }

    public FishingDay(
            long id,
            long startedAt,
            long endedAt,
            WeatherSnapshot startWeather,
            WeatherSnapshot endWeather) {
        this(id, startedAt, endedAt, startWeather, endWeather, Collections.emptyList());
    }

    public FishingDay(
            long id,
            long startedAt,
            long endedAt,
            WeatherSnapshot startWeather,
            WeatherSnapshot endWeather,
            List<double[]> route) {
        this.id = id;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.startWeather = startWeather;
        this.endWeather = endWeather;
        ArrayList<double[]> copy = new ArrayList<>();
        if (route != null) {
            for (double[] point : route) {
                if (point != null && point.length >= 3) {
                    copy.add(point.clone());
                }
            }
        }
        this.route = Collections.unmodifiableList(copy);
    }

    public boolean isActive() {
        return endedAt <= 0L;
    }

    public long effectiveEnd(long now) {
        return isActive() ? now : endedAt;
    }

    public FishingDay withStartWeather(WeatherSnapshot weather) {
        return new FishingDay(id, startedAt, endedAt, weather, endWeather, route);
    }

    public FishingDay withEndWeather(WeatherSnapshot weather) {
        return new FishingDay(id, startedAt, endedAt, startWeather, weather, route);
    }

    public FishingDay withRoute(List<double[]> points) {
        return new FishingDay(id, startedAt, endedAt, startWeather, endWeather, points);
    }
}
