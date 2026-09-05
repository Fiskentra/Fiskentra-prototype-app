import com.fiskentra.app.model.PointMetadata;

public final class PointMetadataTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(PointMetadata.TYPES.size() == 5, "All map marker types are editable");
        check(PointMetadata.isAllowedType("Catch"), "Catch is supported");
        check(PointMetadata.isAllowedType("Tackle change"), "Tackle change is supported");
        check(!PointMetadata.isAllowedType("Unknown"), "Unknown type is rejected");
        check("North inlet".equals(PointMetadata.clean("  North inlet  ")), "Whitespace is normalized");
        check(PointMetadata.validate("", "Waypoint", "").isEmpty(), "Optional fields may be blank");
        check(!PointMetadata.validate("", "Unknown", "").isEmpty(), "Type validation runs at save boundary");
        check(PointMetadata.validate("x".repeat(80), "Camp", "n".repeat(500)).isEmpty(), "Limits are inclusive");
        check(!PointMetadata.validate("x".repeat(81), "Camp", "").isEmpty(), "Long names are rejected");
        check(!PointMetadata.validate("", "Hazard", "n".repeat(501)).isEmpty(), "Long notes are rejected");
        System.out.println("PointMetadata: " + checks + " checks passed");
    }
}
