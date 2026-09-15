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
        return bounds != null || type.matches(".*(water|lake|river|marine|region|country|landform|municipality|park).*" );
    }
    public static boolean validBounds(double[] b) {
        return b != null && b.length == 4 && FieldNavigation.validCoordinate(b[1], b[0]) && FieldNavigation.validCoordinate(b[3], b[2]) && b[1] <= b[3] && b[0] <= b[2];
    }
}
