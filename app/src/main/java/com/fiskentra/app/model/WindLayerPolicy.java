package com.fiskentra.app.model;

/** Freshness, meteorological direction and request budget for a single wind sample. */
public final class WindLayerPolicy {
    public static final long REFRESH_MS = 15L * 60L * 1000L;
    public static final long RETRY_MS = 60L * 1000L;
    public static final long STALE_MS = 30L * 60L * 1000L;
    public static final long MAX_AGE_MS = 60L * 60L * 1000L;

    private WindLayerPolicy() { }

    /** Weather reports where wind comes FROM; a map arrow points where it travels TO. */
    public static float flowBearing(int fromDegrees) {
        return ((fromDegrees % 360 + 360) % 360 + 180) % 360;
    }

    public static boolean usable(long observedAt, double speedKmh, int directionDegrees, long now) {
        return Double.isFinite(speedKmh) && speedKmh >= 0
                && directionDegrees >= 0 && directionDegrees <= 360
                && observedAt > 0 && observedAt <= now + 5L * 60L * 1000L
                && now - observedAt <= MAX_AGE_MS;
    }

    public static boolean due(long now, long lastAttempt, boolean lastSucceeded) {
        return lastAttempt == 0L || now < lastAttempt
                || now - lastAttempt >= (lastSucceeded ? REFRESH_MS : RETRY_MS);
    }

    /** Keep the fixed instrument between the compass and bottom controls, never over a sheet. */
    public static boolean indicatorFits(int top, int height, int mapHeight, int reservedBottom, boolean sheetOpen) {
        return !sheetOpen && top >= 0 && height > 0 && mapHeight > 0 && reservedBottom >= 0
                && (long) top + height <= (long) mapHeight - reservedBottom;
    }
}
