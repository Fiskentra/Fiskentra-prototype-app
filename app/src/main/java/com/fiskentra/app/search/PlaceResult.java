package com.fiskentra.app.search;

import com.fiskentra.app.model.FieldNavigation;

/** Normalized provider result; coordinates are always latitude/longitude, bbox is west/south/east/north. */
public final class PlaceResult {
    public final String providerId, resultId, name, context, type, attribution;
    public final double latitude, longitude;
    private final double[] bounds;
    public final boolean cached;
    public final long fetchedAtUtc;
    public PlaceResult(String providerId, String resultId, String name, String context, String type,
            double latitude, double longitude, double[] bounds, String attribution, boolean cached, long fetchedAtUtc) {
        if (!FieldNavigation.validCoordinate(latitude, longitude) || resultId == null || resultId.isEmpty() || name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Invalid place");
        this.providerId = providerId; this.resultId = resultId; this.name = name; this.context = context == null ? "" : context;
        this.type = type == null ? "" : type; this.latitude = latitude; this.longitude = longitude;
        this.bounds = validBounds(bounds) ? bounds.clone() : null; this.attribution = attribution == null ? "" : attribution;
        this.cached = cached; this.fetchedAtUtc = fetchedAtUtc;
    }
    public double[] bounds() { return bounds == null ? null : bounds.clone(); }
    public boolean areaDestination() {
        String[] kinds = type.toLowerCase(java.util.Locale.ROOT).trim().split("\\s*·\\s*", -1);
        // A provider bbox also describes individual buildings; it does not imply an area target.
        if (kinds[0].equals("address")) return false;
        for (String kind : kinds) {
            switch (kind.trim()) {
                case "water": case "lake": case "river": case "marine": case "continental_marine":
                case "major_landform": case "country": case "region": case "subregion": case "county":
                case "joint_municipality": case "joint_submunicipality": case "municipality":
                case "municipal_district": case "locality": case "neighbourhood": case "place":
                case "postal_code": case "park": case "national_park": case "road": case "street":
                    return true;
            }
        }
        if (kinds[0].equals("poi")) return false;
        return bounds != null;
    }
    public static boolean validBounds(double[] b) {
        return b != null && b.length == 4 && FieldNavigation.validCoordinate(b[1], b[0]) && FieldNavigation.validCoordinate(b[3], b[2]) && b[1] <= b[3] && b[0] <= b[2];
    }
}
