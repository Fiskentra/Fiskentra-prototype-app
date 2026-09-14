import com.fiskentra.app.model.*;

public final class SavedPointAppearanceTest {
    private static int count;
    private static void check(boolean value, String name) { count++; if (!value) throw new AssertionError(name); }
    public static void main(String[] args) {
        SavedPoint old = new SavedPoint(1, 56.9, 24.1, 1234, "Catch", "notes");
        check(old.size == 18 && old.color == 0 && old.symbol.isEmpty(), "Old point defaults");
        SavedPoint point = old.withAppearance("My lake", "!", 0xfff67267, 26);
        check(point.id == old.id && point.latitude == old.latitude && point.timestamp == old.timestamp, "Appearance never moves a point");
        check(old.title.isEmpty(), "Original remains immutable");
        SavedPoint weather = point.withWeather(null);
        check(weather.color == point.color && weather.size == point.size && weather.symbol.equals("!"), "Weather enrichment preserves marker");
        SavedPoint catchPoint = point.withCatchDetails(new CatchDetails("Pike", 50, 2, "Lure", "Notes", true, ""));
        check(catchPoint.color == point.color && catchPoint.size == point.size, "Catch editing preserves appearance");
        SavedPoint metadata = catchPoint.withMetadata("Renamed", "Waypoint", "New note");
        check(metadata.symbol.equals("!") && metadata.title.equals("Renamed"), "Metadata editing preserves symbol");
        SavedPoint edited = metadata.withAppearance("Camp", "*", 0xff68c4ff, 12);
        check(edited.catchDetails == catchPoint.catchDetails && edited.note.equals("New note"), "Appearance preserves catch and notes");
        check(point.withAppearance("", null, 0, 200).size == 28, "Maximum size bounded");
        check(point.withAppearance("", null, 0, -1).size == 12, "Minimum size bounded");
        System.out.println("PASS: " + count + " saved point appearance checks");
    }
}
