package com.fiskentra.app.model;

/** Pure geographic rules for the user-selectable offline map area. */
public final class OfflineAreaPolicy {
    public static final double MIN_ZOOM = 8d;
    public static final double MAX_ZOOM = 16d;

    private OfflineAreaPolicy() { }

    public static double normalizeRadius(double value) {
        return value <= 2d ? 2d : value <= 5d ? 5d : 10d;
    }

    /** Returns north, east, south and west. */
    public static double[] bounds(double latitude, double longitude, double requestedRadiusKm) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90d || latitude > 90d || longitude < -180d || longitude > 180d) {
            throw new IllegalArgumentException("Invalid area centre");
        }
        double radius = normalizeRadius(requestedRadiusKm);
        double latDelta = radius / 111.32d;
        double cosine = Math.max(.15d, Math.cos(Math.toRadians(latitude)));
        double lonDelta = radius / (111.32d * cosine);
        return new double[]{
                Math.min(85d, latitude + latDelta),
                Math.min(180d, longitude + lonDelta),
                Math.max(-85d, latitude - latDelta),
                Math.max(-180d, longitude - lonDelta)
        };
    }
}
