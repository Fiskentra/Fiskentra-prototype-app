import com.fiskentra.app.model.CatchDensityGrid;
import com.fiskentra.app.model.PastTripGeometry;
import com.fiskentra.app.model.PastTripPalette;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

public final class CatchDensityGridTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static double sum(float[] values) { double total = 0; if (values != null) for (float value : values) total += value; return total; }
    public static void main(String[] args) {
        CatchDensityGrid empty = new CatchDensityGrid(Collections.emptyList());
        check(empty.count() == 0 && empty.tileDensity(0, 0, 0) == null, "no observations do not fabricate a heatmap");
        CatchDensityGrid invalid = new CatchDensityGrid(Arrays.asList(null, new double[]{91, 0}, new double[]{0, 181}, new double[]{Double.NaN, 0}));
        check(invalid.count() == 0, "invalid records do not contribute");
        CatchDensityGrid one = new CatchDensityGrid(Collections.singletonList(new double[]{0, 0}));
        CatchDensityGrid two = new CatchDensityGrid(Arrays.asList(new double[]{0, 0}, new double[]{0, 0}));
        float[] single = one.tileDensity(0, 0, 0), twice = two.tileDensity(0, 0, 0);
        check(single != null && single[127 * 256 + 127] > .99f, "an actual observation has a visible density peak");
        check(Math.abs(sum(twice) / sum(single) - 2) < .00001, "coincident catches accumulate instead of hiding each other");
        check(single[127 * 256 + 127] > single[127 * 256 + 140], "density falls with distance from catch");
        CatchDensityGrid dateline = new CatchDensityGrid(Collections.singletonList(new double[]{0, 180}));
        float[] wrapped = dateline.tileDensity(0, 0, 0);
        check(wrapped[127 * 256] > .99f && wrapped[127 * 256 + 255] > .99f, "world seam renders on both edges");
        CatchDensityGrid eastern = new CatchDensityGrid(Collections.singletonList(new double[]{10, 179.99}));
        check(eastern.tileDensity(0, 3, 3) != null, "neighboring tile across antimeridian receives density");
        check(Arrays.equals(eastern.tileDensity(0, 3, 3), eastern.tileDensity(8, 3, 3)), "wrapped tile coordinates resolve identically");
        check(one.tileDensity(0, -1, 1) == null && one.tileDensity(0, 2, 1) == null, "no tiles outside Mercator rows");
        check(one.tileDensity(0, 0, -1) == null && one.tileDensity(0, 0, 23) == null, "invalid zoom cannot allocate unbounded grids");
        CatchDensityGrid pole = new CatchDensityGrid(Collections.singletonList(new double[]{90, 0}));
        check(pole.count() == 1 && pole.tileDensity(0, 0, 0) != null, "polar coordinates are safely clipped to Mercator bounds");
        check(one.tileDensity(0, 0, 4) == null, "distant tiles stay transparent");
        check(one.signature() != two.signature(), "new catch invalidates old tile cache");
        java.util.List<double[]> route = Arrays.asList(new double[]{10, 10, 1}, new double[]{11, 11, 2},
                new double[]{20, 20, 3, 1}, new double[]{21, 21, 4}, new double[]{Double.NaN, 0, 5},
                new double[]{30, 30, 6}, new double[]{31, 31, 7});
        java.util.List<java.util.List<double[]>> parts = PastTripGeometry.parts(route, 100);
        check(parts.size() == 3, "recording pause and unusable fix both split history lines");
        check(parts.get(0).get(1)[0] == 11 && parts.get(1).get(0)[0] == 20, "no invented connection across recording pause");
        check(parts.get(1).get(1)[0] == 21 && parts.get(2).get(0)[0] == 30, "no invented connection across unusable fix");
        check(!PastTripGeometry.hasLine(Arrays.asList(new double[]{10,10}, new double[]{20,20,3,1})), "isolated fixes do not count as a historical track");
        check(PastTripGeometry.hasLine(route), "real recorded line is eligible");
        ArrayList<double[]> longRoute = new ArrayList<>();
        for (int i = 0; i < 10000; i++) longRoute.add(new double[]{10 + i * .00001, 10, i});
        java.util.List<double[]> reduced = PastTripGeometry.parts(longRoute, 1000).get(0);
        check(reduced.size() <= 1001, "long historical tracks have bounded rendering detail");
        check(reduced.get(0) == longRoute.get(0) && reduced.get(reduced.size() - 1) == longRoute.get(9999), "track simplification preserves endpoints");
        check(longRoute.size() == 10000, "display simplification never edits the stored route");
        long[] tripIds = new long[12];
        for (int i = 0; i < tripIds.length; i++) tripIds[i] = 1788220800000L - i * 86400000L;
        int[] palette = PastTripPalette.slots(tripIds, 12);
        java.util.Set<Integer> unique = new java.util.HashSet<>(); for (int slot : palette) unique.add(slot);
        check(unique.size() == 12, "daily timestamp trip IDs receive distinct colors across the displayed selection");
        check(Arrays.equals(palette, PastTripPalette.slots(tripIds, 12)), "palette assignments are stable for unchanged selected trips");
        for (int slot : palette) check(slot >= 0 && slot < 12, "assigned trip color stays inside palette");
        System.out.println("PASS " + checks + " catch density/history checks");
    }
}
