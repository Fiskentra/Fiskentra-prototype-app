import com.fiskentra.app.model.BoatArrivalPolicy;
import java.util.Locale;

public final class BoatArrivalPolicyTest {
    private static int checks;
    private static void check(boolean ok, String name) { checks++; if (!ok) throw new AssertionError(name); }
    private static void label(double meters, boolean imperial, String expected, String name) {
        String actual = BoatArrivalPolicy.distanceLabel(meters, imperial);
        check(actual.equals(expected), name + ": " + actual);
    }
    public static void main(String[] args) {
        Locale.setDefault(Locale.US);
        BoatArrivalPolicy policy = new BoatArrivalPolicy();
        check(!policy.update(51, 5), "Outside entry radius");
        check(policy.update(50, 25), "Arrive at50m with permitted accuracy");
        for (double distance : new double[]{48, 53, 49, 61, 72, 50, 74.9}) {
            check(policy.update(distance, 7), "GPS jitter retains arrival: " + distance);
        }
        check(!policy.update(75, 5), "Leave arrival area at75m");
        check(!policy.update(60, 5), "Do not reenter hysteresis band");
        check(policy.update(49, 5), "Return to50m area");
        check(!policy.update(10, 26), "Poor accuracy cannot confirm arrival");
        check(policy.update(60, 5), "Accuracy recovery preserves area state");
        check(!policy.update(Double.NaN, 5), "Missing distance rejected");
        check(!policy.update(Double.POSITIVE_INFINITY, 5), "Infinite distance rejected");
        check(!policy.update(-1, 5), "Negative distance rejected");
        check(!policy.update(0, Double.NaN), "Missing accuracy rejected");
        check(!policy.update(0, Double.POSITIVE_INFINITY), "Infinite accuracy rejected");
        check(!policy.update(0, 0), "Zero accuracy rejected");
        check(!policy.update(0, -3), "Negative accuracy rejected");
        check(policy.update(60, 6), "Invalid samples do not erase reliable state");
        policy.reset();
        check(!policy.update(60, 5), "New destination resets arrival hysteresis");
        check(!policy.update(10, 80), "Do not arrive on an inaccurate first sample");
        check(!policy.update(60, 5), "Bad first sample did not enter area");
        check(policy.update(0, 1), "Exact destination remains accepted");
        label(123, false, "\u2248120 m", "Metric display rounds to10m");
        label(126, false, "\u2248130 m", "Metric display rounds upward");
        label(1, false, "\u224810 m", "Never imply exact metre countdown");
        label(0, false, "\u224810 m", "Tiny GPS distances remain approximate");
        label(995, false, "\u22481.0 km", "Metric unit transition after rounding");
        label(1510, false, "\u22481.5 km", "Farther metric display uses100m precision");
        label(100, true, "\u2248350 ft", "Imperial display rounds to50ft");
        label(1, true, "\u224850 ft", "Imperial tiny distance stays approximate");
        label(1609.344, true, "\u22481.0 mi", "Mile boundary");
        label(2414.016, true, "\u22481.5 mi", "Farther imperial display uses tenths");
        label(Double.NaN, false, "Distance unavailable", "NaN display unavailable");
        label(-1, false, "Distance unavailable", "Negative display unavailable");
        System.out.println("PASS: " + checks + " boat arrival checks");
    }
}
