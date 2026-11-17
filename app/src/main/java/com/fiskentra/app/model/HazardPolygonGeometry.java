package com.fiskentra.app.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Validates drawn boundaries and computes distance to their closed edges. */
public final class HazardPolygonGeometry {
    public static final int MAX_VERTICES = 256;
    public static final int MAX_STROKE_SAMPLES = 4096;
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private static final double MIN_AREA_SQUARE_METERS = 1;
    private static final double MIN_VERTEX_SPACING_METERS = .1;
    private static final double MAX_STROKE_ERROR_METERS = 5;
    private static final double GEOMETRY_EPSILON = 1e-6;

    private HazardPolygonGeometry() { }

    public static List<MapHazard.Vertex> validatedVertices(List<MapHazard.Vertex> input) {
        if (input == null || input.size() < 3 || input.size() > MAX_VERTICES) {
            throw new IllegalArgumentException("A hazard zone needs 3 to " + MAX_VERTICES + " vertices");
        }
        List<MapHazard.Vertex> vertices = new ArrayList<>(input);
        for (int i = 0; i < vertices.size(); i++) {
            MapHazard.Vertex vertex = vertices.get(i);
            if (vertex == null || !FieldNavigation.validCoordinate(vertex.latitude, vertex.longitude)) {
                throw new IllegalArgumentException("Invalid hazard zone coordinates");
            }
            for (int j = 0; j < i; j++) {
                MapHazard.Vertex previous = vertices.get(j);
                if (samePosition(vertex, previous)) {
                    throw new IllegalArgumentException("Hazard zone vertices must be distinct");
                }
            }
        }
        checkBoundary(new Projection(vertices));
        return Collections.unmodifiableList(vertices);
    }

    /** Closes a single continuous gesture and keeps its boundary within the storage limit. */
    public static List<MapHazard.Vertex> fromFreehandStroke(List<MapHazard.Vertex> samples) {
        if (samples == null || samples.size() > MAX_STROKE_SAMPLES) {
            throw new IllegalArgumentException("Invalid hazard stroke length");
        }
        List<MapHazard.Vertex> ring = new ArrayList<>(samples.size());
        for (MapHazard.Vertex sample : samples) {
            if (sample == null) throw new IllegalArgumentException("Invalid hazard stroke sample");
            if (ring.isEmpty() || !samePosition(ring.get(ring.size() - 1), sample)) ring.add(sample);
        }
        while (ring.size() > 1 && samePosition(ring.get(0), ring.get(ring.size() - 1))) {
            ring.remove(ring.size() - 1);
        }
        if (ring.size() < 3) throw new IllegalArgumentException("Draw a hazard boundary with area");

        Projection projection = new Projection(ring);
        // Validate the actual trace so simplification cannot erase a crossing.
        checkBoundary(projection);
        double tolerance = Math.max(MIN_VERTEX_SPACING_METERS,
                Math.min(MAX_STROKE_ERROR_METERS, projection.spanMeters() / 1000));
        List<MapHazard.Vertex> outline = simplifyStroke(ring, projection, tolerance);
        if (outline.size() > MAX_VERTICES && tolerance < MAX_STROKE_ERROR_METERS) {
            outline = simplifyStroke(ring, projection, MAX_STROKE_ERROR_METERS);
        }
        try {
            return validatedVertices(outline);
        } catch (IllegalArgumentException invalidOutline) {
            // A shortcut across a narrow inlet can touch another edge. Keep the exact ring if it fits.
            if (ring.size() > MAX_VERTICES) throw invalidOutline;
            return validatedVertices(ring);
        }
    }

    private static boolean samePosition(MapHazard.Vertex first, MapHazard.Vertex second) {
        return FieldNavigation.distance(first.latitude, first.longitude, second.latitude, second.longitude)
                < MIN_VERTEX_SPACING_METERS;
    }

