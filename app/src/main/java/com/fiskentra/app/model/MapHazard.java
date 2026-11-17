package com.fiskentra.app.model;

/** A personal map warning, with a zero radius representing a point. */
public final class MapHazard {
    public static final int WARNING_DISTANCE_METERS = 100;
    public static final int DEFAULT_ZONE_RADIUS_METERS = 100;
    public static final int MIN_ZONE_RADIUS_METERS = 20;
    public static final int MAX_ZONE_RADIUS_METERS = 5000;
    public static final int MAX_NAME_LENGTH = PointMetadata.MAX_TITLE_LENGTH;

    public final String id;
    public final String name;
    public final double latitude;
    public final double longitude;
    public final int radiusMeters;

    public MapHazard(String id, String name, double latitude, double longitude, int radiusMeters) {
        String cleanName = PointMetadata.clean(name);
        if (id == null || id.isEmpty() || cleanName.isEmpty() || cleanName.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Invalid hazard name or identifier");
        }
        if (!FieldNavigation.validCoordinate(latitude, longitude)) {
            throw new IllegalArgumentException("Invalid hazard coordinates");
        }
        if (radiusMeters != 0 && (radiusMeters < MIN_ZONE_RADIUS_METERS || radiusMeters > MAX_ZONE_RADIUS_METERS)) {
            throw new IllegalArgumentException("Invalid hazard radius");
        }
        this.id = id;
        this.name = cleanName;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusMeters = radiusMeters;
    }

    public boolean isZone() {
        return radiusMeters > 0;
    }

    public double distanceMeters(double latitude, double longitude) {
        double distanceToCenter = FieldNavigation.distance(this.latitude, this.longitude, latitude, longitude);
        return Math.max(0, distanceToCenter - radiusMeters);
    }
}
