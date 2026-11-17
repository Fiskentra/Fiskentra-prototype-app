package com.fiskentra.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import com.fiskentra.app.model.CatchDensityGrid;
import com.fiskentra.app.model.FishingDay;
import com.fiskentra.app.model.PastTripGeometry;
import com.fiskentra.app.model.PastTripPalette;
import com.fiskentra.app.model.SavedPoint;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.RoundCap;
import com.google.android.gms.maps.model.Tile;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.android.gms.maps.model.TileProvider;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline overlays, independent of point marker and current-track visibility. Call mutations on the UI thread. */
final class LocalFishingLayers implements AutoCloseable {
    static final int MAX_HISTORY_TRIPS = 12;
    private static final int MAX_POINTS_PER_TRIP = 8000;
    private static final int[] COLORS = {0xffffb74d, 0xffab8cff, 0xffef74b5, 0xff70bfff,
            0xffd5e65a, 0xff77d7ab, 0xffff8b71, 0xffc5a5ff, 0xff73d5df, 0xffeec96f, 0xffc78eb0, 0xff9fbe72};
    private final GoogleMap map;
    private final float density;
    private final List<Polyline> historyLines = new ArrayList<>();
    private TileOverlay heatOverlay;
    private HeatTiles heatTiles;
    private long heatSourceSignature = Long.MIN_VALUE, historySignature = Long.MIN_VALUE;
    private boolean heatEnabled, historyEnabled, closed;
    private int catchCount, tripCount, availableTripCount;
    private List<FishingDay> lastHistoryInput;
    private Set<String> lastHistorySelection = Collections.emptySet();

    LocalFishingLayers(Context context, GoogleMap map) {
        this.map = map;
        density = context.getResources().getDisplayMetrics().density;
    }

