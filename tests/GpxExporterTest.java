import com.fiskentra.app.export.GpxExporter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class GpxExporterTest {
    private static int passed;

    public static void main(String[] args) {
        long start = 1_700_000_000_000L;
        long end = start + 10_000L;
        List<double[]> track = new ArrayList<>();
        track.add(new double[]{60.2, 16.2, start + 2_000});
        track.add(new double[]{60.1, 16.1, start + 1_000});
        track.add(new double[]{95.0, 16.3, start + 3_000});
        track.add(new double[]{60.0, 16.0, start - 1});
        List<GpxExporter.Waypoint> points = Arrays.asList(
                new GpxExporter.Waypoint(60.3, 16.3, start + 3_000,
                        "Pike & perch", "Catch", "Rock < edge"),
                new GpxExporter.Waypoint(60.4, 16.4, start + 4_000,
                        "", "Waypoint", ""),
                new GpxExporter.Waypoint(60.5, 181.0, start + 5_000,
                        "Invalid", "Waypoint", ""));

        String xml = GpxExporter.create("Morning <trip>", start, end, track, points);
        check(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), "XML declaration");
        check(xml.contains("version=\"1.1\""), "GPX 1.1");
        check(xml.contains("Morning &lt;trip&gt;"), "trip name escaped");
        check(xml.contains("Pike &amp; perch"), "waypoint name escaped");
        check(xml.contains("Rock &lt; edge"), "description escaped");
        check(xml.contains("<name>Waypoint</name>"), "type used as fallback name");
        check(count(xml, "<trkpt ") == 2, "invalid and out-of-range track points omitted");
        check(count(xml, "<wpt ") == 2, "invalid waypoint omitted");
        check(xml.indexOf("60.1000000") < xml.indexOf("60.2000000"), "track sorted by time");
        check(xml.contains("2023-11-14T22:13:21.000Z"), "UTC timestamp");
        System.out.println("GpxExporterTest: " + passed + " checks passed");
    }

    private static int count(String value, String token) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(token, index)) >= 0; index += token.length()) count++;
        return count;
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        passed++;
    }
}
