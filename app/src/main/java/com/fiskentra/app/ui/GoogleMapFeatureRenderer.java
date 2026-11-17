package com.fiskentra.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import com.fiskentra.app.model.FieldNavigation;
import com.fiskentra.app.model.RoadRoute;
import com.fiskentra.app.model.SavedPoint;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.Projection;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.RoundCap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Google Maps overlays. Clusters are visual only; saved geographic coordinates stay intact. */
final class GoogleMapFeatureRenderer implements AutoCloseable {
    private final GoogleMap map;
    private final MapMarkerImages markerImages;
    private final float density;
    private final Map<String, Displayed> displayed = new LinkedHashMap<>();
    private final List<Polyline> trackLines = new ArrayList<>(), returnLines = new ArrayList<>();
    private final Map<String, Sprite> sprites = new LinkedHashMap<String, Sprite>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Sprite> oldest) { return size() > 256; }
    };
    private List<SavedPoint> points = Collections.emptyList();
    private SavedPoint selected;
    private long pointSignature = Long.MIN_VALUE, trackSignature = Long.MIN_VALUE, returnSignature = Long.MIN_VALUE;
    private boolean closed, pointsVisible = true, trackVisible = true, labelsVisible = true;

    GoogleMapFeatureRenderer(Context context, GoogleMap map) {
        this.map = map;
        markerImages = new MapMarkerImages(context);
        density = context.getResources().getDisplayMetrics().density;
    }

    void visibility(boolean points, boolean track, boolean labels) {
        if (closed) return;
        boolean redraw = pointsVisible != points || labelsVisible != labels;
        pointsVisible = points; trackVisible = track; labelsVisible = labels;
        for (Polyline line : trackLines) line.setVisible(track);
        if (redraw) refreshCamera();
    }

    void data(List<SavedPoint> pointList, List<double[]> line, SavedPoint chosen,
            RoadRoute dormantRoadRoute, List<double[]> reverse) {
        if (closed) return;
        points = pointList == null ? Collections.emptyList() : pointList;
        selected = chosen;
        long signature = chosen == null ? 0 : chosen.id;
        for (SavedPoint point : points) {
            signature = 31 * signature + point.id;
            signature = 31 * signature + Double.doubleToLongBits(point.latitude);
            signature = 31 * signature + Double.doubleToLongBits(point.longitude);
            signature = 31 * signature + appearance(point).hashCode();
        }
        if (signature != pointSignature) { pointSignature = signature; refreshCamera(); }
        long nextTrack = lineSignature(line);
        if (nextTrack != trackSignature) {
            trackSignature = nextTrack; replaceLines(trackLines, line, 6, 3, trackVisible);
        }
        long nextReturn = lineSignature(reverse);
        if (nextReturn != returnSignature) {
            returnSignature = nextReturn; replaceLines(returnLines, reverse, 8, 5, true);
        }
        // Road routing is intentionally dormant. Boat guidance never follows a road route.
    }

    /** Recluster after camera movement, retaining unchanged native marker instances. */
    void refreshCamera() {
        if (closed) return;
        if (!pointsVisible) {
            for (Displayed item : displayed.values()) item.marker.setVisible(false);
            return;
        }
        Projection projection = map.getProjection();
        LatLngBounds visible = projection.getVisibleRegion().latLngBounds;
        float radius = 42 * density;
        Map<Long, List<Group>> buckets = new HashMap<>();
        List<Group> groups = new ArrayList<>();
        for (SavedPoint point : points) {
            if (!point.hasLocation() || selected != null && selected.id == point.id) continue;
            LatLng coordinate = new LatLng(point.latitude, point.longitude);
            if (!visible.contains(coordinate)) continue;
            Point screen = projection.toScreenLocation(coordinate);
            int cellX = (int) Math.floor(screen.x / radius), cellY = (int) Math.floor(screen.y / radius);
            Group nearest = null; double nearestDistance = radius;
            for (int x = cellX - 1; x <= cellX + 1; x++) for (int y = cellY - 1; y <= cellY + 1; y++) {
                List<Group> neighbors = buckets.get(cell(x, y));
                if (neighbors == null) continue;
                for (Group group : neighbors) {
                    double distance = Math.hypot(group.anchor.x - screen.x, group.anchor.y - screen.y);
                    if (distance <= nearestDistance) { nearest = group; nearestDistance = distance; }
                }
            }
            if (nearest == null) {
                nearest = new Group(screen); groups.add(nearest);
                buckets.computeIfAbsent(cell(cellX, cellY), key -> new ArrayList<>()).add(nearest);
            }
            nearest.members.add(point); nearest.sumX += screen.x; nearest.sumY += screen.y;
        }
        Set<String> retained = new HashSet<>();
        for (Group group : groups) {
            group.members.sort((a, b) -> Long.compare(a.id, b.id));
            StringBuilder keyBuilder = new StringBuilder();
            for (SavedPoint point : group.members) keyBuilder.append(point.id).append(',');
            String key = keyBuilder.toString(); retained.add(key);
            SavedPoint first = group.members.get(0); boolean cluster = group.members.size() > 1;
            LatLng position = cluster ? projection.fromScreenLocation(new Point((int) (group.sumX / group.members.size()),
                    (int) (group.sumY / group.members.size()))) : new LatLng(first.latitude, first.longitude);
            String imageKey = cluster ? "cluster:" + group.members.size() : appearance(first) + ":labels:" + labelsVisible;
            Displayed item = displayed.get(key);
            if (item == null) {
                Sprite sprite = sprite(group.members, imageKey);
                Marker marker = map.addMarker(new MarkerOptions().position(position)
                        .icon(sprite.icon).anchor(sprite.anchorX, .5f).flat(false).zIndex(4));
                if (marker == null) continue;
                item = new Displayed(marker, imageKey); displayed.put(key, item);
            } else {
                if (!position.equals(item.marker.getPosition())) item.marker.setPosition(position);
                if (!imageKey.equals(item.imageKey)) {
                    Sprite sprite = sprite(group.members, imageKey); item.marker.setIcon(sprite.icon);
                    item.marker.setAnchor(sprite.anchorX, .5f); item.imageKey = imageKey;
                }
                item.marker.setVisible(true);
            }
            item.members = new ArrayList<>(group.members); item.marker.setTag(item);
        }
        java.util.Iterator<Map.Entry<String, Displayed>> entries = displayed.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, Displayed> entry = entries.next();
            if (!retained.contains(entry.getKey())) { entry.getValue().marker.remove(); entries.remove(); }
        }
    }

    private static long cell(int x, int y) { return ((long) x << 32) ^ (y & 0xffffffffL); }
    private static String appearance(SavedPoint point) {
        return MapMarkerImages.id(point) + ':' + point.color + ':' + point.size + ':' + point.title;
    }

    private Sprite sprite(List<SavedPoint> members, String key) {
        Sprite cached = sprites.get(key); if (cached != null) return cached;
        boolean cluster = members.size() > 1; SavedPoint point = members.get(0);
        float radius = (cluster ? 19 : Math.max(10, point.size * .72f)) * density;
        int diameter = (int) Math.ceil(2 * radius + 4 * density);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize((cluster ? 15 : 12) * density);
        String label = labelsVisible && !cluster ? point.title : "";
        if (label.length() > 26) label = label.substring(0, 25) + "\u2026";
        int labelWidth = label.isEmpty() ? 0 : (int) Math.ceil(paint.measureText(label) + 10 * density);
        Bitmap bitmap = Bitmap.createBitmap(diameter + labelWidth, diameter, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(android.util.DisplayMetrics.DENSITY_DEFAULT);
        Canvas canvas = new Canvas(bitmap); float center = diameter / 2f;
        paint.setColor(cluster ? 0xff001e2e : point.color == 0 ? MapTilerMapView.colorFor(point.type) : point.color);
        canvas.drawCircle(center, center, radius, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * density);
        paint.setColor(cluster ? 0xff35dfd1 : Color.WHITE); canvas.drawCircle(center, center, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        if (cluster) {
            paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(Integer.toString(members.size()), center, center - (paint.ascent() + paint.descent()) / 2, paint);
        } else {
            float halfGlyph = 12 * density * point.size / 18f;
            canvas.drawBitmap(markerImages.get(point), null,
                    new RectF(center - halfGlyph, center - halfGlyph, center + halfGlyph, center + halfGlyph), paint);
            if (!label.isEmpty()) {
                paint.setTextAlign(Paint.Align.LEFT);
                float x = diameter + 4 * density, y = center - (paint.ascent() + paint.descent()) / 2;
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3 * density); paint.setColor(0xff001e2e);
                canvas.drawText(label, x, y, paint); paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
                canvas.drawText(label, x, y, paint);
            }
        }
        Sprite result = new Sprite(BitmapDescriptorFactory.fromBitmap(bitmap), center / bitmap.getWidth());
        sprites.put(key, result); return result;
    }

    private static long lineSignature(List<double[]> line) {
        long signature = 1;
        if (line != null) for (double[] coordinate : line) {
            signature = 31 * signature + (coordinate == null ? 0 : coordinate.length);
            if (coordinate != null) for (double value : coordinate) signature = 31 * signature + Double.doubleToLongBits(value);
        }
        return signature;
    }

    private void replaceLines(List<Polyline> existing, List<double[]> coordinates, float casingWidth, float width, boolean visible) {
        for (Polyline line : existing) line.remove(); existing.clear();
        if (coordinates == null) return;
        List<LatLng> part = new ArrayList<>(); double[] previous = null;
        for (double[] coordinate : coordinates) {
            boolean valid = coordinate != null && coordinate.length >= 2 && FieldNavigation.validCoordinate(coordinate[0], coordinate[1]);
            if (!valid || previous != null && FieldNavigation.segmentBreak(previous, coordinate)) {
                addLine(existing, part, casingWidth, width, visible); part = new ArrayList<>();
            }
            if (valid) part.add(new LatLng(coordinate[0], coordinate[1]));
            previous = valid ? coordinate : null;
        }
        addLine(existing, part, casingWidth, width, visible);
    }

    private void addLine(List<Polyline> lines, List<LatLng> part, float casingWidth, float width, boolean visible) {
        if (part.size() < 2) return;
        lines.add(map.addPolyline(new PolylineOptions().addAll(part).color(0xff001e2e)
                .width(casingWidth * density).startCap(new RoundCap()).endCap(new RoundCap()).geodesic(true).visible(visible).zIndex(1)));
        lines.add(map.addPolyline(new PolylineOptions().addAll(part).color(0xff35dfd1)
                .width(width * density).startCap(new RoundCap()).endCap(new RoundCap()).geodesic(true).visible(visible).zIndex(2)));
    }

    boolean tapMarker(Marker marker, MapTilerMapView.Listener listener) {
        if (closed || !pointsVisible || listener == null || !(marker.getTag() instanceof Displayed)) return false;
        activate((Displayed) marker.getTag(), listener); return true;
    }
    boolean tap(PointF screen, MapTilerMapView.Listener listener) {
        if (closed || !pointsVisible || listener == null) return false;
        double distance = 26 * density; Displayed nearest = null; Projection projection = map.getProjection();
        for (Displayed item : displayed.values()) {
            if (!item.marker.isVisible()) continue;
            Point shown = projection.toScreenLocation(item.marker.getPosition());
            double candidate = Math.hypot(screen.x - shown.x, screen.y - shown.y);
            if (candidate <= distance) { distance = candidate; nearest = item; }
        }
        if (nearest == null) return false;
        activate(nearest, listener); return true;
    }
    private void activate(Displayed item, MapTilerMapView.Listener listener) {
        if (item.members.size() == 1) { listener.onPoint(item.members.get(0)); return; }
        SavedPoint first = item.members.get(0); boolean coincident = true;
        for (SavedPoint point : item.members) if (FieldNavigation.distance(first.latitude, first.longitude, point.latitude, point.longitude) > 2) {
            coincident = false; break;
        }
        if (coincident || map.getCameraPosition().zoom >= 19) listener.onCluster(new ArrayList<>(item.members));
        else map.animateCamera(CameraUpdateFactory.newLatLngZoom(item.marker.getPosition(), Math.min(20, map.getCameraPosition().zoom + 2)), 400, null);
    }

    @Override public void close() {
        if (closed) return; closed = true;
        for (Displayed item : displayed.values()) item.marker.remove();
        for (Polyline line : trackLines) line.remove(); for (Polyline line : returnLines) line.remove();
        displayed.clear(); trackLines.clear(); returnLines.clear(); sprites.clear(); markerImages.clear();
        points = Collections.emptyList(); selected = null;
    }
    private static final class Group {
        final Point anchor; final List<SavedPoint> members = new ArrayList<>(); double sumX, sumY;
        Group(Point anchor) { this.anchor = anchor; }
    }
    private static final class Displayed {
        final Marker marker; List<SavedPoint> members = Collections.emptyList(); String imageKey;
        Displayed(Marker marker, String imageKey) { this.marker = marker; this.imageKey = imageKey; }
    }
    private static final class Sprite {
        final BitmapDescriptor icon; final float anchorX;
        Sprite(BitmapDescriptor icon, float anchorX) { this.icon = icon; this.anchorX = anchorX; }
    }
}
