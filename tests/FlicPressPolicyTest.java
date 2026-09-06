import com.fiskentra.app.model.FlicPressPolicy;

public final class FlicPressPolicyTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(FlicPressPolicy.resolve(true, false, false) == FlicPressPolicy.Action.WAYPOINT,
                "Single press must save a waypoint");
        check(FlicPressPolicy.resolve(false, true, false) == FlicPressPolicy.Action.CATCH,
                "Double press must register a catch");
        check(FlicPressPolicy.resolve(false, false, true) == FlicPressPolicy.Action.TACKLE_CHANGE,
                "Hold must record a tackle change");
        check(FlicPressPolicy.resolve(false, false, false) == FlicPressPolicy.Action.NONE,
                "No gesture must not create a point");
        check(FlicPressPolicy.resolve(true, true, true) == FlicPressPolicy.Action.TACKLE_CHANGE,
                "Hold must take precedence over click flags");
        check(FlicPressPolicy.resolve(true, true, false) == FlicPressPolicy.Action.CATCH,
                "Double press must take precedence over a single-click flag");
        System.out.println("FlicPressPolicy: " + checks + " checks passed");
    }
}