    /** Iterative Douglas–Peucker on the closed ring. The virtual last sample is the first one. */
    private static List<MapHazard.Vertex> simplifyStroke(List<MapHazard.Vertex> ring,
            Projection projection, double tolerance) {
        int count = ring.size();
        boolean[] keep = new boolean[count];
        keep[0] = true;
        ArrayDeque<int[]> spans = new ArrayDeque<>();
        spans.push(new int[]{0, count});
        while (!spans.isEmpty()) {
            int[] span = spans.pop();
            int start = span[0], end = span[1];
            double ax = projection.x[start], ay = projection.y[start];
            double dx = projection.x[end % count] - ax, dy = projection.y[end % count] - ay;
            double lengthSquared = dx * dx + dy * dy;
            double largest = tolerance * tolerance;
            int split = -1;
            for (int i = start + 1; i < end; i++) {
                double px = projection.x[i] - ax, py = projection.y[i] - ay;
                double fraction = lengthSquared == 0 ? 0
                        : Math.max(0, Math.min(1, (px * dx + py * dy) / lengthSquared));
                double ex = px - fraction * dx, ey = py - fraction * dy;
                double errorSquared = ex * ex + ey * ey;
                if (errorSquared > largest) {
                    largest = errorSquared;
                    split = i;
                }
            }
            if (split >= 0) {
                keep[split] = true;
                spans.push(new int[]{start, split});
                spans.push(new int[]{split, end});
            }
        }
        List<MapHazard.Vertex> outline = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (keep[i]) outline.add(ring.get(i));
        }
        return outline;
    }

    private static void checkBoundary(Projection projection) {
        int count = projection.x.length;
        double doubleArea = 0;
        for (int i = 0; i < count; i++) {
            int next = (i + 1) % count;
            doubleArea += projection.x[i] * projection.y[next] - projection.x[next] * projection.y[i];
            for (int j = i + 1; j < count; j++) {
                int after = (j + 1) % count;
                if (next == j || after == i) continue;
                if (segmentsIntersect(projection.x[i], projection.y[i], projection.x[next], projection.y[next],
                        projection.x[j], projection.y[j], projection.x[after], projection.y[after])) {
                    throw new IllegalArgumentException("Hazard zone edges must not cross");
                }
            }
        }
        if (Math.abs(doubleArea) / 2 < MIN_AREA_SQUARE_METERS) {
            throw new IllegalArgumentException("Hazard zone is too small or has no area");
        }
    }

    public static double centerLatitude(List<MapHazard.Vertex> vertices) {
        return Math.toDegrees(new Projection(vertices).latitude);
    }

    public static double centerLongitude(List<MapHazard.Vertex> vertices) {
        return Math.toDegrees(new Projection(vertices).longitude);
    }

    public static double distanceMeters(List<MapHazard.Vertex> vertices, double latitude, double longitude) {
        Projection projection = new Projection(vertices);
        double[] query = projection.project(latitude, longitude);
        boolean inside = false;
        double minimumDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < vertices.size(); i++) {
            MapHazard.Vertex start = vertices.get(i);
            MapHazard.Vertex end = vertices.get((i + 1) % vertices.size());
            int next = (i + 1) % vertices.size();
            if (query != null && (projection.y[i] > query[1]) != (projection.y[next] > query[1])
                    && query[0] < (projection.x[next] - projection.x[i]) * (query[1] - projection.y[i])
                    / (projection.y[next] - projection.y[i]) + projection.x[i]) {
                inside = !inside;
            }
            minimumDistance = Math.min(minimumDistance, edgeDistanceMeters(start, end, latitude, longitude));
        }
        return inside ? 0 : minimumDistance;
    }

    private static double edgeDistanceMeters(MapHazard.Vertex start, MapHazard.Vertex end,
            double latitude, double longitude) {
        double distance = FieldNavigation.distance(start.latitude, start.longitude, latitude, longitude);
        double edgeLength = FieldNavigation.distance(start.latitude, start.longitude, end.latitude, end.longitude);
        double bearingDifference = Math.toRadians(FieldNavigation.bearing(start.latitude, start.longitude, latitude, longitude)
                - FieldNavigation.bearing(start.latitude, start.longitude, end.latitude, end.longitude));
        double angularDistance = distance / EARTH_RADIUS_METERS;
        double along = Math.atan2(Math.sin(angularDistance) * Math.cos(bearingDifference), Math.cos(angularDistance))
                * EARTH_RADIUS_METERS;
        if (along < 0 || along > edgeLength) {
            return Math.min(distance, FieldNavigation.distance(end.latitude, end.longitude, latitude, longitude));
        }
        double cross = Math.sin(angularDistance) * Math.sin(bearingDifference);
        return Math.abs(Math.asin(Math.max(-1, Math.min(1, cross)))) * EARTH_RADIUS_METERS;
    }

    /** Great-circle edges become straight lines in this local hemisphere projection. */
    private static final class Projection {
        final double latitude;
        final double longitude;
        final double[] x;
        final double[] y;

        Projection(List<MapHazard.Vertex> vertices) {
            double vectorX = 0, vectorY = 0, vectorZ = 0;
            for (MapHazard.Vertex vertex : vertices) {
                double lat = Math.toRadians(vertex.latitude), lon = Math.toRadians(vertex.longitude);
                vectorX += Math.cos(lat) * Math.cos(lon);
                vectorY += Math.cos(lat) * Math.sin(lon);
                vectorZ += Math.sin(lat);
            }
            if (Math.sqrt(vectorX * vectorX + vectorY * vectorY + vectorZ * vectorZ) < 1e-9) {
                throw new IllegalArgumentException("A hazard zone must fit within a hemisphere");
            }
            latitude = Math.atan2(vectorZ, Math.hypot(vectorX, vectorY));
            longitude = Math.atan2(vectorY, vectorX);
            x = new double[vertices.size()];
            y = new double[vertices.size()];
            for (int i = 0; i < vertices.size(); i++) {
                MapHazard.Vertex vertex = vertices.get(i);
                double[] projected = project(vertex.latitude, vertex.longitude);
                if (projected == null) {
                    throw new IllegalArgumentException("A hazard zone must fit within a hemisphere");
                }
                x[i] = projected[0];
                y[i] = projected[1];
            }
        }

        double spanMeters() {
            double minX = x[0], maxX = x[0], minY = y[0], maxY = y[0];
            for (int i = 1; i < x.length; i++) {
                minX = Math.min(minX, x[i]);
                maxX = Math.max(maxX, x[i]);
                minY = Math.min(minY, y[i]);
                maxY = Math.max(maxY, y[i]);
            }
            return Math.max(maxX - minX, maxY - minY);
        }

        double[] project(double degreesLatitude, double degreesLongitude) {
            double lat = Math.toRadians(degreesLatitude);
            double deltaLongitude = Math.toRadians(degreesLongitude) - longitude;
            double cosLat = Math.cos(lat), sinLat = Math.sin(lat);
            double denominator = Math.sin(latitude) * sinLat + Math.cos(latitude) * cosLat * Math.cos(deltaLongitude);
            if (denominator <= 1e-9) return null;
            return new double[] {
                    EARTH_RADIUS_METERS * cosLat * Math.sin(deltaLongitude) / denominator,
                    EARTH_RADIUS_METERS * (Math.cos(latitude) * sinLat
                            - Math.sin(latitude) * cosLat * Math.cos(deltaLongitude)) / denominator
            };
        }
    }

    private static boolean segmentsIntersect(double ax, double ay, double bx, double by,
            double cx, double cy, double dx, double dy) {
        if (Math.max(ax, bx) < Math.min(cx, dx) - GEOMETRY_EPSILON
                || Math.max(cx, dx) < Math.min(ax, bx) - GEOMETRY_EPSILON
                || Math.max(ay, by) < Math.min(cy, dy) - GEOMETRY_EPSILON
                || Math.max(cy, dy) < Math.min(ay, by) - GEOMETRY_EPSILON) return false;
        double first = orientation(ax, ay, bx, by, cx, cy);
        double second = orientation(ax, ay, bx, by, dx, dy);
        double third = orientation(cx, cy, dx, dy, ax, ay);
        double fourth = orientation(cx, cy, dx, dy, bx, by);
        return (first * second < 0 && third * fourth < 0)
                || Math.abs(first) < GEOMETRY_EPSILON && within(ax, ay, bx, by, cx, cy)
                || Math.abs(second) < GEOMETRY_EPSILON && within(ax, ay, bx, by, dx, dy)
                || Math.abs(third) < GEOMETRY_EPSILON && within(cx, cy, dx, dy, ax, ay)
                || Math.abs(fourth) < GEOMETRY_EPSILON && within(cx, cy, dx, dy, bx, by);
    }

    private static double orientation(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    private static boolean within(double ax, double ay, double bx, double by, double x, double y) {
        return x >= Math.min(ax, bx) - GEOMETRY_EPSILON && x <= Math.max(ax, bx) + GEOMETRY_EPSILON
                && y >= Math.min(ay, by) - GEOMETRY_EPSILON && y <= Math.max(ay, by) + GEOMETRY_EPSILON;
    }
}
