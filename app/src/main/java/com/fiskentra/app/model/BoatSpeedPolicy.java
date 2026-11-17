package com.fiskentra.app.model;

import java.util.Locale;

/** Formats the provider's measured speed; never estimates motion from noisy coordinates. */
public final class BoatSpeedPolicy {
    public static final long MAX_AGE_NANOS = 10_000_000_000L;
    public static final double MAX_SPEED_ACCURACY_METERS_PER_SECOND = 1.0;
    private static final double METERS_PER_MILE = 1609.344;

    private BoatSpeedPolicy() {}

    public static String unit(boolean imperial) {
        return imperial ? "mil/h" : "km/h";
    }

    /** Pass NaN for speed or uncertainty when the provider does not supply that value. */
    public static boolean usable(double speedMetersPerSecond, double speedAccuracyMetersPerSecond,
                                 long fixNanos, long nowNanos) {
        if (fixNanos <= 0 || nowNanos < fixNanos || nowNanos - fixNanos > MAX_AGE_NANOS) return false;
        if (!Double.isFinite(speedMetersPerSecond) || speedMetersPerSecond < 0
                || !Double.isFinite(speedMetersPerSecond * 3.6)) return false;
        return Double.isNaN(speedAccuracyMetersPerSecond)
                || (Double.isFinite(speedAccuracyMetersPerSecond) && speedAccuracyMetersPerSecond >= 0
                && speedAccuracyMetersPerSecond <= MAX_SPEED_ACCURACY_METERS_PER_SECOND);
    }

    /** Returns NaN when speed is unavailable; a real reported zero remains a valid measurement. */
    public static double displayValue(double speedMetersPerSecond, double speedAccuracyMetersPerSecond,
                                      long fixNanos, long nowNanos, boolean imperial) {
        if (!usable(speedMetersPerSecond, speedAccuracyMetersPerSecond, fixNanos, nowNanos)) return Double.NaN;
        // Do not clamp small positive speeds: slow drift is meaningful on the water.
        double value = speedMetersPerSecond * (imperial ? 3600.0 / METERS_PER_MILE : 3.6);
        return Double.isFinite(value) ? (value == 0 ? 0 : value) : Double.NaN;
    }

    public static String label(double speedMetersPerSecond, double speedAccuracyMetersPerSecond,
                               long fixNanos, long nowNanos, boolean imperial) {
        double value = displayValue(speedMetersPerSecond, speedAccuracyMetersPerSecond, fixNanos, nowNanos, imperial);
        return Double.isNaN(value) ? "-- " + unit(imperial)
                : String.format(Locale.US, "%.1f %s", value, unit(imperial));
    }

    /** Keeps a measured speed through intervening fixes that do not contain a usable speed. */
    public static final class Measurement {
        private double speedMetersPerSecond = Double.NaN;
        private double speedAccuracyMetersPerSecond = Double.NaN;
        private long measuredAtNanos;
        private long lastNowNanos;

        public void clear() {
            speedMetersPerSecond = Double.NaN;
            speedAccuracyMetersPerSecond = Double.NaN;
            measuredAtNanos = 0;
            lastNowNanos = 0;
        }

        public String update(double speed, double speedAccuracy, long fixNanos, long nowNanos, boolean imperial) {
            if (nowNanos <= 0) {
                clear();
                return "-- " + unit(imperial);
            }
            // An elapsed-clock reset must never revive a measurement from an earlier clock origin.
            if (nowNanos < lastNowNanos) clear();
            lastNowNanos = nowNanos;
            if (fixNanos > measuredAtNanos && usable(speed, speedAccuracy, fixNanos, nowNanos)) {
                speedMetersPerSecond = speed;
                speedAccuracyMetersPerSecond = speedAccuracy;
                measuredAtNanos = fixNanos;
            }
            // Use the original measurement time, not the timestamp of a later network fix.
            return label(speedMetersPerSecond, speedAccuracyMetersPerSecond, measuredAtNanos, nowNanos, imperial);
        }
    }
}
