import com.fiskentra.app.model.BoatSpeedPolicy;
import java.util.Locale;

public final class BoatSpeedPolicyTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long now = 40_000_000_000L;
        double unknown = Double.NaN;
        check("36.0 km/h".equals(BoatSpeedPolicy.label(10, .2, now, now, false)), "m/s to km/h");
        check("22.4 mil/h".equals(BoatSpeedPolicy.label(10, .2, now, now, true)), "m/s to mil/h");
        check("1.0 mil/h".equals(BoatSpeedPolicy.label(.44704, unknown, now, now, true)), "international mile conversion");
        check("0.0 km/h".equals(BoatSpeedPolicy.label(0, .1, now, now, false)), "reported stationary zero valid");
        check("0.0 km/h".equals(BoatSpeedPolicy.label(-0.0, unknown, now, now, false)), "no negative zero display");
        check("0.4 km/h".equals(BoatSpeedPolicy.label(.1, .1, now, now, false)), "slow boat drift not clamped away");
        check("-- km/h".equals(BoatSpeedPolicy.label(unknown, unknown, now, now, false)), "missing speed is not zero");
        check("-- mil/h".equals(BoatSpeedPolicy.label(unknown, unknown, now, now, true)), "unavailable imperial label");
        check(BoatSpeedPolicy.usable(2, unknown, now - BoatSpeedPolicy.MAX_AGE_NANOS, now), "10 seconds boundary accepted");
        check(!BoatSpeedPolicy.usable(2, unknown, now - BoatSpeedPolicy.MAX_AGE_NANOS - 1, now), "stale speed rejected");
        check("-- km/h".equals(BoatSpeedPolicy.label(2, unknown, now - 11_000_000_000L, now, false)), "stale readout unavailable");
        check(!BoatSpeedPolicy.usable(2, unknown, now + 1, now), "future monotonic timestamp rejected");
        check(!BoatSpeedPolicy.usable(2, unknown, 0, now), "missing timestamp rejected");
        check(!BoatSpeedPolicy.usable(-1, unknown, now, now), "negative speed rejected");
        check(!BoatSpeedPolicy.usable(Double.POSITIVE_INFINITY, unknown, now, now), "infinite speed rejected");
        check(BoatSpeedPolicy.usable(2, unknown, now, now), "provider without uncertainty supported");
        check(BoatSpeedPolicy.usable(2, 1, now, now), "maximum usable uncertainty accepted");
        check(!BoatSpeedPolicy.usable(2, 1.01, now, now), "uncertain measurement rejected");
        check(!BoatSpeedPolicy.usable(2, -.1, now, now), "negative uncertainty invalid");
        check(!BoatSpeedPolicy.usable(2, Double.POSITIVE_INFINITY, now, now), "infinite uncertainty invalid");
        check(Double.isNaN(BoatSpeedPolicy.displayValue(unknown, unknown, now, now, false)), "numeric unavailable value NaN");
        check("-- km/h".equals(BoatSpeedPolicy.label(Double.MAX_VALUE, unknown, now, now, false)), "conversion overflow not displayed");
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            check("36.0 km/h".equals(BoatSpeedPolicy.label(10, .2, now, now, false)), "stable decimal format in English UI");
        } finally { Locale.setDefault(before); }

        BoatSpeedPolicy.Measurement measurement = new BoatSpeedPolicy.Measurement();
        check("-- km/h".equals(measurement.update(unknown, unknown, now, now, false)), "empty cache stays unavailable");
        check("36.0 km/h".equals(measurement.update(10, .2, now, now, false)), "usable GPS measurement cached");
        check("36.0 km/h".equals(measurement.update(unknown, unknown, now + 2_000_000_000L, now + 2_000_000_000L, false)), "network fix without speed preserves measured speed");
        check("36.0 km/h".equals(measurement.update(20, 2, now + 3_000_000_000L, now + 3_000_000_000L, false)), "uncertain later fix cannot erase good measurement");
        check("36.0 km/h".equals(measurement.update(20, .1, now - 1, now + 4_000_000_000L, false)), "older replay cannot replace measurement");
        check("36.0 km/h".equals(measurement.update(20, .1, now, now + 5_000_000_000L, false)), "same-timestamp replay cannot replace measurement");
        check("22.4 mil/h".equals(measurement.update(unknown, unknown, now + 6_000_000_000L, now + 6_000_000_000L, true)), "unit switch converts cached measurement");
        check("36.0 km/h".equals(measurement.update(unknown, unknown, now + 10_000_000_000L, now + 10_000_000_000L, false)), "cache valid at 10 seconds");
        check("-- km/h".equals(measurement.update(unknown, unknown, now + 10_000_000_001L, now + 10_000_000_001L, false)), "network updates do not extend original speed age");
        check("18.0 km/h".equals(measurement.update(5, .1, now + 11_000_000_000L, now + 11_000_000_000L, false)), "fresh newer speed replaces expired measurement");
        check("18.0 km/h".equals(measurement.update(15, .1, now + 14_000_000_000L, now + 12_000_000_000L, false)), "future sample ignored without erasing current cache");
        check("18.0 km/h".equals(measurement.update(Double.MAX_VALUE, .1, now + 13_000_000_000L, now + 13_000_000_000L, false)), "overflow sample does not erase usable cache");
        measurement.clear();
        check("-- mil/h".equals(measurement.update(unknown, unknown, now + 14_000_000_000L, now + 14_000_000_000L, true)), "clear removes cached speed");
        measurement.update(5, .1, now + 15_000_000_000L, now + 15_000_000_000L, false);
        check("-- km/h".equals(measurement.update(unknown, unknown, 500, 500, false)), "clock reset clears old clock-origin measurement");
        check("7.2 km/h".equals(measurement.update(2, .1, 600, 600, false)), "new measurement accepted after clock reset");
        check("-- km/h".equals(measurement.update(unknown, unknown, 0, 0, false)), "invalid clock clears cache");
        check("-- km/h".equals(measurement.update(20, .1, 1001, 1000, false)), "future sample cannot populate empty cache");
        System.out.println("PASS " + checks + " boat speed checks");
    }
}
