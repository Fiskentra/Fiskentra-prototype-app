package com.fiskentra.app.model;

import java.util.Collections;
import java.util.List;

/** A personal warning represented by a point, a legacy circle, or a drawn polygon. */
public final class MapHazard {
    public static final int WARNING_DISTANCE_METERS = 100;
    public static final int DEFAULT_ZONE_RADIUS_METERS = 100;
    public static final int MIN_ZONE_RADIUS_METERS = 20;
    public static final int MAX_ZONE_RADIUS_METERS = 5000;
    public static final int MAX_NAME_LENGTH = PointMetadata.MAX_TITLE_LENGTH;
    public static final int MAX_POLYGON_VERTICES = HazardPolygonGeometry.MAX_VERTICES;

    public final String id;
    public final String name;
    public final double latitude;
    public final double longitude;
    public final int radiusMeters;
    public final List<Vertex> vertices;

    public static final class Vertex {
        public final double latitude;
        public final double longitude;

        public Vertex(double latitude, double longitude) {
            if (!FieldNavigation.validCoordinate(latitude, longitude)) {
                throw new IllegalArgumentException("Invalid hazard coordinates");
            }
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

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
        vertices = Collections.emptyList();
    }

    public MapHazard(String id, String name, List<Vertex> vertices) {
        String cleanName = PointMetadata.clean(name);
        if (id == null || id.isEmpty() || cleanName.isEmpty() || cleanName.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Invalid hazard name or identifier");
        }
        this.vertices = HazardPolygonGeometry.validatedVertices(vertices);
        this.id = id;
        this.name = cleanName;
        latitude = HazardPolygonGeometry.centerLatitude(this.vertices);
        longitude = HazardPolygonGeometry.centerLongitude(this.vertices);
        radiusMeters = 0;
    }

    public boolean isPolygon() {
        return !vertices.isEmpty();
    }

    public boolean isZone() {
        return radiusMeters > 0 || isPolygon();
    }

    public double distanceMeters(double latitude, double longitude) {
        if (isPolygon()) return HazardPolygonGeometry.distanceMeters(vertices, latitude, longitude);
        double distanceToCenter = FieldNavigation.distance(this.latitude, this.longitude, latitude, longitude);
        return Math.max(0, distanceToCenter - radiusMeters);
    }
}
