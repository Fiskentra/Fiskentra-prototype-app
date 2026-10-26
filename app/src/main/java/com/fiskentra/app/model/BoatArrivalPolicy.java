package com.fiskentra.app.model;

import java.util.Locale;

/** Approximate boat arrival, independent of Android and the map renderer. */
public final class BoatArrivalPolicy {
    public static final double ENTER_RADIUS_METERS = 50;
    public static final double EXIT_RADIUS_METERS = 75;
    public static final double MAX_ACCURACY_METERS = 25;
    private boolean inside;

    /** Start a new destination without carrying over the previous arrival area. */
    public void reset() { inside = false; }

    /**
     * The caller must supply a fresh GPS fix. Uncertain fixes cannot confirm proximity;
     * they retain the last reliable area state so recovery does not lose hysteresis.
     */
    public boolean update(double distanceMeters, double accuracyMeters) {
        if (!validDistance(distanceMeters) || !Double.isFinite(accuracyMeters)
                || accuracyMeters <= 0 || accuracyMeters > MAX_ACCURACY_METERS) return false;
        if (inside) {
            if (distanceMeters >= EXIT_RADIUS_METERS) inside = false;
        } else if (distanceMeters <= ENTER_RADIUS_METERS) inside = true;
        return inside;
    }

    /** Guidance is approximate: tens of metres, fifty feet, or tenths of a larger unit. */
    public static String distanceLabel(double meters, boolean imperial) {
        if (!validDistance(meters)) return "Distance unavailable";
        if (imperial) {
            double feet = meters / 0.3048;
            double roundedFeet = Math.max(50, Math.round(feet / 50) * 50.0);
            if (roundedFeet >= 5280) return String.format(Locale.getDefault(), "\u2248%.1f mi", meters / 1609.344);
            return "\u2248" + Math.round(roundedFeet) + " ft";
        }
        double roundedMeters = Math.max(10, Math.round(meters / 10) * 10.0);
        if (roundedMeters >= 1000) return String.format(Locale.getDefault(), "\u2248%.1f km", meters / 1000);
        return "\u2248" + Math.round(roundedMeters) + " m";
    }

    private static boolean validDistance(double meters) { return Double.isFinite(meters) && meters >= 0; }
}
