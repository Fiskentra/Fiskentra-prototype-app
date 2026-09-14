package com.fiskentra.app.model;

import java.util.List;

/** Offline great-circle guidance and track statistics, independent of Android or a routing provider. */
public final class FieldNavigation {
    private FieldNavigation() { }
    public static boolean validCoordinate(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double a = Math.pow(Math.sin(Math.toRadians(lat2 - lat1) / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(Math.toRadians(lon2 - lon1) / 2), 2);
        return 6371000 * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, a))));
    }
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double delta = Math.toRadians(lon2 - lon1), a = Math.toRadians(lat1), b = Math.toRadians(lat2);
        return (Math.toDegrees(Math.atan2(Math.sin(delta) * Math.cos(b), Math.cos(a) * Math.sin(b) - Math.sin(a) * Math.cos(b) * Math.cos(delta))) + 360) % 360;
    }
    public static double turn(double bearing, double heading) { return ((bearing - heading) % 360 + 540) % 360 - 180; }
    public static boolean segmentBreak(double[] a, double[] b) {
        return a == null || b == null || a.length < 2 || b.length < 2
                || !validCoordinate(a[0], a[1]) || !validCoordinate(b[0], b[1])
                || (b.length > 3 && b[3] == 1);
    }
    public static double distanceMeters(List<double[]> points) {
        double distance = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1), b = points.get(i);
            if (!segmentBreak(a, b)) distance += distance(a[0], a[1], b[0], b[1]);
        }
        return distance;
    }
}
