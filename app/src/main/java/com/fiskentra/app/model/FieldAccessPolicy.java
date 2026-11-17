package com.fiskentra.app.model;

/** Permission requests and available field-service work, independent of Android lifecycle code. */
public final class FieldAccessPolicy {
    // These describe capabilities, not Android ServiceInfo foreground-service type values.
    public static final int LOCATION = 1;
    public static final int CONNECTED_DEVICE = 2;

    private FieldAccessPolicy() { }

    /** Android 12 requires coarse and fine in the same request, including a precision upgrade. */
    public static String[] locationPermissionsToRequest(boolean preciseLocationGranted) {
        return preciseLocationGranted ? new String[0] : new String[] {
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION"
        };
    }

    /** A disabled location provider cannot support a location foreground service. */
    public static int backgroundCapabilities(boolean locationGranted, boolean locationEnabled,
                                             boolean bluetoothGranted, boolean buttonPaired) {
        int capabilities = locationGranted && locationEnabled ? LOCATION : 0;
        if (bluetoothGranted && buttonPaired) capabilities |= CONNECTED_DEVICE;
        return capabilities;
    }
}
