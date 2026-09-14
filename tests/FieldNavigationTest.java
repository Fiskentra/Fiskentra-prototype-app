import com.fiskentra.app.model.FieldNavigation;
import java.util.*;

public final class FieldNavigationTest {
    private static int checks;
    private static void check(boolean ok, String name) { checks++; if (!ok) throw new AssertionError(name); }
    private static void near(double actual, double expected, double tolerance, String name) { check(Math.abs(actual - expected) <= tolerance, name + ": " + actual); }
    public static void main(String[] args) {
        check(FieldNavigation.validCoordinate(90, 180), "Coordinate boundary accepted");
        check(!FieldNavigation.validCoordinate(Double.NaN, 0), "NaN rejected");
        check(!FieldNavigation.validCoordinate(91, 0), "Invalid latitude rejected");
        check(!FieldNavigation.validCoordinate(0, Double.POSITIVE_INFINITY), "Infinite longitude rejected");
        near(FieldNavigation.distance(0, 0, 0, 0), 0, .001, "Same point");
        near(FieldNavigation.distance(0, 0, 0, 1), 111195, 2, "One equatorial degree");
        near(FieldNavigation.distance(0, 179.9, 0, -179.9), 22239, 2, "Date line shortest path");
        check(Double.isFinite(FieldNavigation.distance(90, 0, -90, 180)), "Antipodal distance finite");
        near(FieldNavigation.bearing(0, 0, 1, 0), 0, .001, "North bearing");
        near(FieldNavigation.bearing(0, 0, 0, 1), 90, .001, "East bearing");
        near(FieldNavigation.bearing(0, 179.9, 0, -179.9), 90, .001, "Date line east bearing");
        near(FieldNavigation.turn(5, 355), 10, .001, "North wrap turn");
        near(FieldNavigation.turn(355, 5), -10, .001, "Negative north wrap turn");
        List<double[]> route = Arrays.asList(new double[]{0, 0, 1000}, new double[]{0, .001, 2000}, new double[]{0, 1, 100000, 1}, new double[]{0, 1.001, 101000});
        near(FieldNavigation.distanceMeters(route), 222.39, .1, "GPS outage is not counted as travelled distance");
        check(FieldNavigation.segmentBreak(route.get(1), route.get(2)), "GPS outage breaks rendered line");
        check(!FieldNavigation.segmentBreak(route.get(0), route.get(1)), "Continuous route retained");
        check(FieldNavigation.segmentBreak(new double[]{0, 0, 1000}, new double[]{0, .001, 2000, 1}), "Explicit pause segment");
        near(FieldNavigation.distanceMeters(Collections.emptyList()), 0, .001, "Empty track");
        System.out.println("PASS: " + checks + " field navigation checks");
    }
}
