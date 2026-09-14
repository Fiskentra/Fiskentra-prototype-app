package com.fiskentra.app.model;

public final class SavedPoint {
    public final long id;
    public final double latitude;
    public final double longitude;
    public final long timestamp;
    public final String type;
    public final String note;
    /** Optional device-only label. Cloud schema support is intentionally deferred. */
    public final String title;
    public final String symbol;
    public final int color;
    public final int size;
    public final WeatherSnapshot weather;
    public final CatchDetails catchDetails;

    public SavedPoint(long id, double latitude, double longitude, long timestamp, String type, String note) {
        this(id, latitude, longitude, timestamp, type, note, null, null, "");
    }

    public SavedPoint(
            long id,
            double latitude,
            double longitude,
            long timestamp,
            String type,
            String note,
            WeatherSnapshot weather) {
        this(id, latitude, longitude, timestamp, type, note, weather, null, "");
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
        this(id, latitude, longitude, timestamp, type, note, weather, catchDetails, "");
    }

    public SavedPoint(
            long id,
            double latitude,
            double longitude,
            long timestamp,
            String type,
            String note,
            WeatherSnapshot weather,
            CatchDetails catchDetails,
            String title) {
        this(id, latitude, longitude, timestamp, type, note, weather, catchDetails, title, "", 0, 18);
    }

    public SavedPoint(long id, double latitude, double longitude, long timestamp, String type,
            String note, WeatherSnapshot weather, CatchDetails catchDetails, String title,
            String symbol, int color, int size) {
        this.id = id;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = timestamp;
        this.type = type;
        this.note = note;
        this.title = title == null ? "" : title;
        this.weather = weather;
        this.catchDetails = catchDetails;
        this.symbol = symbol == null ? "" : symbol;
        this.color = color;
        this.size = Math.max(12, Math.min(28, size));
    }

    public SavedPoint withWeather(WeatherSnapshot value) {
        return new SavedPoint(id, latitude, longitude, timestamp, type, note, value, catchDetails, title, symbol, color, size);
    }

    public SavedPoint withCatchDetails(CatchDetails value) {
        return new SavedPoint(id, latitude, longitude, timestamp, type, note, weather, value, title, symbol, color, size);
    }

    public SavedPoint withMetadata(String valueTitle, String valueType, String valueNote) {
        return new SavedPoint(id, latitude, longitude, timestamp, valueType, valueNote,
                weather, catchDetails, valueTitle, symbol, color, size);
    }

    public SavedPoint withAppearance(String title, String symbol, int color, int size) {
        return new SavedPoint(id, latitude, longitude, timestamp, type, note, weather, catchDetails, title, symbol, color, size);
    }
}
