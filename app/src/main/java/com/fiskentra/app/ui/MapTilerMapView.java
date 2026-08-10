package com.fiskentra.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.location.Location;
import android.view.View;
import android.widget.FrameLayout;

import com.fiskentra.app.BuildConfig;
import com.fiskentra.app.model.SavedPoint;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;

import java.util.Collections;
import java.util.List;

public final class MapTilerMapView extends FrameLayout {
    private static final String MAP_STYLE_ID = "outdoor-v2";
    private static final double DEFAULT_LAT = 56.9496;
    private static final double DEFAULT_LON = 24.1052;

    private final MapView mapView;
    private final MarkerOverlay overlay;
    private MapLibreMap mapLibreMap;
    private Location location;
    private List<SavedPoint> points = Collections.emptyList();
    private SavedPoint selectedPoint;
    private boolean cameraMoved;
    private boolean started;
    private boolean resumed;
    private boolean destroyed;

    public MapTilerMapView(Context context) {
        super(context);
        MapLibre.getInstance(context);
        mapView = new MapView(context);
        mapView.onCreate(null);
        addView(mapView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        overlay = new MarkerOverlay(context);
        addView(overlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mapView.getMapAsync(map -> {
            mapLibreMap = map;
            mapLibreMap.setStyle(styleUrl());
            mapLibreMap.addOnCameraIdleListener(overlay::invalidate);
            moveCameraIfReady();
            overlay.invalidate();
        });
    }

    public void setData(Location location, List<SavedPoint> points, List<double[]> track, SavedPoint selectedPoint) {
        this.location = location;
        this.points = points == null ? Collections.emptyList() : points;
        this.selectedPoint = selectedPoint;
        overlay.setData(this.points, track);
        moveCameraIfReady();
    }

    public void start() {
        if (destroyed || started) return;
        mapView.onStart();
        started = true;
    }

    public void resume() {
        if (destroyed || resumed) return;
        mapView.onResume();
        resumed = true;
    }

    public void pause() {
        if (destroyed || !resumed) return;
        mapView.onPause();
        resumed = false;
    }

    public void stop() {
        if (destroyed || !started) return;
        mapView.onStop();
        started = false;
    }

    public void destroy() {
        if (destroyed) return;
        pause();
        stop();
        mapView.onDestroy();
        destroyed = true;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
        resume();
    }

    @Override protected void onDetachedFromWindow() {
        destroy();
        super.onDetachedFromWindow();
    }

    public void onLowMemory() {
        mapView.onLowMemory();
    }

    private void moveCameraIfReady() {
        if (mapLibreMap == null) return;
        double lat = DEFAULT_LAT;
        double lon = DEFAULT_LON;
        double zoom = 11.5;
        if (selectedPoint != null) {
            lat = selectedPoint.latitude;
            lon = selectedPoint.longitude;
            zoom = 14.5;
        } else if (!points.isEmpty()) {
            CameraTarget target = savedPointsCameraTarget();
            lat = target.latitude;
            lon = target.longitude;
            zoom = target.zoom;
        } else if (location != null) {
            lat = location.getLatitude();
            lon = location.getLongitude();
            zoom = 13.0;
        }
        if (cameraMoved && selectedPoint == null) {
            overlay.invalidate();
            return;
        }
        mapLibreMap.setCameraPosition(new CameraPosition.Builder()
                .target(new LatLng(lat, lon))
                .zoom(zoom)
                .build());
        cameraMoved = true;
        overlay.invalidate();
    }

    private CameraTarget savedPointsCameraTarget() {
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE;
        for (SavedPoint point : points) {
            minLat = Math.min(minLat, point.latitude);
            maxLat = Math.max(maxLat, point.latitude);
            minLon = Math.min(minLon, point.longitude);
            maxLon = Math.max(maxLon, point.longitude);
        }
        if (location != null) {
            minLat = Math.min(minLat, location.getLatitude());
            maxLat = Math.max(maxLat, location.getLatitude());
            minLon = Math.min(minLon, location.getLongitude());
            maxLon = Math.max(maxLon, location.getLongitude());
        }
        double span = Math.max(Math.abs(maxLat - minLat), Math.abs(maxLon - minLon));
        return new CameraTarget(
                (minLat + maxLat) / 2.0,
                (minLon + maxLon) / 2.0,
                zoomForSpan(span)
        );
    }

    private double zoomForSpan(double span) {
        if (span < 0.002) return 16.0;
        if (span < 0.006) return 15.0;
        if (span < 0.015) return 14.0;
        if (span < 0.04) return 13.0;
        if (span < 0.10) return 12.0;
        if (span < 0.25) return 11.0;
        if (span < 0.60) return 10.0;
        if (span < 1.20) return 9.0;
        return 8.0;
    }

    private String styleUrl() {
        return "https://api.maptiler.com/maps/" + MAP_STYLE_ID + "/style.json?key=" + BuildConfig.MAPTILER_API_KEY;
    }

    private final class MarkerOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<SavedPoint> points = Collections.emptyList();
        private List<double[]> track = Collections.emptyList();

        MarkerOverlay(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        void setData(List<SavedPoint> points, List<double[]> track) {
            this.points = points == null ? Collections.emptyList() : points;
            this.track = track == null ? Collections.emptyList() : track;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (mapLibreMap == null) return;
            drawTrack(canvas);
            drawSavedPoints(canvas);
            drawCurrentLocation(canvas);
        }

        private void drawTrack(Canvas canvas) {
            if (track.size() < 2) return;
            paint.setColor(Color.rgb(99, 190, 116));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(6f);
            PointF previous = null;
            for (double[] point : track) {
                PointF screenPoint = mapLibreMap.getProjection().toScreenLocation(new LatLng(point[0], point[1]));
                if (previous != null) canvas.drawLine(previous.x, previous.y, screenPoint.x, screenPoint.y, paint);
                previous = screenPoint;
            }
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawSavedPoints(Canvas canvas) {
            for (SavedPoint point : points) {
                PointF screenPoint = mapLibreMap.getProjection().toScreenLocation(new LatLng(point.latitude, point.longitude));
                boolean selected = selectedPoint != null && selectedPoint.id == point.id;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(colorFor(point.type, selected));
                canvas.drawCircle(screenPoint.x, screenPoint.y, selected ? 18f : 12f, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(selected ? 5f : 3f);
                paint.setColor(Color.WHITE);
                canvas.drawCircle(screenPoint.x, screenPoint.y, selected ? 27f : 18f, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawCurrentLocation(Canvas canvas) {
            if (location == null) return;
            PointF screenPoint = mapLibreMap.getProjection().toScreenLocation(
                    new LatLng(location.getLatitude(), location.getLongitude()));
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(screenPoint.x, screenPoint.y, 13f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5f);
            paint.setColor(Color.rgb(121, 227, 143));
            canvas.drawCircle(screenPoint.x, screenPoint.y, 20f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private int colorFor(String type, boolean selected) {
            if (selected) return Color.rgb(0, 174, 213);
            if ("Catch".equals(type)) return Color.rgb(83, 214, 137);
            if ("Waypoint".equals(type)) return Color.rgb(244, 190, 85);
            if ("Tackle change".equals(type)) return Color.rgb(197, 155, 255);
            return Color.rgb(121, 227, 143);
        }
    }

    private static final class CameraTarget {
        final double latitude;
        final double longitude;
        final double zoom;

        CameraTarget(double latitude, double longitude, double zoom) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.zoom = zoom;
        }
    }
}
