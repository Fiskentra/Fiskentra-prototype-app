package com.fiskentra.app.model;

public final class FishingDay {
    public final long id;
    public final long startedAt;
    public final long endedAt;
    public final WeatherSnapshot startWeather;
    public final WeatherSnapshot endWeather;

    public FishingDay(long id, long startedAt, long endedAt) {
        this(id, startedAt, endedAt, null, null);
    }

    public FishingDay(
            long id,
            long startedAt,
            long endedAt,
            WeatherSnapshot startWeather,
            WeatherSnapshot endWeather) {
        this.id = id;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.startWeather = startWeather;
        this.endWeather = endWeather;
    }

    public boolean isActive() {
        return endedAt <= 0L;
    }

    public long effectiveEnd(long now) {
        return isActive() ? now : endedAt;
    }

    public FishingDay withStartWeather(WeatherSnapshot weather) {
        return new FishingDay(id, startedAt, endedAt, weather, endWeather);
    }

    public FishingDay withEndWeather(WeatherSnapshot weather) {
        return new FishingDay(id, startedAt, endedAt, startWeather, weather);
    }
}
