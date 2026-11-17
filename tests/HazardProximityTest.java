import com.fiskentra.app.location.HazardProximityMonitor;
import com.fiskentra.app.model.MapHazard;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

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

    private static MapHazard polygon(String id, double[][] coordinates) {
        List<MapHazard.Vertex> vertices = new ArrayList<>();
        for (double[] coordinate : coordinates) vertices.add(new MapHazard.Vertex(coordinate[0], coordinate[1]));
        return new MapHazard(id, "Drawn hazard", vertices);
    }

    private static MapHazard square() {
        return polygon("square", new double[][] {
                {offset(-100), offset(-100)}, {offset(-100), offset(100)},
                {offset(100), offset(100)}, {offset(100), offset(-100)}
        });
    }

    private static void polygonApproach() {
        MapHazard square = square();
        check(square.isZone() && square.isPolygon() && square.vertices.size() == 4, "Polygon is a zone");
        check(square.distanceMeters(0, 0) == 0, "Polygon interior has zero distance");
        check(square.distanceMeters(offset(100), 0) < .01, "Polygon edge is nearby");
        check(Math.abs(square.distanceMeters(0, offset(199)) - 99) < .01,
                "Polygon distance measures the edge midpoint instead of vertices or center");
        check(Math.abs(square.distanceMeters(0, offset(-199)) - 99) < .01,
                "The closing edge contributes to distance");
        check(Math.abs(square.distanceMeters(offset(200), offset(200)) - Math.sqrt(20_000)) < .02,
                "Outside a corner, distance clamps to the endpoint");
        List<MapHazard> hazards = Collections.singletonList(square);
        HazardProximityMonitor monitor = new HazardProximityMonitor();
        check(at(monitor, 201, 1, hazards).isEmpty(), "Polygon 101 meters beyond edge is silent");
        check(at(monitor, 199, 2, hazards).equals(hazards), "Polygon warns 99 meters beyond edge");
        check(at(monitor, 50, 3, hazards).isEmpty(), "Staying inside polygon does not replay");
        check(at(monitor, 229, 4, hazards).isEmpty(), "Polygon hysteresis retains nearby state");
        check(at(monitor, 199, 5, hazards).isEmpty(), "Polygon boundary jitter does not replay");
        check(at(monitor, 231, 6, hazards).isEmpty(), "Polygon retreat beyond rearm distance is silent");
        check(at(monitor, 199, 7, hazards).equals(hazards), "Polygon warns on a new approach");
        check(at(new HazardProximityMonitor(), 0, 1, hazards).equals(hazards), "First fix inside polygon warns");
    }

    private static void polygonGeography() {
        MapHazard concave = polygon("concave", new double[][] {
                {0, 0}, {0, offset(300)}, {offset(100), offset(300)},
                {offset(100), offset(100)}, {offset(300), offset(100)}, {offset(300), 0}
        });
        check(concave.distanceMeters(offset(50), offset(250)) == 0, "Concave arm is inside");
        check(Math.abs(concave.distanceMeters(offset(200), offset(200)) - 100) < .02,
                "Concave notch remains outside the zone");
        MapHazard dateline = polygon("date-line-zone", new double[][] {
                {-.001, 179.999}, {-.001, -179.999}, {.001, -179.999}, {.001, 179.999}
        });
        check(dateline.distanceMeters(0, 180) == 0 && dateline.distanceMeters(0, -180) == 0,
                "Polygon contains its interior across the antimeridian");
        check(Math.abs(Math.abs(dateline.longitude) - 180) < .001, "Polygon center stays near antimeridian");
        check(Math.abs(dateline.distanceMeters(0, 179.998) - 111.1949) < .02,
                "Polygon edge distance crosses antimeridian correctly");
        check(dateline.distanceMeters(0, 0) > 10_000_000, "Antipodal point is outside a local zone");
        MapHazard polar = polygon("polar-zone", new double[][] {
                {89.99, -135}, {89.99, -45}, {89.99, 45}, {89.99, 135}
        });
        check(polar.distanceMeters(90, 0) == 0, "Great-circle polygon supports an interior near the pole");
        check(polar.distanceMeters(89.98, 0) > 1000, "Polar outside distance remains physical");
        MapHazard reverse = polygon("reverse", new double[][] {
                {offset(100), offset(-100)}, {offset(100), offset(100)},
                {offset(-100), offset(100)}, {offset(-100), offset(-100)}
        });
        check(reverse.distanceMeters(0, 0) == 0, "Reversing polygon orientation preserves containment");
        check(Math.abs(reverse.distanceMeters(0, offset(199)) - square().distanceMeters(0, offset(199))) < .01,
                "Reversing polygon orientation preserves outside distance");
    }

    private static boolean rejectsPolygon(double[][] coordinates) {
        try {
            polygon("invalid", coordinates);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static void polygonValidation() {
        check(rejectsPolygon(new double[][] {{0, 0}, {0, .001}}), "Too few vertices rejected");
        check(rejectsPolygon(new double[][] {{0, 0}, {0, .001}, {.001, 0}, {0, 0}}), "Repeated closing vertex rejected");
        check(rejectsPolygon(new double[][] {{0, 0}, {.001, .001}, {0, .001}, {.001, 0}}), "Crossing edges rejected");
        check(rejectsPolygon(new double[][] {{0, 0}, {0, .001}, {0, .002}}), "Collinear vertices rejected");
        check(rejectsPolygon(new double[][] {{0, 0}, {0, .000001}, {.000001, 0}}), "Tiny unusable zone rejected");
        check(rejectsPolygon(new double[][] {{0, 0}, {0, 120}, {0, -120}}), "Globe-spanning boundary rejected");
        check(rejectsPolygon(new double[][] {{0, 0}, {0, .001}, {Double.NaN, 0}}), "Nonfinite coordinates rejected");
        check(rejectsPolygon(new double[][] {{0, 0}, {0, .001}, {91, 0}}), "Out-of-range coordinates rejected");
        double[][] excessive = new double[MapHazard.MAX_POLYGON_VERTICES + 1][2];
        check(rejectsPolygon(excessive), "Oversized vertex list rejected");
        List<MapHazard.Vertex> mutable = new ArrayList<>(square().vertices);
        MapHazard immutable = new MapHazard("immutable", "Boundary", mutable);
        mutable.clear();
        check(immutable.vertices.size() == 4, "Polygon defensively copies its vertex list");
        boolean readOnly = false;
        try { immutable.vertices.clear(); } catch (UnsupportedOperationException expected) { readOnly = true; }
        check(readOnly, "Polygon vertex list is immutable");
        check(POINT.vertices.isEmpty() && ZONE.vertices.isEmpty() && !ZONE.isPolygon(),
                "Legacy point and circular-zone representations remain unchanged");
    }

    public static void main(String[] args) {
        pointApproach();
        zoneApproach();
        inaccurateOrOutOfOrderFixes();
        changedHazards();
        geography();
        polygonApproach();
        polygonGeography();
        polygonValidation();
        System.out.println("HazardProximity: " + checks + " checks passed");
    }
}
