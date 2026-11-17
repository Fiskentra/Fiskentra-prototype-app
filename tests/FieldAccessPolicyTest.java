import com.fiskentra.app.model.FieldAccessPolicy;

import java.util.Arrays;
import java.util.List;

public final class FieldAccessPolicyTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // A first request and an upgrade from approximate access both lack a precise grant.
        List<String> firstRequest = Arrays.asList(FieldAccessPolicy.locationPermissionsToRequest(false));
        check(firstRequest.contains("android.permission.ACCESS_FINE_LOCATION"), "Request precise access");
        check(firstRequest.contains("android.permission.ACCESS_COARSE_LOCATION"),
                "Never request fine alone, even when coarse was already granted");
        check(firstRequest.size() == 2, "Request the location pair once");
        check(FieldAccessPolicy.locationPermissionsToRequest(true).length == 0,
                "Do not prompt again after precise access is granted");

        int location = FieldAccessPolicy.LOCATION;
        int button = FieldAccessPolicy.CONNECTED_DEVICE;
        check(FieldAccessPolicy.backgroundCapabilities(false, false, false, false) == 0,
                "No permissions means no foreground service");
        check(FieldAccessPolicy.backgroundCapabilities(true, false, true, false) == 0,
                "Recording with location disabled and no paired button must not start a type-zero service");
        check(FieldAccessPolicy.backgroundCapabilities(false, true, false, true) == 0,
                "Enabled hardware does not replace runtime permissions");
        check(FieldAccessPolicy.backgroundCapabilities(true, true, false, false) == location,
                "GPS-only recording works without a Flic button or Bluetooth access");
        check(FieldAccessPolicy.backgroundCapabilities(false, false, true, true) == button,
                "A paired button can capture without location permission");
        check(FieldAccessPolicy.backgroundCapabilities(true, false, true, true) == button,
                "Turning off location leaves a valid connected-device service");
        check(FieldAccessPolicy.backgroundCapabilities(true, true, true, true) == (location | button),
                "Enable both service types only when both capabilities are available");
        check(FieldAccessPolicy.backgroundCapabilities(true, true, false, true) == location,
                "Revoked Bluetooth permission leaves GPS recording available");
        check(FieldAccessPolicy.backgroundCapabilities(false, true, true, false) == 0,
                "Bluetooth permission alone does not create field work");
        System.out.println("FieldAccessPolicy: " + checks + " checks passed");
    }
}
