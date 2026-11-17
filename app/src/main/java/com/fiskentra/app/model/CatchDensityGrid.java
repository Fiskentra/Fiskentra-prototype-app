package com.fiskentra.app.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable offline catch density in Web Mercator. No fish prediction or synthetic observations. */
public final class CatchDensityGrid {
    public static final int TILE_SIZE = 256;
    private static final int RADIUS = 36;
    private static final double SIGMA = 12;
    private static final double MAX_LATITUDE = 85.0511287798066;
    private final double[][] points;
    private final int count;
    private final long signature;

    public CatchDensityGrid(List<double[]> coordinates) {
        Map<Coordinate, Integer> grouped = new LinkedHashMap<>();
        int total = 0;
        if (coordinates != null) for (double[] point : coordinates) {
            if (point == null || point.length < 2 || !FieldNavigation.validCoordinate(point[0], point[1])) continue;
            Coordinate key = new Coordinate(point[0], point[1]);
            grouped.put(key, grouped.getOrDefault(key, 0) + 1);
            total++;
        }
        List<double[]> normalized = new ArrayList<>();
        long hash = 1;
        for (Map.Entry<Coordinate, Integer> entry : grouped.entrySet()) {
            Coordinate coordinate = entry.getKey();
            double latitude = Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, coordinate.latitude));
            double sin = Math.sin(Math.toRadians(latitude));
            double x = (coordinate.longitude + 180) / 360;
            double y = .5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI);
            normalized.add(new double[]{x, Math.max(0, Math.min(1, y)), entry.getValue()});
            hash = 31 * hash + Double.doubleToLongBits(x);
            hash = 31 * hash + Double.doubleToLongBits(y);
            hash = 31 * hash + entry.getValue();
        }
        points = normalized.toArray(new double[0][]);
        count = total;
        signature = hash;
    }

    public int count() { return count; }
    public long signature() { return signature; }

    /** Returns summed Gaussian weights, or null for a tile with no nearby observations. */
    public float[] tileDensity(int tileX, int tileY, int zoom) {
        if (zoom < 0 || zoom > 22 || count == 0) return null;
        int tileCount = 1 << zoom;
        if (tileY < 0 || tileY >= tileCount) return null;
        int wrappedX = Math.floorMod(tileX, tileCount);
        double world = (double) TILE_SIZE * tileCount;
        float[] density = null;
        for (double[] point : points) {
            double y = point[1] * world - (double) tileY * TILE_SIZE;
            if (y < -RADIUS || y > TILE_SIZE + RADIUS) continue;
            double x = point[0] * world - (double) wrappedX * TILE_SIZE;
            for (int wrap = -1; wrap <= 1; wrap++) {
                double wrappedCenter = x + wrap * world;
                if (wrappedCenter < -RADIUS || wrappedCenter > TILE_SIZE + RADIUS) continue;
                if (density == null) density = new float[TILE_SIZE * TILE_SIZE];
                add(density, wrappedCenter, y, point[2]);
            }
        }
        return density;
    }

    private static void add(float[] density, double centerX, double centerY, double weight) {
        int firstX = Math.max(0, (int) Math.floor(centerX - RADIUS));
        int lastX = Math.min(TILE_SIZE - 1, (int) Math.ceil(centerX + RADIUS));
        int firstY = Math.max(0, (int) Math.floor(centerY - RADIUS));
        int lastY = Math.min(TILE_SIZE - 1, (int) Math.ceil(centerY + RADIUS));
        if (lastX < firstX || lastY < firstY) return;
        float[] horizontal = new float[lastX - firstX + 1];
        for (int x = firstX; x <= lastX; x++) {
            double dx = (x + .5 - centerX) / SIGMA;
            horizontal[x - firstX] = (float) Math.exp(-.5 * dx * dx);
        }
        for (int y = firstY; y <= lastY; y++) {
            double dy = (y + .5 - centerY) / SIGMA;
            float vertical = (float) (weight * Math.exp(-.5 * dy * dy));
            int row = y * TILE_SIZE;
            for (int x = firstX; x <= lastX; x++) density[row + x] += horizontal[x - firstX] * vertical;
        }
    }

    private static final class Coordinate {
        final double latitude, longitude;
        Coordinate(double latitude, double longitude) { this.latitude = latitude; this.longitude = longitude == 180 ? -180 : longitude; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Coordinate)) return false;
            Coordinate point = (Coordinate) other;
            return Double.compare(latitude, point.latitude) == 0 && Double.compare(longitude, point.longitude) == 0;
        }
        @Override public int hashCode() { return 31 * Double.hashCode(latitude) + Double.hashCode(longitude); }
    }
}
