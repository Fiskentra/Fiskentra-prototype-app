import com.fiskentra.app.location.HazardProximityMonitor;
import com.fiskentra.app.model.MapHazard;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class HazardProximityTest {
    private static final long SECOND = 1_000_000_000L;
    private static final MapHazard POINT = new MapHazard("point", " Reef ", 0, 0, 0);
    private static final MapHazard ZONE = new MapHazard("zone", "Shallows", 0, 0, 100);
    private static final List<MapHazard> POINTS = Collections.singletonList(POINT);
    private static int checks;

    private static void check(boolean passed, String message) {
        checks++;
        if (!passed) throw new AssertionError(message);
    }

    private static double offset(double meters) {
        return Math.toDegrees(meters / 6_371_000.0);
    }

    private static List<MapHazard> at(HazardProximityMonitor monitor, double meters, long seconds,
            List<MapHazard> hazards) {
        return monitor.update(0, offset(meters), 10, seconds * SECOND, seconds * SECOND, hazards);
    }

    private static void pointApproach() {
        HazardProximityMonitor monitor = new HazardProximityMonitor();
        check(at(monitor, 101, 1, POINTS).isEmpty(), "Outside the point threshold");
        check(at(monitor, 99, 2, POINTS).equals(POINTS), "Point warns within 100 meters");
        check(at(monitor, 70, 3, POINTS).isEmpty(), "Staying nearby never repeats the warning");
        check(at(monitor, 125, 4, POINTS).isEmpty(), "Beyond the warning radius without clearing it");
        check(at(monitor, 99, 5, POINTS).isEmpty(), "Boundary jitter does not repeat the warning");
        check(at(monitor, 131, 6, POINTS).isEmpty(), "Leaving the outer radius clears without warning");
        check(at(monitor, 99, 7, POINTS).equals(POINTS), "A new approach warns again");
    }

    private static void zoneApproach() {
        HazardProximityMonitor monitor = new HazardProximityMonitor();
        List<MapHazard> hazards = Arrays.asList(POINT, ZONE);
        check(at(monitor, 201, 1, hazards).isEmpty(), "More than 100 meters from the zone boundary");
        check(at(monitor, 199, 2, hazards).equals(Collections.singletonList(ZONE)),
                "Zone warns 100 meters from its edge even if its center is farther away");
        check(at(monitor, 90, 3, hazards).equals(POINTS), "Entering the zone only warns for the new nearby point");
        check(at(monitor, 220, 4, hazards).isEmpty(), "Clear of the point but within the zone buffer");
        check(at(monitor, 190, 5, hazards).isEmpty(), "The zone buffer also prevents repeat warnings");
        check(at(monitor, 231, 6, hazards).isEmpty(), "Beyond the outer buffer of both hazards");
        check(at(monitor, 50, 7, hazards).equals(hazards), "Overlapping approaches arrive as a single batch");
        check(at(new HazardProximityMonitor(), 0, 1, hazards).equals(hazards), "First fix inside warns");
    }

    private static void inaccurateOrOutOfOrderFixes() {
        HazardProximityMonitor monitor = new HazardProximityMonitor();
        check(at(monitor, 20, 10, POINTS).equals(POINTS), "Initial reliable fix warns");
        check(at(monitor, 250, 10, POINTS).isEmpty(), "Duplicate timestamp ignored");
        check(at(monitor, 250, 9, POINTS).isEmpty(), "Out-of-order timestamp ignored");
        check(monitor.update(0, offset(250), 10, 11 * SECOND, 42 * SECOND, POINTS).isEmpty(),
                "Stale fix ignored");
        check(monitor.update(0, offset(250), 51, 12 * SECOND, 12 * SECOND, POINTS).isEmpty(),
                "Inaccurate fix ignored");
        check(monitor.update(0, offset(250), Double.NaN, 13 * SECOND, 13 * SECOND, POINTS).isEmpty(),
                "Fix without accuracy ignored");
        check(monitor.update(91, 0, 10, 14 * SECOND, 14 * SECOND, POINTS).isEmpty(),
                "Invalid coordinate ignored");
        check(at(monitor, 20, 15, POINTS).isEmpty(), "Ignored fixes did not rearm the warning");
        check(at(monitor, 250, 16, POINTS).isEmpty(), "Reliable retreat returns no warning");
        check(at(monitor, 20, 17, POINTS).equals(POINTS), "Reliable retreat rearmed the warning");
    }

    private static void changedHazards() {
        HazardProximityMonitor monitor = new HazardProximityMonitor();
        List<MapHazard> newPoints = Arrays.asList(POINT, ZONE);
        check(at(monitor, 0, 1, POINTS).equals(POINTS), "Warn for the original point");
        check(at(monitor, 0, 2, newPoints).equals(Collections.singletonList(ZONE)),
                "Adding a hazard does not replay the original warning");
        check(at(monitor, 0, 3, Collections.emptyList()).isEmpty(), "Deleted hazards are inactive");
        check(at(monitor, 0, 4, newPoints).equals(newPoints), "Deletion clears the approach state");
    }

    private static void geography() {
        check(POINT.name.equals("Reef") && !POINT.isZone() && ZONE.isZone(), "Hazard kind and cleaned name");
        MapHazard dateline = new MapHazard("dateline", "Rocks", 0, 179.9999, 0);
        check(dateline.distanceMeters(0, -179.9999) < 23, "Distance crosses the antimeridian");
    }

    public static void main(String[] args) {
        pointApproach();
        zoneApproach();
        inaccurateOrOutOfOrderFixes();
        changedHazards();
        geography();
        System.out.println("HazardProximity: " + checks + " checks passed");
    }
}