    /** Empty selectedIds means all completed trips; nonempty ids select matching trips only. */
    void update(boolean heatmap, boolean history, List<SavedPoint> points, List<FishingDay> days,
            Set<String> selectedIds, float opacity) {
        if (closed) return;
        boolean historyWasVisible = historyEnabled;
        heatEnabled = heatmap;
        historyEnabled = history;
        if (heatmap) {
            long nextHeatSignature = 1;
            int nextCatchCount = 0;
            if (points != null) for (SavedPoint point : points) {
                if (point != null && "Catch".equals(point.type) && point.hasLocation()) {
                    nextCatchCount++;
                    nextHeatSignature = 31 * nextHeatSignature + Double.doubleToLongBits(point.latitude);
                    nextHeatSignature = 31 * nextHeatSignature + Double.doubleToLongBits(point.longitude);
                }
            }
            catchCount = nextCatchCount;
            if (nextHeatSignature != heatSourceSignature || heatOverlay == null && catchCount > 0) {
                ArrayList<double[]> catches = new ArrayList<>();
                if (points != null) for (SavedPoint point : points) {
                    if (point != null && "Catch".equals(point.type) && point.hasLocation()) catches.add(new double[]{point.latitude, point.longitude});
                }
                removeHeatmap();
                heatSourceSignature = nextHeatSignature;
                if (catchCount > 0) {
                    heatTiles = new HeatTiles(new CatchDensityGrid(catches));
                    heatOverlay = map.addTileOverlay(new TileOverlayOptions().tileProvider(heatTiles)
                            .fadeIn(false).zIndex(.6f).transparency(1 - clamp(opacity)));
                }
            }
            if (heatOverlay != null) { heatOverlay.setVisible(true); heatOverlay.setTransparency(1 - clamp(opacity)); }
        } else if (heatOverlay != null) heatOverlay.setVisible(false);

        if (!history) {
            if (historyWasVisible) for (Polyline line : historyLines) line.setVisible(false);
            return;
        }
        Set<String> selection = selectedIds == null ? Collections.emptySet() : selectedIds;
        if (days == lastHistoryInput && selection.equals(lastHistorySelection)) {
            if (!historyWasVisible) for (Polyline line : historyLines) line.setVisible(true);
            return;
        }
        lastHistoryInput = days;
        lastHistorySelection = new HashSet<>(selection);
        List<FishingDay> eligible = new ArrayList<>();
        if (days != null) for (FishingDay day : days) {
            if (day != null && !day.isActive() && PastTripGeometry.hasLine(day.route)
                    && (selectedIds == null || selectedIds.isEmpty() || selectedIds.contains(Long.toString(day.id)))) eligible.add(day);
        }
        eligible.sort(Comparator.comparingLong((FishingDay day) -> day.startedAt).reversed());
        availableTripCount = eligible.size();
        if (eligible.size() > MAX_HISTORY_TRIPS) eligible = new ArrayList<>(eligible.subList(0, MAX_HISTORY_TRIPS));
        tripCount = eligible.size();
        long[] ids = new long[eligible.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = eligible.get(i).id;
        int[] paletteSlots = PastTripPalette.slots(ids, COLORS.length);
        long nextSignature = 1;
        for (int i = 0; i < eligible.size(); i++) {
            FishingDay day = eligible.get(i);
            nextSignature = 31 * nextSignature + day.id;
            nextSignature = 31 * nextSignature + paletteSlots[i];
            for (double[] coordinate : day.route) {
                nextSignature = 31 * nextSignature + coordinate.length;
                for (double value : coordinate) nextSignature = 31 * nextSignature + Double.doubleToLongBits(value);
            }
        }
        if (historySignature != nextSignature) {
            removeHistory();
            historySignature = nextSignature;
            for (int i = 0; i < eligible.size(); i++) renderTrip(eligible.get(i), COLORS[paletteSlots[i]]);
        }
        for (Polyline line : historyLines) line.setVisible(true);
    }

    String status() {
        if (closed) return "";
        String heat = !heatEnabled ? "" : catchCount == 0 ? "Catch heatmap: no catches with GPS in the current filters"
                : "Catch heatmap: " + catchCount + (catchCount == 1 ? " recorded catch" : " recorded catches");
        String history = !historyEnabled ? "" : tripCount == 0 ? "Past trips: no completed tracks selected"
                : "Past trips: " + tripCount + (tripCount == 1 ? " track" : " tracks")
                + (availableTripCount > MAX_HISTORY_TRIPS ? " (latest " + MAX_HISTORY_TRIPS + " selected)" : "");
        return heat + (!heat.isEmpty() && !history.isEmpty() ? "\n" : "") + history;
    }

    private void renderTrip(FishingDay day, int color) {
        for (List<double[]> coordinates : PastTripGeometry.parts(day.route, MAX_POINTS_PER_TRIP)) {
            List<LatLng> part = new ArrayList<>();
            for (double[] point : coordinates) part.add(new LatLng(point[0], point[1]));
            addHistoryPart(part, color);
        }
    }

    private void addHistoryPart(List<LatLng> part, int color) {
        if (part.size() < 2) return;
        Polyline casing = map.addPolyline(new PolylineOptions().addAll(part).color(0xdd001e2e).width(6 * density)
                .geodesic(true).startCap(new RoundCap()).endCap(new RoundCap()).zIndex(.9f));
        Polyline line = map.addPolyline(new PolylineOptions().addAll(part).color(color).width(3 * density)
                .geodesic(true).startCap(new RoundCap()).endCap(new RoundCap()).zIndex(1.1f));
        if (casing != null) historyLines.add(casing);
        if (line != null) historyLines.add(line);
    }

    private static float clamp(float value) { return Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : .65f; }
    private void removeHeatmap() {
        if (heatOverlay != null) heatOverlay.remove();
        heatOverlay = null;
        if (heatTiles != null) heatTiles.close();
        heatTiles = null;
    }
    private void removeHistory() { for (Polyline line : historyLines) line.remove(); historyLines.clear(); }
    @Override public void close() { if (closed) return; closed = true; removeHeatmap(); removeHistory(); lastHistoryInput = null; lastHistorySelection = Collections.emptySet(); }

    private static final class HeatTiles implements TileProvider, AutoCloseable {
        private final CatchDensityGrid grid;
        private boolean disposed;
        private final Map<String, Tile> cache = new LinkedHashMap<String, Tile>(32, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Tile> entry) { return size() > 64; }
        };
        HeatTiles(CatchDensityGrid grid) { this.grid = grid; }
        @Override public synchronized Tile getTile(int x, int y, int zoom) {
            if (disposed) return NO_TILE;
            String key = zoom + "/" + x + "/" + y;
            Tile cached = cache.get(key);
            if (cached != null) return cached;
            float[] values = grid.tileDensity(x, y, zoom);
            if (values == null) { cache.put(key, NO_TILE); return NO_TILE; }
            int[] colors = new int[values.length];
            for (int i = 0; i < values.length; i++) colors[i] = color(values[i]);
            Bitmap bitmap = Bitmap.createBitmap(colors, CatchDensityGrid.TILE_SIZE, CatchDensityGrid.TILE_SIZE, Bitmap.Config.ARGB_8888);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            bitmap.recycle();
            Tile tile = new Tile(CatchDensityGrid.TILE_SIZE, CatchDensityGrid.TILE_SIZE, bytes.toByteArray());
            cache.put(key, tile);
            return tile;
        }
        private static int color(float density) {
            if (density < .005f) return 0;
            float intensity = (float) (1 - Math.exp(-density / 2));
            float alpha = Math.min(1, density * 1.1f);
            int red, green, blue;
            if (intensity < .5f) { float blend = intensity * 2; red = (int) (255 * blend); green = 220; blue = (int) (235 * (1 - blend)); }
            else { float blend = (intensity - .5f) * 2; red = 255; green = (int) (220 * (1 - blend)); blue = 0; }
            return ((int) (alpha * 230) << 24) | (red << 16) | (green << 8) | blue;
        }
        @Override public synchronized void close() { disposed = true; cache.clear(); }
    }
}
