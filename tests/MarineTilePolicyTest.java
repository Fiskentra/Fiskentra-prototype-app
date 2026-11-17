import com.fiskentra.app.model.MarineTilePolicy;
import java.util.Locale;

public final class MarineTilePolicyTest {
    private static int checks;
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    private static void near(double value, double expected, String reason) { check(Math.abs(value - expected) < .00001, reason); }
    public static void main(String[] args) {
        double w = MarineTilePolicy.HALF_WORLD_METERS;
        double[] whole = MarineTilePolicy.bounds(0, 0, 0);
        near(whole[0], -w, "World western edge"); near(whole[1], -w, "World southern edge");
        near(whole[2], w, "World eastern edge"); near(whole[3], w, "World northern edge");
        double[] northWest = MarineTilePolicy.bounds(0, 0, 1);
        near(northWest[0], -w, "NW west"); near(northWest[1], 0, "XYZ y increases southward");
        near(northWest[2], 0, "NW east is prime meridian"); near(northWest[3], w, "NW north");
        double[] southEast = MarineTilePolicy.bounds(1, 1, 1);
        near(southEast[0], 0, "SE west"); near(southEast[1], -w, "SE south");
        near(southEast[2], w, "SE east"); near(southEast[3], 0, "SE north is equator");
        check(MarineTilePolicy.wrapX(-1, 4) == 15, "West dateline wrap");
        check(MarineTilePolicy.wrapX(16, 4) == 0, "East dateline wrap");
        check(!MarineTilePolicy.valid(0, -1, 4), "No vertical wrap at north pole");
        check(!MarineTilePolicy.valid(0, 16, 4), "No vertical wrap at south pole");
        check(!MarineTilePolicy.valid(0, 0, 30), "Reject overflow-prone zoom");
        double[] left = MarineTilePolicy.bounds(10, 7, 5), right = MarineTilePolicy.bounds(11, 7, 5);
        near(left[2], right[0], "Adjacent tiles share exact horizontal edge");
        double[] below = MarineTilePolicy.bounds(10, 8, 5);
        near(left[1], below[3], "Adjacent tiles share exact vertical edge");
        Locale original = Locale.getDefault(); Locale.setDefault(Locale.GERMANY);
        try {
            String url = MarineTilePolicy.depthUrl(0, 0, 1);
            check(url.contains("BBOX=-20037508.34278924,0.00000000,0.00000000,20037508.34278924"), "Locale cannot corrupt WMS coordinate separator");
            check(url.contains("SRS=EPSG:3857") && url.contains("VERSION=1.1.1"), "Avoid latitude/longitude axis-order mismatch");
            check(url.contains("TRANSPARENT=TRUE") && url.contains("emodnet:contours"), "Request depth contours without an opaque base map");
        } finally { Locale.setDefault(original); }
        check(MarineTilePolicy.seamarkUrl(-1, 1, 2).endsWith("/2/3/1.png"), "Dateline URL wraps horizontally");
        String view = MarineTilePolicy.viewSignature(56.95, 24.05, 57.05, 24.15, 10.2);
        check(view.equals(MarineTilePolicy.viewSignature(56.950001, 24.050001, 57.050001, 24.150001, 10.2)),
                "Sub-tile GPS jitter does not clear native tile cache");
        check(!view.equals(MarineTilePolicy.viewSignature(56.95, 25.05, 57.05, 25.15, 10.2)),
                "Pan to new source-tile coverage invalidates view");
        check(!view.equals(MarineTilePolicy.viewSignature(56.95, 24.05, 57.05, 24.15, 11.2)),
                "Changed requested tile level invalidates view");
        check(MarineTilePolicy.viewSignature(-10, 170, 10, -170, 3).contains(":15:7:0:8"),
                "Dateline-crossing footprint preserves west/east order");
        check(!MarineTilePolicy.viewSignature(-90, -180, 90, 180, 3).isEmpty(),
                "World and polar viewport clamps to valid Mercator tiles");
        check(MarineTilePolicy.viewSignature(Double.NaN, 0, 1, 1, 3).isEmpty(), "Invalid viewport ignored");
        System.out.println("MarineTilePolicy: " + checks + " checks passed");
    }
}
