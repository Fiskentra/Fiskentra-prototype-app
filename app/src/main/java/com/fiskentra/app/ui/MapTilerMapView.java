package com.fiskentra.app.ui;

import android.content.Context;
import android.graphics.*;
import android.location.Location;
import android.view.View;
import android.widget.FrameLayout;
import com.fiskentra.app.BuildConfig;
import com.fiskentra.app.model.SavedPoint;
import com.fiskentra.app.model.RoadRoute;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import java.util.*;

/** Visual marker offsets never modify geographic coordinates. */
public final class MapTilerMapView extends FrameLayout {
    public static final String STYLE_OUTDOOR = "outdoor-v4", STYLE_HYBRID = "hybrid-v4", STYLE_TOPO = "topo-v4", STYLE_OCEAN = "ocean-v4";
    public interface StyleListener { void onStyleLoading(String id); void onStyleLoaded(String id); void onStyleError(String id); }
    public static String normalizeStyleId(String id) { return STYLE_HYBRID.equals(id) || STYLE_TOPO.equals(id) || STYLE_OCEAN.equals(id) ? id : STYLE_OUTDOOR; }
    public static String styleName(String id) { return STYLE_HYBRID.equals(id) ? "Satellite" : STYLE_TOPO.equals(id) ? "Topographic" : STYLE_OCEAN.equals(id) ? "Ocean" : "Outdoor"; }
    public void setOverlayVisibility(boolean points, boolean track) { setLayers(points, track, showLabels); }
    public void recenter() { locate(); }
    public void northUp() { north(); }
    public void zoomBy(double amount) { if (map != null) map.setCameraPosition(new CameraPosition.Builder(map.getCameraPosition()).zoom(Math.max(1, Math.min(20, map.getCameraPosition().zoom + amount))).build()); }
    public interface Listener {
        void onPlace(double latitude, double longitude);
        void onPoint(SavedPoint point);
        default void onCluster(List<SavedPoint> points) { onPoint(points.get(0)); }
    }
    private final MapView mapView;
    private final MarkerOverlay overlay;
    private MapLibreMap map;
    private Listener listener;
    private Location location;
    private List<SavedPoint> points = Collections.emptyList();
    private List<double[]> track = Collections.emptyList();
    private SavedPoint selected, destination;
    private RoadRoute roadRoute;
    private boolean directGuidance=true;
    private boolean started, resumed, destroyed, positioned, follow, tapToPlace;
    private boolean showPoints = true, showTrack = true, showLabels = true;
    private String baseStyle;
    private String mapStatus = "Loading map…";
    private double heading = Double.NaN;
    private long focusedId = -1;
    private LatLng pendingCenter;
    private int controlTop, controlBottom;
    public void setControlInsets(int top, int bottom) {
        if (top == controlTop && bottom == controlBottom) return;
        controlTop = top; controlBottom = bottom; applyControlInsets();
    }
    private void applyControlInsets() {
        if (map == null) return;
        map.setPadding(0, controlTop, 0, controlBottom);
        map.getUiSettings().setLogoMargins((int)dp(14), 0, 0, controlBottom);
        map.getUiSettings().setAttributionMargins((int)dp(112), 0, 0, controlBottom);
    }

