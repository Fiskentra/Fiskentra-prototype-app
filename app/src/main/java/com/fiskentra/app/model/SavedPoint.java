package com.fiskentra.app.model;

public final class SavedPoint {
    public final long id;
    public final double latitude;
    public final double longitude;
    public final long timestamp;
    public final String type;
    public final String note;
    public final WeatherSnapshot weather;
    public final CatchDetails catchDetails;

    public SavedPoint(long id, double latitude, double longitude, long timestamp, String type, String note) {
        this(id, latitude, longitude, timestamp, type, note, null, null);
    }

    public SavedPoint(
            long id,
            double latitude,
            double longitude,
            long timestamp,
            String type,
            String note,
            WeatherSnapshot weather) {
        this(id, latitude, longitude, timestamp, type, note, weather, null);
    }

    public SavedPoint(
            long id,
            double latitude,
            double longitude,
            long timestamp,
            String type,
            String note,
            WeatherSnapshot weather,
            CatchDetails catchDetails) {
        this.id = id;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = timestamp;
        this.type = type;
        this.note = note;
        this.weather = weather;
        this.catchDetails = catchDetails;
    }

    public SavedPoint withWeather(WeatherSnapshot value) {
        return new SavedPoint(id, latitude, longitude, timestamp, type, note, value, catchDetails);
    }

    public SavedPoint withCatchDetails(CatchDetails value) {
        return new SavedPoint(id, latitude, longitude, timestamp, type, note, weather, value);
    }
}
