package android.location;

/** JVM distance implementation for exercising the actual cache's geographic limits. */
public final class Location {
    public static void distanceBetween(double latitude, double longitude,
            double otherLatitude, double otherLongitude, float[] result) {
        result[0] = (float) com.fiskentra.app.model.FieldNavigation.distance(
                latitude, longitude, otherLatitude, otherLongitude);
    }
}
