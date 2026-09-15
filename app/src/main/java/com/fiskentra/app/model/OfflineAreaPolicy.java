package com.fiskentra.app.model;

/** Pure geographic rules for the user-selectable offline map area. */
public final class OfflineAreaPolicy {
    public static final double MIN_ZOOM = 8d;
    public static final double MAX_ZOOM = 16d;
    public static final double MAX_LATITUDE = 85.05112878d;
    public static final long FREE_RESERVE_BYTES = 64L * 1024 * 1024;
    public static final long DOWNLOAD_BUDGET_BYTES = 512L * 1024 * 1024;
    public enum Coverage { AVAILABLE, NO_AREA, INCOMPLETE, DIFFERENT_STYLE, OUTSIDE_BOUNDS, OUTSIDE_ZOOM }

    private OfflineAreaPolicy() { }

    public static double normalizeRadius(double value) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid radius");
        return value <= 2d ? 2d : value <= 5d ? 5d : 10d;
    }

    /** Returns north, east, south and west. */
    public static double[] bounds(double latitude, double longitude, double requestedRadiusKm) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90d || latitude > 90d || longitude < -180d || longitude > 180d) {
            throw new IllegalArgumentException("Invalid area centre");
        }
        double radius = normalizeRadius(requestedRadiusKm);
        double angularRadius = radius / 6371.0088d;
        double latDelta = Math.toDegrees(angularRadius);
        if (latitude + latDelta > MAX_LATITUDE || latitude - latDelta < -MAX_LATITUDE)
            throw new IllegalArgumentException("Area extends beyond supported map latitudes; move its center farther from the pole");
        double lonDelta = Math.toDegrees(Math.asin(Math.sin(angularRadius) / Math.cos(Math.toRadians(latitude))));
        if (longitude + lonDelta > 180 || longitude - lonDelta < -180)
            throw new IllegalArgumentException("Area crosses the date line; move its center away from 180 degrees");
        return new double[]{latitude + latDelta, longitude + lonDelta, latitude - latDelta, longitude - lonDelta};
    }

    public static boolean contains(double[] area, double latitude, double longitude) {
        return area != null && area.length == 4 && Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude <= area[0] && latitude >= area[2] && longitude <= area[1] && longitude >= area[3];
    }

    public static Coverage coverage(boolean sdkComplete, double[] area, String savedStyle, String currentStyle,
                                    double minimumZoom, double maximumZoom, double latitude, double longitude, double zoom) {
        if (area == null) return Coverage.NO_AREA;
        if (!sdkComplete) return Coverage.INCOMPLETE;
        if (savedStyle == null || !savedStyle.equals(currentStyle)) return Coverage.DIFFERENT_STYLE;
        if (!contains(area, latitude, longitude)) return Coverage.OUTSIDE_BOUNDS;
        if (!Double.isFinite(zoom) || zoom < minimumZoom || zoom > maximumZoom) return Coverage.OUTSIDE_ZOOM;
        return Coverage.AVAILABLE;
    }

    /** -1 means the SDK is still discovering resources. Never infer READY from counts. */
    public static int progress(boolean sdkComplete, boolean precise, long completed, long required) {
        if (sdkComplete) return 100;
        if (!precise || required <= 0) return -1;
        return (int) Math.max(0, Math.min(99, Math.round(completed * 100d / required)));
    }

    /** Planning estimate only: one 64 KiB equivalent tile per XYZ tile plus 4 MiB for style resources. */
    public static long approximateBytes(double[] area) {
        long tiles = 0;
        for (int z = (int) MIN_ZOOM; z <= MAX_ZOOM; z++) {
            double n = Math.scalb(1d, z);
            long west = (long) Math.floor((area[3] + 180d) / 360d * n);
            long east = (long) Math.floor((area[1] + 180d) / 360d * n);
            long north = tileY(area[0], n), south = tileY(area[2], n);
            tiles += (east - west + 1) * (south - north + 1);
        }
        return tiles * 64L * 1024L + 4L * 1024L * 1024L;
    }

    private static long tileY(double latitude, double n) {
        double rad = Math.toRadians(latitude);
        return (long) Math.floor((1d - Math.log(Math.tan(rad) + 1d / Math.cos(rad)) / Math.PI) / 2d * n);
    }

    public static boolean hasSpace(long freeBytes, long estimatedBytes) {
        return estimatedBytes >= 0 && freeBytes >= FREE_RESERVE_BYTES
                && estimatedBytes <= freeBytes - FREE_RESERVE_BYTES;
    }
}
