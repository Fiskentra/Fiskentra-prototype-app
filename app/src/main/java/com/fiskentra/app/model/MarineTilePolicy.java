package com.fiskentra.app.model;

import java.util.Locale;

/** XYZ tiles and WMS 1.1.1 use the same west/south/east/north Web Mercator bounds. */
public final class MarineTilePolicy {
    public static final double HALF_WORLD_METERS = 20037508.342789244;
    private MarineTilePolicy() { }

    public static boolean valid(int x, int y, int zoom) {
        return zoom >= 0 && zoom <= 22 && y >= 0 && y < (1L << zoom);
    }

    public static int wrapX(int x, int zoom) {
        if (zoom < 0 || zoom > 22) throw new IllegalArgumentException("Invalid tile zoom");
        return Math.floorMod(x, 1 << zoom);
    }

    public static double[] bounds(int x, int y, int zoom) {
        if (!valid(x, y, zoom)) throw new IllegalArgumentException("Invalid tile coordinate");
        double span = 2 * HALF_WORLD_METERS / (1L << zoom);
        double west = -HALF_WORLD_METERS + wrapX(x, zoom) * span;
        double north = HALF_WORLD_METERS - y * span;
        return new double[]{west, north - span, west + span, north};
    }

    public static String depthUrl(int x, int y, int zoom) {
        double[] b = bounds(x, y, zoom);
        return String.format(Locale.US,
                "https://ows.emodnet-bathymetry.eu/wms?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap"
                + "&LAYERS=emodnet:mean_multicolour,emodnet:contours&STYLES=&SRS=EPSG:3857"
                + "&BBOX=%.8f,%.8f,%.8f,%.8f&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=TRUE",
                b[0], b[1], b[2], b[3]);
    }

    public static String seamarkUrl(int x, int y, int zoom) {
        if (!valid(x, y, zoom)) throw new IllegalArgumentException("Invalid tile coordinate");
        return "https://tiles.openseamap.org/seamark/" + zoom + "/" + wrapX(x, zoom) + "/" + y + ".png";
    }

    /** Coverage identity ignores GPS/padding jitter until the source-tile footprint changes. */
    public static String viewSignature(double south, double west, double north, double east, double zoom) {
        if (!Double.isFinite(south) || !Double.isFinite(west) || !Double.isFinite(north)
                || !Double.isFinite(east) || !Double.isFinite(zoom)) return "";
        int displayZoom = Math.max(0, Math.min(22, (int) Math.floor(zoom)));
        // A fractional camera zoom may request the next tile level; use its finer footprint.
        int sourceZoom = Math.min(18, displayZoom + 1), count = 1 << sourceZoom;
        return displayZoom + ":" + sourceZoom + ":" + tileX(west, count) + ":" + tileY(north, count)
                + ":" + tileX(east, count) + ":" + tileY(south, count);
    }

    private static int tileX(double longitude, int count) {
        return Math.max(0, Math.min(count - 1, (int) Math.floor((longitude + 180) / 360 * count)));
    }

    private static int tileY(double latitude, int count) {
        double radians = Math.toRadians(Math.max(-85.0511287798066, Math.min(85.0511287798066, latitude)));
        double y = (1 - Math.log(Math.tan(radians) + 1 / Math.cos(radians)) / Math.PI) / 2 * count;
        return Math.max(0, Math.min(count - 1, (int) Math.floor(y)));
    }
}
