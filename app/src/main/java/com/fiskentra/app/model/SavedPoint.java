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
    /** Event identity/time remain unchanged by enrichment or later manual positioning. */
    public final String eventId;
    public final long tripId;
    public final boolean favorite;
    public final long revision;
    public final String locationState;
    public final long locatedAtUtc;
    public final double locationAccuracy;
    public final String locationSource;

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
        this(id, latitude, longitude, timestamp, type, note, weather, catchDetails, title,
                symbol, color, size, "legacy:" + id, 0L, false, 1L,
                Double.isFinite(latitude) && Double.isFinite(longitude) ? "LOCATED" : "UNLOCATED",
                timestamp, Double.NaN, "legacy");
    }

    public SavedPoint(long id, double latitude, double longitude, long timestamp, String type,
            String note, WeatherSnapshot weather, CatchDetails catchDetails, String title,
            String symbol, int color, int size, String eventId, long tripId, boolean favorite,
            long revision, String locationState, long locatedAtUtc, double locationAccuracy,
            String locationSource) {
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
        this.eventId = eventId == null ? "legacy:" + id : eventId;
        this.tripId = tripId;
        this.favorite = favorite;
        this.revision = Math.max(1, revision);
        this.locationState = locationState == null ? "UNLOCATED" : locationState;
        this.locatedAtUtc = locatedAtUtc;
        this.locationAccuracy = locationAccuracy;
        this.locationSource = locationSource == null ? "" : locationSource;
    }

    public boolean hasLocation() {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180;
    }

    private SavedPoint copy(String type, String note, WeatherSnapshot weather, CatchDetails details,
            String title, String symbol, int color, int size, long trip, boolean starred, long rev) {
        return new SavedPoint(id, latitude, longitude, timestamp, type, note, weather, details, title,
                symbol, color, size, eventId, trip, starred, rev, locationState, locatedAtUtc,
                locationAccuracy, locationSource);
    }

    public SavedPoint withWeather(WeatherSnapshot value) {
        return copy(type, note, value, catchDetails, title, symbol, color, size, tripId, favorite, revision);
    }

    public SavedPoint withCatchDetails(CatchDetails value) {
        return copy(type, note, weather, value, title, symbol, color, size, tripId, favorite, revision);
    }

    public SavedPoint withMetadata(String valueTitle, String valueType, String valueNote) {
        return copy(valueType, valueNote, weather, catchDetails, valueTitle, symbol, color, size, tripId, favorite, revision);
    }

    public SavedPoint withAppearance(String title, String symbol, int color, int size) {
        return copy(type, note, weather, catchDetails, title, symbol, color, size, tripId, favorite, revision);
    }

    public SavedPoint withFavorite(boolean value) { return copy(type, note, weather, catchDetails, title, symbol, color, size, tripId, value, revision); }
    public SavedPoint withTripId(long value) { return copy(type, note, weather, catchDetails, title, symbol, color, size, value, favorite, revision); }
    public SavedPoint withRevision(long value) { return copy(type, note, weather, catchDetails, title, symbol, color, size, tripId, favorite, value); }
    public SavedPoint withLocation(double lat, double lon, long fixTime, double accuracy, String source) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
            throw new IllegalArgumentException("Invalid coordinates");
        return new SavedPoint(id, lat, lon, timestamp, type, note, weather, catchDetails, title,
                symbol, color, size, eventId, tripId, favorite, revision, "LOCATED", fixTime, accuracy, source);
    }
}
