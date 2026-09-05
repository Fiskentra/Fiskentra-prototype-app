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
import com.fiskentra.app.R;
import android.graphics.drawable.Drawable;
import com.fiskentra.app.model.SavedPoint;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MapTilerMapView extends FrameLayout {
    public static final String STYLE_OUTDOOR = "outdoor-v4";
    public static final String STYLE_HYBRID = "hybrid-v4";
    public static final String STYLE_TOPO = "topo-v4";
    public static final String STYLE_OCEAN = "ocean-v4";
    private static final double DEFAULT_LAT = 56.9496;
    private static final double DEFAULT_LON = 24.1052;

    private final MapView mapView;
    private final MarkerOverlay overlay;
    private final String styleId;
    private final StyleListener styleListener;
    private MapLibreMap mapLibreMap;
    private Location location;
    private List<SavedPoint> points = Collections.emptyList();
    private SavedPoint selectedPoint;
    private boolean cameraMoved;
    private boolean styleReady, showPoints = true, showTrack = true;
    private boolean started;
    private boolean resumed;
    private boolean destroyed;

    public interface StyleListener {
        void onStyleLoading(String styleId);
        void onStyleLoaded(String styleId);
        void onStyleError(String styleId);
    }

    public MapTilerMapView(Context context) {
        this(context, STYLE_OUTDOOR, null);
    }

    public MapTilerMapView(Context context, String requestedStyleId, StyleListener styleListener) {
        super(context);
        styleId = normalizeStyleId(requestedStyleId);
        this.styleListener = styleListener;
        MapLibre.getInstance(context);
        // The SurfaceView renderer can block the UI thread in onWindowResize on
        // the physical OPPO during tab changes. TextureView avoids that surface
        // resize wait and composes correctly with this screen's native overlays.
        mapView = new MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true));
        mapView.onCreate(null);
        mapView.addOnDidFailLoadingMapListener(error -> {
            if (this.styleListener != null) this.styleListener.onStyleError(styleId);
        });
        addView(mapView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        overlay = new MarkerOverlay(context);
        addView(overlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mapView.getMapAsync(map -> {
            mapLibreMap = map;
            if (this.styleListener != null) this.styleListener.onStyleLoading(styleId);
            mapLibreMap.setStyle(styleUrl(), style -> {
                styleReady = true;
                if (this.styleListener != null) this.styleListener.onStyleLoaded(styleId);
                moveCameraIfReady();
                overlay.invalidate();
            });
            mapLibreMap.addOnCameraIdleListener(overlay::invalidate);
            mapLibreMap.addOnCameraMoveListener(overlay::invalidate);
            moveCameraIfReady();
            overlay.invalidate();
        });
    }

    public static String normalizeStyleId(String styleId) {
        if (STYLE_HYBRID.equals(styleId)) return STYLE_HYBRID;
        if (STYLE_TOPO.equals(styleId)) return STYLE_TOPO;
        if (STYLE_OCEAN.equals(styleId)) return STYLE_OCEAN;
        return STYLE_OUTDOOR;
    }

    public static String styleName(String styleId) {
        String normalized = normalizeStyleId(styleId);
        if (STYLE_HYBRID.equals(normalized)) return "Satellite";
        if (STYLE_TOPO.equals(normalized)) return "Topographic";
        if (STYLE_OCEAN.equals(normalized)) return "Ocean";
        return "Outdoor";
    }

    public void setData(Location location, List<SavedPoint> points, List<double[]> track, SavedPoint selectedPoint) {
        this.location = location;
        this.points = points == null ? Collections.emptyList() : points;
        this.selectedPoint = selectedPoint;
        overlay.setData(this.points, track);
        moveCameraIfReady();
    }

    public void setOverlayVisibility(boolean points, boolean track) { showPoints=points;showTrack=track;overlay.invalidate(); }
    public void recenter() { if(mapLibreMap==null||location==null)return;selectedPoint=null;mapLibreMap.setCameraPosition(new CameraPosition.Builder(mapLibreMap.getCameraPosition()).target(new LatLng(location.getLatitude(),location.getLongitude())).zoom(14).build());cameraMoved=true;overlay.invalidate(); }
    public void northUp() { if(mapLibreMap!=null)mapLibreMap.setCameraPosition(new CameraPosition.Builder(mapLibreMap.getCameraPosition()).bearing(0).tilt(0).build()); }
    public void zoomBy(double amount) { if(mapLibreMap!=null)mapLibreMap.setCameraPosition(new CameraPosition.Builder(mapLibreMap.getCameraPosition()).zoom(Math.max(1,Math.min(20,mapLibreMap.getCameraPosition().zoom+amount))).build()); }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) { super.onSizeChanged(w,h,oldw,oldh);post(this::moveCameraIfReady); }

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
        if (mapLibreMap == null || !styleReady || getWidth()==0 || getHeight()==0) return;
        double lat = DEFAULT_LAT;
        double lon = DEFAULT_LON;
        double zoom = 11.5;
        if (selectedPoint != null) {
            lat = selectedPoint.latitude;
            lon = selectedPoint.longitude;
            zoom = 14.5;
        } else if (location != null) {
            lat=location.getLatitude();lon=location.getLongitude();zoom=14.0;
        } else if (!points.isEmpty()) {
            // The bounding centre of widely separated historical points can be open sea.
            // Open on the newest real moment; never present it as the live GPS position.
            SavedPoint newest=points.get(0);
            for(SavedPoint p:points)if(p.timestamp>newest.timestamp)newest=p;
            lat=newest.latitude;lon=newest.longitude;zoom=14.0;
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
        cameraMoved = location != null || !points.isEmpty() || selectedPoint != null;
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
        return "https://api.maptiler.com/maps/" + styleId + "/style.json?key=" + BuildConfig.MAPTILER_API_KEY;
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
            if(showTrack)drawTrack(canvas);
            if(showPoints)drawSavedPoints(canvas);
            // Keep the live GPS position as the topmost map element. Saved points reserve
            // its screen position and spread around it, so neither layer hides the other.
            drawCurrentLocation(canvas);
        }

        private void drawTrack(Canvas canvas) {
            if (track.size() < 2) return;
            paint.setColor(DesignScreens.BLUE);
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
            List<PointF> occupied = new ArrayList<>();
            if (location != null) {
                occupied.add(mapLibreMap.getProjection().toScreenLocation(
                        new LatLng(location.getLatitude(), location.getLongitude())));
            }
            for (SavedPoint point : points) {
                if (selectedPoint != null && selectedPoint.id == point.id) continue;
                PointF origin = mapLibreMap.getProjection().toScreenLocation(
                        new LatLng(point.latitude, point.longitude));
                PointF screenPoint = spreadOverlappingMarker(origin, occupied);
                occupied.add(screenPoint);
                drawSavedPoint(canvas, point, origin, screenPoint, false);
            }

            // Keep an explicitly selected point at its exact map coordinate and above every
            // other marker so OPEN MAP always produces an unmistakable highlight.
            if (selectedPoint != null) {
                PointF origin = mapLibreMap.getProjection().toScreenLocation(
                        new LatLng(selectedPoint.latitude, selectedPoint.longitude));
                drawSavedPoint(canvas, selectedPoint, origin, origin, true);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawSavedPoint(
                Canvas canvas,
                SavedPoint point,
                PointF origin,
                PointF screenPoint,
                boolean selected) {
            if (origin.x != screenPoint.x || origin.y != screenPoint.y) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2f);
                paint.setColor(Color.argb(150, 255, 255, 255));
                canvas.drawLine(origin.x, origin.y, screenPoint.x, screenPoint.y, paint);
            }

            float density=getResources().getDisplayMetrics().density;
            int size=Math.round((selected?30:23)*density);
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.argb(215,0,17,30));canvas.drawCircle(screenPoint.x,screenPoint.y,size*.56f,paint);
            int id="Catch".equals(point.type)?R.drawable.ic_fish:"Tackle change".equals(point.type)?R.drawable.ic_fish_hook:"Hazard".equals(point.type)?R.drawable.ic_alert_triangle:"Camp".equals(point.type)?R.drawable.ic_tent:R.drawable.ic_map_pin;
            drawIcon(canvas,id,colorFor(point.type),screenPoint,size);
        }

        private PointF spreadOverlappingMarker(PointF origin, List<PointF> occupied) {
            final float minimumDistance = 28f*getResources().getDisplayMetrics().density;
            if (isAvailable(origin, occupied, minimumDistance)) return origin;

            // Try stable rings around the true coordinate. This keeps several moments saved
            // from one fishing spot readable without pretending they have different GPS data.
            for (int slot = 0; slot < 48; slot++) {
                int ring = 1 + slot / 8;
                int position = slot % 8;
                double angle = position * (Math.PI / 4.0)
                        + (ring % 2 == 0 ? Math.PI / 8.0 : 0.0);
                float distance = ring * minimumDistance;
                PointF candidate = new PointF(
                        origin.x + (float) Math.cos(angle) * distance,
                        origin.y + (float) Math.sin(angle) * distance);
                if (isAvailable(candidate, occupied, minimumDistance * 0.85f)) return candidate;
            }
            return origin;
        }

        private boolean isAvailable(PointF candidate, List<PointF> occupied, float minimumDistance) {
            float minimumDistanceSquared = minimumDistance * minimumDistance;
            for (PointF existing : occupied) {
                float dx = candidate.x - existing.x;
                float dy = candidate.y - existing.y;
                if (dx * dx + dy * dy < minimumDistanceSquared) return false;
            }
            return true;
        }

        private void drawCurrentLocation(Canvas canvas) {
            if (location == null) return;
            PointF screenPoint = mapLibreMap.getProjection().toScreenLocation(
                    new LatLng(location.getLatitude(), location.getLongitude()));

            paint.setColor(Color.argb(170, 0, 17, 30));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(screenPoint.x, screenPoint.y, 25f, paint);

            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(screenPoint.x, screenPoint.y, 11f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5f);
            paint.setColor(DesignScreens.BLUE);
            canvas.drawCircle(screenPoint.x, screenPoint.y, 21f, paint);
            paint.setStrokeWidth(2f);
            paint.setColor(Color.rgb(0, 174, 213));
            canvas.drawCircle(screenPoint.x, screenPoint.y, 14f, paint);
            paint.setStyle(Paint.Style.FILL);
            drawIcon(canvas,R.drawable.ic_navigation,DesignScreens.BLUE,screenPoint,Math.round(28*getResources().getDisplayMetrics().density));
        }
        private void drawIcon(Canvas canvas,int id,int color,PointF p,int size){Drawable icon=getContext().getDrawable(id).mutate();icon.setTint(color);icon.setBounds(Math.round(p.x-size/2f),Math.round(p.y-size/2f),Math.round(p.x+size/2f),Math.round(p.y+size/2f));icon.draw(canvas);}

        private int colorFor(String type) {
            if ("Catch".equals(type)) return DesignScreens.LIME;
            if ("Waypoint".equals(type)) return DesignScreens.AMBER;
            if ("Tackle change".equals(type)) return Color.rgb(197, 155, 255);
            if ("Sighting".equals(type)) return Color.rgb(255, 142, 89);
            if ("Camp".equals(type)) return Color.rgb(104, 196, 255);
            if ("Hazard".equals(type)) return Color.rgb(246, 114, 103);
            if ("Map".equals(type)) return Color.rgb(0, 174, 213);
            return Color.rgb(121, 227, 143);
        }

        private String markerLetter(String type) {
            if ("Catch".equals(type)) return "C";
            if ("Waypoint".equals(type)) return "W";
            if ("Tackle change".equals(type)) return "T";
            if ("Sighting".equals(type)) return "S";
            if ("Camp".equals(type)) return "P";
            if ("Hazard".equals(type)) return "!";
            if ("Map".equals(type)) return "M";
            return "?";
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
