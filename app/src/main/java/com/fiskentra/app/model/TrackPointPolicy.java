package com.fiskentra.app.model;

import java.util.List;

/** Pure validation for reliable foreground-service route recording. */
public final class TrackPointPolicy {
    public static final float MIN_DISTANCE_METERS = 8f;
    public static final float MAX_ACCURACY_METERS = 75f;
    public static final long MAX_FIX_AGE_MS = 120_000L;
    public static final long FUTURE_TOLERANCE_MS = 30_000L;
    public static final double MAX_PLAUSIBLE_SPEED_MPS = 55d;

    private TrackPointPolicy() { }

    public static boolean shouldRecord(
            List<double[]> points,
            boolean active,
            long startedAt,
            double latitude,
            double longitude,
            long fixTime,
            float accuracyMeters,
            long now) {
        if (!active || startedAt <= 0L || fixTime <= 0L) return false;
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90d || latitude > 90d || longitude < -180d || longitude > 180d) {
            return false;
        }
        if (accuracyMeters > MAX_ACCURACY_METERS) return false;
        if (fixTime < startedAt || now - fixTime > MAX_FIX_AGE_MS
                || fixTime - now > FUTURE_TOLERANCE_MS) return false;
        if (points == null || points.isEmpty()) return true;

        double[] last = points.get(points.size() - 1);
        if (last == null || last.length < 3 || fixTime <= (long) last[2]) return false;
        double distance = distanceMeters(last[0], last[1], latitude, longitude);
        if (distance < MIN_DISTANCE_METERS) return false;
        double seconds = (fixTime - last[2]) / 1000d;
        return seconds <= 0d || distance / seconds <= MAX_PLAUSIBLE_SPEED_MPS;
    }

    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6_371_000d;
        double phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1), dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2d) * Math.sin(dPhi / 2d)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(dLambda / 2d) * Math.sin(dLambda / 2d);
        return earthRadius * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }
}