    public MapTilerMapView(Context context) { this(context, context.getSharedPreferences("field_map", 0).getString("style", STYLE_OUTDOOR), null); }
    public MapTilerMapView(Context context, String requestedStyle, StyleListener styleListener) {
        super(context);
        MapLibre.getInstance(context);
        baseStyle = normalizeStyleId(requestedStyle);
        mapView = new MapView(context, org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(context).textureMode(true));
        mapView.onCreate(null);
        mapView.addOnDidFailLoadingMapListener(error -> { mapStatus = "Map unavailable · check internet or downloaded area"; if (styleListener != null) styleListener.onStyleError(baseStyle); });
        addView(mapView, new LayoutParams(-1, -1));
        overlay = new MarkerOverlay(context);
        addView(overlay, new LayoutParams(-1, -1));
        mapView.getMapAsync(value -> {
            if (destroyed) return;
            map = value;
            map.getUiSettings().setCompassEnabled(false);
            map.getUiSettings().setCompassFadeFacingNorth(false);
            map.getUiSettings().setTiltGesturesEnabled(false);
            applyControlInsets();
            if (styleListener != null) styleListener.onStyleLoading(baseStyle);
            map.setStyle(styleUrl(), style -> { mapStatus = ""; if (styleListener != null) styleListener.onStyleLoaded(baseStyle); });
            map.addOnCameraMoveListener(overlay::invalidate);
            map.addOnCameraIdleListener(overlay::invalidate);
            map.addOnCameraMoveStartedListener(reason -> {
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) { follow = false; positioned = true; }
            });
            map.addOnMapLongClickListener(coordinate -> {
                if (listener != null) listener.onPlace(coordinate.getLatitude(), coordinate.getLongitude());
                return true;
            });
            map.addOnMapClickListener(coordinate -> {
                if (listener == null) return false;
                Hit hit = overlay.hit(map.getProjection().toScreenLocation(coordinate));
                if (hit != null) { if (hit.members.size() > 1) listener.onCluster(hit.members); else listener.onPoint(hit.point); return true; }
                if (tapToPlace) { listener.onPlace(coordinate.getLatitude(), coordinate.getLongitude()); return true; }
                return false;
            });
            updateCamera();
        });
    }
    public void setListener(Listener value) { listener = value; }
    public void setTapToPlace(boolean enabled) { tapToPlace = enabled; }
    public void setHeading(double degrees) { heading = degrees; overlay.invalidate(); }
    public void setDestination(SavedPoint point) { destination = point; overlay.invalidate(); }
    public void setDirectGuidance(boolean direct) { directGuidance=direct; overlay.invalidate(); }
    public void setRoadRoute(RoadRoute route) { roadRoute=route; overlay.invalidate(); }
    public void showRoute() {
        if(map==null||roadRoute==null) return;
        LatLngBounds.Builder bounds=new LatLngBounds.Builder();
        for(RoadRoute.Coordinate p:roadRoute.shape)bounds.include(new LatLng(p.lat,p.lon));
        follow=false; positioned=true;
        map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(bounds.build(),(int)dp(24),controlTop+(int)dp(24),(int)dp(24),controlBottom+(int)dp(24)));
    }
    public void setLayers(boolean points, boolean track, boolean labels) {
        showPoints = points; showTrack = track; showLabels = labels; overlay.invalidate();
    }
    public String getBaseStyle() { return baseStyle; }
    public String getMapStatus() { return mapStatus; }
    public String styleUrl() { return "https://api.maptiler.com/maps/" + baseStyle + "/style.json?key=" + BuildConfig.MAPTILER_API_KEY; }
    public static String styleUrl(String id) { return "https://api.maptiler.com/maps/" + normalizeStyleId(id) + "/style.json?key=" + BuildConfig.MAPTILER_API_KEY; }
    public void setBaseStyle(String style) {
        baseStyle = normalizeStyleId(style); mapStatus = "Loading map…";
        getContext().getSharedPreferences("field_map", 0).edit().putString("style", style).apply();
        if (map != null) map.setStyle(styleUrl(), loaded -> mapStatus = "");
    }
    public LatLng center() { return map == null ? null : map.getCameraPosition().target; }
    public LatLngBounds visibleBounds() { return map == null ? null : map.getProjection().getVisibleRegion().latLngBounds; }
    public void lookAt(double lat, double lon) {
        if (map == null) { pendingCenter = new LatLng(lat, lon); return; }
        follow = false; positioned = true;
        map.setCameraPosition(new CameraPosition.Builder(map.getCameraPosition()).target(new LatLng(lat, lon)).zoom(15).build());
    }
    public void locate() {
        if (location == null || map == null) return;
        follow = true;
        map.setCameraPosition(new CameraPosition.Builder(map.getCameraPosition()).target(new LatLng(location.getLatitude(), location.getLongitude())).zoom(16).build());
    }
    public void north() { if (map != null) map.setCameraPosition(new CameraPosition.Builder(map.getCameraPosition()).bearing(0).build()); }
    public void setData(Location location, List<SavedPoint> points, List<double[]> track, SavedPoint selectedPoint) {
        this.location = location;
        this.points = points == null ? Collections.emptyList() : points;
        this.track = track == null ? Collections.emptyList() : track;
        selected = selectedPoint;
        updateCamera(); overlay.invalidate();
    }
    private void updateCamera() {
        if (map == null) return;
        if (pendingCenter != null) {
            LatLng center = pendingCenter; pendingCenter = null; positioned = true;
            lookAt(center.getLatitude(), center.getLongitude()); return;
        }
        if (selected != null && selected.id != focusedId) {
            focusedId = selected.id; positioned = true; lookAt(selected.latitude, selected.longitude);
        } else if (follow && location != null) {
            map.setCameraPosition(new CameraPosition.Builder(map.getCameraPosition()).target(new LatLng(location.getLatitude(), location.getLongitude())).build());
        } else if (!positioned) {
            double lat = location != null ? location.getLatitude() : !points.isEmpty() ? points.get(0).latitude : 56.9496;
            double lon = location != null ? location.getLongitude() : !points.isEmpty() ? points.get(0).longitude : 24.1052;
            map.setCameraPosition(new CameraPosition.Builder().target(new LatLng(lat, lon)).zoom(13).build());
            positioned = location != null || !points.isEmpty();
        }
        if (selected == null) focusedId = -1;
    }
    public void start() { if (!destroyed && !started) { mapView.onStart(); started = true; } }
    public void resume() { if (!destroyed && !resumed) { mapView.onResume(); resumed = true; } }
    public void pause() { if (!destroyed && resumed) { mapView.onPause(); resumed = false; } }
    public void stop() { if (!destroyed && started) { mapView.onStop(); started = false; } }
    public void destroy() { if (!destroyed) { pause(); stop(); mapView.onDestroy(); destroyed = true; } }
    public void onLowMemory() { if (!destroyed) mapView.onLowMemory(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); start(); resume(); }
    @Override protected void onDetachedFromWindow() { destroy(); super.onDetachedFromWindow(); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private final class MarkerOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Hit> hits = new ArrayList<>();
        MarkerOverlay(Context context) { super(context); setWillNotDraw(false); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        private PointF project(double lat, double lon) { return map.getProjection().toScreenLocation(new LatLng(lat, lon)); }
        Hit hit(PointF position) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (Math.hypot(hit.screen.x - position.x, hit.screen.y - position.y) <= dp(Math.max(22, hit.point.size))) return hit;
            }
            return null;
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (map == null || destroyed) return;
            paint.setStyle(Paint.Style.FILL); paint.setPathEffect(null);
            if (showTrack && track.size() > 1) {
                paint.setColor(0xff35dfd1); paint.setStrokeWidth(dp(3)); paint.setStrokeCap(Paint.Cap.ROUND);
                PointF previous = null; double[] previousCoordinate = null;
                for (double[] point : track) {
                    if (!Double.isFinite(point[0]) || !Double.isFinite(point[1])) { previous = null; continue; }
                    PointF next = project(point[0], point[1]);
                    if (previous != null && !com.fiskentra.app.model.FieldNavigation.segmentBreak(previousCoordinate, point)) canvas.drawLine(previous.x, previous.y, next.x, next.y, paint);
                    previous = next; previousCoordinate = point;
                }
            }
            if(roadRoute!=null) {
                Path path=new Path(); boolean first=true;
                for(RoadRoute.Coordinate coordinate:roadRoute.shape) { PointF p=project(coordinate.lat,coordinate.lon); if(first){path.moveTo(p.x,p.y);first=false;}else path.lineTo(p.x,p.y); }
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(Color.WHITE); paint.setStrokeWidth(dp(7)); canvas.drawPath(path,paint);
                paint.setColor(0xff005eff); paint.setStrokeWidth(dp(4)); canvas.drawPath(path,paint); paint.setStyle(Paint.Style.FILL);
                if(destination!=null && roadRoute.destinationGap()>20) {
                    RoadRoute.Coordinate end=roadRoute.shape.get(roadRoute.shape.size()-1); PointF a=project(end.lat,end.lon),b=project(destination.latitude,destination.longitude);
                    paint.setColor(0xff35dfd1);paint.setStrokeWidth(dp(2));paint.setPathEffect(new DashPathEffect(new float[]{dp(3),dp(6)},0));canvas.drawLine(a.x,a.y,b.x,b.y,paint);paint.setPathEffect(null);
                }
            }
            if (destination != null) {
                PointF target = project(destination.latitude, destination.longitude);
                paint.setColor(0xff00a7ff); paint.setStrokeWidth(dp(3));
                if (location != null && directGuidance && roadRoute==null) {
                    PointF start = project(location.getLatitude(), location.getLongitude());
                    paint.setPathEffect(new DashPathEffect(new float[]{dp(7), dp(6)}, 0));
                    canvas.drawLine(start.x, start.y, target.x, target.y, paint); paint.setPathEffect(null);
                }
                paint.setStyle(Paint.Style.STROKE); canvas.drawCircle(target.x, target.y, dp(28), paint); paint.setStyle(Paint.Style.FILL);
            }
            hits.clear();
            if (showPoints) {
                List<SavedPoint> ordered = new ArrayList<>(points);
                ordered.sort((a, b) -> Long.compare(a.id, b.id));
                List<Hit> groups = new ArrayList<>();
                for (SavedPoint point : ordered) {
                    PointF origin = project(point.latitude, point.longitude);
                    if (origin.x < -dp(60) || origin.y < -dp(60) || origin.x > getWidth() + dp(60) || origin.y > getHeight() + dp(60)) continue;
                    Hit group = null;
                    for (Hit candidate : groups) if (Math.hypot(candidate.screen.x-origin.x,candidate.screen.y-origin.y) < dp(40)) { group=candidate; break; }
                    if (group == null) groups.add(new Hit(point,origin)); else group.members.add(point);
                }
                for (Hit group : groups) {
                    SavedPoint point = group.point; PointF origin = group.screen;
                    boolean clustered = group.members.size() > 1;
                    float radius = dp(clustered ? 16 : point.size*.72f);
                    PointF drawn = new PointF(origin.x, origin.y - radius - dp(9));
                    Hit target = new Hit(point,drawn); target.members.clear(); target.members.addAll(group.members); hits.add(target);
                    paint.setColor(Color.WHITE); paint.setStrokeWidth(dp(2));
                    canvas.drawLine(origin.x, origin.y, drawn.x, drawn.y, paint); canvas.drawCircle(origin.x, origin.y, dp(4), paint);
                    paint.setColor(point.color == 0 ? colorFor(point.type) : point.color); canvas.drawCircle(origin.x, origin.y, dp(2.5f), paint);
                    paint.setColor(selected != null && selected.id == point.id ? Color.rgb(0, 174, 213) : Color.WHITE);
                    canvas.drawCircle(drawn.x, drawn.y, radius + dp(1.5f), paint);
                    paint.setColor(clustered ? 0xff12354b : point.color == 0 ? colorFor(point.type) : point.color); canvas.drawCircle(drawn.x, drawn.y, radius, paint);
                    paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextSize(dp(point.size));
                    if (clustered) { paint.setTextSize(dp(15)); canvas.drawText(Integer.toString(group.members.size()),drawn.x,drawn.y-(paint.ascent()+paint.descent())/2,paint); }
                    else if (point.symbol.isEmpty()) {
                        int res = "Catch".equals(point.type) ? com.fiskentra.app.R.drawable.ic_fish : "Tackle change".equals(point.type) ? com.fiskentra.app.R.drawable.ic_fish_hook : "Camp".equals(point.type) ? com.fiskentra.app.R.drawable.ic_tent : "Hazard".equals(point.type) ? com.fiskentra.app.R.drawable.ic_alert_triangle : com.fiskentra.app.R.drawable.ic_map_pin;
                        android.graphics.drawable.Drawable icon = getContext().getDrawable(res).mutate(); icon.setTint("Waypoint".equals(point.type) ? 0xff001e2e : Color.WHITE);
                        int half = (int)(radius*.7f); icon.setBounds((int)drawn.x-half,(int)drawn.y-half,(int)drawn.x+half,(int)drawn.y+half); icon.draw(canvas);
                    } else {
                        paint.setColor(point.color == 0xffffffff || point.color == 0xffffc455 ? 0xff001e2e : Color.WHITE);
                        canvas.drawText(point.symbol, drawn.x, drawn.y - (paint.ascent() + paint.descent()) / 2, paint);
                    }
                    if (!clustered && showLabels && (!point.title.isEmpty() || selected != null && selected.id == point.id)) {
                        String label = point.title.isEmpty() ? point.type + " · " + new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(point.timestamp)) : point.title;
                        paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(dp(12)); float width = paint.measureText(label) + dp(12); float x = drawn.x + radius + dp(6), y = drawn.y;
                        if (x + width > getWidth() - dp(4)) x = drawn.x - radius - dp(6) - width;
                        paint.setColor(0xee001e2e); canvas.drawRoundRect(x, y - dp(12), x + width, y + dp(12), dp(4), dp(4), paint);
                        paint.setColor(0xffb7c9e6); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); canvas.drawRoundRect(x, y - dp(12), x + width, y + dp(12), dp(4), dp(4), paint); paint.setStyle(Paint.Style.FILL);
                        paint.setColor(0xffeff4ff); paint.setTextAlign(Paint.Align.LEFT); canvas.drawText(label, x + dp(6), y - (paint.ascent()+paint.descent())/2, paint);
                    }
                }
            }
            if (location != null) {
                PointF current = project(location.getLatitude(), location.getLongitude());
                paint.setColor(0x4400a7ff); canvas.drawCircle(current.x, current.y, dp(25), paint);
                if (Double.isFinite(heading)) {
                    canvas.save(); canvas.rotate((float)(heading - map.getCameraPosition().bearing), current.x, current.y);
                    paint.setColor(Color.rgb(0, 130, 175));
                    Path arrow = new Path(); arrow.moveTo(current.x, current.y - dp(28));
                    arrow.lineTo(current.x - dp(8), current.y - dp(14)); arrow.lineTo(current.x + dp(8), current.y - dp(14)); arrow.close();
                    canvas.drawPath(arrow, paint); canvas.restore();
                }
                paint.setColor(Color.WHITE); canvas.drawCircle(current.x, current.y, dp(12), paint);
                paint.setColor(0xff009fff); canvas.drawCircle(current.x, current.y, dp(8), paint);
            }
        }
    }
    public static int colorFor(String type) {
        if ("Catch".equals(type)) return 0xff219975;
        if ("Tackle change".equals(type)) return 0xffa56aff;
        if ("Camp".equals(type)) return Color.rgb(104, 196, 255);
        if ("Hazard".equals(type)) return Color.rgb(246, 114, 103);
        return Color.rgb(244, 190, 85);
    }
    private static final class Hit {
        final SavedPoint point; final PointF screen;
        final List<SavedPoint> members = new ArrayList<>();
        Hit(SavedPoint point, PointF screen) { this.point = point; this.screen = screen; members.add(point); }
    }
}
