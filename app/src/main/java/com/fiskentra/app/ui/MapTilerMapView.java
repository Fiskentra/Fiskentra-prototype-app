package com.fiskentra.app.ui;

import android.content.Context;
import android.graphics.*;
import android.location.Location;
import android.view.View;
import android.widget.FrameLayout;
import com.fiskentra.app.BuildConfig;
import com.fiskentra.app.model.SavedPoint;
import com.fiskentra.app.model.RoadRoute;
import com.fiskentra.app.model.BacktrackRoute;
import com.fiskentra.app.model.FieldNavigation;
import com.fiskentra.app.model.MapFilterPolicy;
import org.maplibre.android.maps.Style;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.fiskentra.app.model.MapCameraPolicy;
import com.fiskentra.app.model.LocationQualityPolicy;
import com.fiskentra.app.location.FiskentraLocationManager;
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
    public void zoomBy(double amount) { overviewBounds=null;insetGeneration++;if (map != null) map.setCameraPosition(new CameraPosition.Builder(map.getCameraPosition()).zoom(Math.max(1, Math.min(20, map.getCameraPosition().zoom + amount))).build()); }
    public interface Listener {
        void onPlace(double latitude, double longitude);
        void onPoint(SavedPoint point);
        default void onCluster(List<SavedPoint> points) { onPoint(points.get(0)); }
        default void onCameraMode(MapCameraPolicy.Mode mode, boolean orientationAvailable) {}
        default void onCameraIdle() {}
    }
    private final MapView mapView;
    private final MarkerOverlay overlay;
    private final ExecutorService geometryWorker = Executors.newSingleThreadExecutor();
    private long geometryGeneration, overviewGeneration;
    private int styleGeneration;
    private boolean loadingStyle, neutralStyle;
    private final StyleListener styleListener;
    private MapLibreMap map;
    private Listener listener;
    private Location location;
    private List<SavedPoint> points = Collections.emptyList();
    private List<double[]> track = Collections.emptyList();
    private SavedPoint selected, destination;
    private RoadRoute roadRoute;
    private boolean directGuidance=true;
    private final MapCameraPolicy camera = new MapCameraPolicy();
    private MapFeatureRenderer renderer;
    private List<double[]> backtrack=Collections.emptyList();
    private SavedPoint preview;
    private CameraPosition selectionCamera;
    private MapCameraPolicy.Mode selectionMode;
    private CameraPosition selectionRestore;
    private MapCameraPolicy.Mode selectionRestoreMode;
    private long selectionGeneration;
    private long pendingSelectedId=Long.MIN_VALUE;
    private double selectionZoom;
    private double orientation=Double.NaN, animatedBearing=Double.NaN;
    private long headingSampleMillis;
    private long animatedFix;
    private boolean navigating;
    private boolean positionLive=true;
    public void setPositionLive(boolean value){positionLive=value;}
    private double[] coverage;
    private boolean coverageReady;
    private boolean started, resumed, destroyed, positioned, follow, tapToPlace;
    private boolean showPoints = true, showTrack = true, showLabels = true;
    private String baseStyle;
    private String mapStatus = "Loading map…";
    private double heading = Double.NaN;
    private long focusedId = -1;
    private LatLng pendingCenter;
    private LatLngBounds overviewBounds;
    private boolean overviewPending;
    private long insetGeneration;
    private boolean debugCamera,debugSavedPositioned;
    private CameraPosition debugSavedCamera;
    private MapCameraPolicy.Mode debugSavedMode;
    private LatLng debugSavedPendingCenter;
    private int controlTop, controlBottom;
    public void setControlInsets(int top, int bottom) {
        if (top == controlTop && bottom == controlBottom) return;
        CameraPosition before=map==null||destroyed?null:map.getCameraPosition();
        controlTop=top;controlBottom=bottom;animatedFix=0;applyControlInsets();queueSelectionCamera();
        long request=++insetGeneration;
        postOnAnimation(()->{
            if(destroyed||map==null||request!=insetGeneration||selectionCamera!=null||selectionRestore!=null)return;
            if(camera.mode()!=MapCameraPolicy.Mode.FREE){updateCamera();return;}
            if(overviewBounds!=null){fitOverview();return;}
            // setPadding caches insets until a camera transform. Commit them while keeping
            // a manually chosen geographic center and zoom, even when FREE has no GPS updates.
            if(before!=null)map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder(before).padding(cameraPadding()).build()));
        });
    }
    private double[] cameraPadding(){
        int height=getHeight();int top=Math.max(0,controlTop),bottom=Math.max(0,controlBottom);
        if(height>0){top=Math.min(top,Math.max(0,height-(int)dp(80)));bottom=Math.min(bottom,Math.max(0,height-top-(int)dp(80)));}
        int ahead=navigating&&selected==null&&camera.mode()!=MapCameraPolicy.Mode.FREE?Math.max(0,(height-top-bottom)/3):0;
        return new double[]{0,top+ahead,0,bottom};
    }
    private void applyControlInsets() {
        if (map == null||destroyed) return;
        double[] padding=cameraPadding();map.setPadding(0,(int)padding[1],0,(int)padding[3]);
        map.getUiSettings().setLogoMargins((int)dp(14), 0, 0, controlBottom);
        map.getUiSettings().setAttributionMargins((int)dp(112), 0, 0, controlBottom);
    }
    private void queueSelectionCamera(){
        if(selectionCamera==null&&selectionRestore==null)return;
        final long request=++selectionGeneration;
        postOnAnimation(()->{
            if(destroyed||map==null||request!=selectionGeneration)return;
            if(selectionCamera!=null&&selected!=null&&selected.hasLocation()){
                map.cancelTransitions();map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder(map.getCameraPosition()).target(new LatLng(selected.latitude,selected.longitude)).zoom(selectionZoom).padding(cameraPadding()).build()));
            }else if(selectionRestore!=null){
                CameraPosition restore=selectionRestore;MapCameraPolicy.Mode mode=selectionRestoreMode;selectionRestore=null;
                map.cancelTransitions();map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder(restore).padding(cameraPadding()).build()));
                camera.restore(mode.name());animatedFix=0;animatedBearing=Double.NaN;notifyMode();
            }
            overlay.invalidate();
        });
    }

    public MapTilerMapView(Context context) { this(context, context.getSharedPreferences("field_map", 0).getString("style", STYLE_OUTDOOR), null); }
    public MapTilerMapView(Context context, String requestedStyle, StyleListener styleListener) {
        super(context);
        this.styleListener = styleListener;
        MapLibre.getInstance(context);
        baseStyle = normalizeStyleId(requestedStyle);
        mapView = new MapView(context, org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(context).textureMode(true));
        mapView.onCreate(null);
        mapView.addOnDidFailLoadingMapListener(error -> { if (loadingStyle && !neutralStyle && !destroyed) useNeutralStyle(); });
        addView(mapView, new LayoutParams(-1, -1));
        overlay = new MarkerOverlay(context);
        addView(overlay, new LayoutParams(-1, -1));
        mapView.getMapAsync(value -> {
            if (destroyed) return;
            map = value; renderer=new MapFeatureRenderer(context,map);
            android.content.SharedPreferences saved=context.getSharedPreferences("field_map",0);
            camera.restore(saved.getString("camera_mode","FREE"));
            if(saved.contains("camera_lat"))try {
                double lat=Double.parseDouble(saved.getString("camera_lat","")),lon=Double.parseDouble(saved.getString("camera_lon",""));
                if(Double.isFinite(lat)&&Double.isFinite(lon)&&Math.abs(lat)<=85&&Math.abs(lon)<=180){map.setCameraPosition(new CameraPosition.Builder().target(new LatLng(lat,lon)).zoom(saved.getFloat("camera_zoom",13)).bearing(saved.getFloat("camera_bearing",0)).build());positioned=true;}
            }catch(NumberFormatException ignored){}
            if(debugCamera&&debugSavedCamera==null){debugSavedCamera=map.getCameraPosition();debugSavedMode=camera.mode();debugSavedPositioned=positioned;}
            map.getUiSettings().setCompassEnabled(false);
            map.getUiSettings().setCompassFadeFacingNorth(false);
            map.getUiSettings().setTiltGesturesEnabled(false);
            applyControlInsets();
            loadBaseStyle();
            map.addOnCameraMoveListener(overlay::invalidate);
            map.addOnCameraIdleListener(() -> { overlay.invalidate(); persistCamera(); if(listener!=null)listener.onCameraIdle(); });
            map.addOnCameraMoveStartedListener(reason -> {
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) { follow = false; camera.gesture(); notifyMode(); selectionCamera=null;selectionRestore=null;pendingSelectedId=Long.MIN_VALUE;selectionGeneration++;overviewBounds=null;insetGeneration++;overviewGeneration++; positioned = true; }
            });
            map.addOnMapLongClickListener(coordinate -> {
                if (listener != null) listener.onPlace(coordinate.getLatitude(), coordinate.getLongitude());
                return true;
            });
            map.addOnMapClickListener(coordinate -> {
                if (listener == null) return false;
                if (tapToPlace) { listener.onPlace(coordinate.getLatitude(),coordinate.getLongitude()); return true; }
                Hit hit = overlay.hit(map.getProjection().toScreenLocation(coordinate));
                if (hit != null) { if (hit.members.size() > 1) listener.onCluster(hit.members); else listener.onPoint(hit.point); return true; }
                PointF tapped = map.getProjection().toScreenLocation(coordinate);
                if (preview != null) { PointF shown=map.getProjection().toScreenLocation(new LatLng(preview.latitude,preview.longitude));if(Math.hypot(tapped.x-shown.x,tapped.y-shown.y)<=dp(24))return true; }
                if(renderer.tap(tapped, listener)){camera.gesture();notifyMode();return true;}
                if (tapToPlace) { listener.onPlace(coordinate.getLatitude(), coordinate.getLongitude()); return true; }
                return false;
            });
            updateCamera();
        });
    }
    public void setListener(Listener value) { listener = value; notifyMode(); }
    public void setTapToPlace(boolean enabled) { tapToPlace = enabled; }
    public void setHeading(double degrees) { if(destroyed)return;heading=degrees; headingSampleMillis=Double.isFinite(degrees)?android.os.SystemClock.elapsedRealtime():0; updateOrientation(); updateCamera(); overlay.invalidate(); }
    private void updateOrientation() {
        MapCameraPolicy.Mode before=camera.mode();boolean available=camera.orientationAvailable();
        boolean fresh=positionLive&&FiskentraLocationManager.isFresh(location);
        orientation=camera.orientation(fresh&&location.hasSpeed()?location.getSpeed():0,fresh&&location.hasBearing()?location.getBearing():Double.NaN,heading,Double.isFinite(heading),headingSampleMillis,android.os.SystemClock.elapsedRealtime());
        if(before!=camera.mode()||available!=camera.orientationAvailable())notifyMode();
    }
    public MapCameraPolicy.Mode cameraMode(){return camera.mode();}
    public boolean orientationAvailable(){return camera.orientationAvailable();}
    private void notifyMode(){if(listener!=null)listener.onCameraMode(camera.mode(),camera.orientationAvailable());}
    public void setPreview(SavedPoint value){preview=value;overlay.prepareMarkers();overlay.invalidate();}
    public void setNavigating(boolean value){if(navigating==value)return;navigating=value;applyControlInsets();}
    public void setCoverage(double n,double e,double s,double w,boolean ready){coverage=new double[]{n,e,s,w};coverageReady=ready;overlay.invalidate();}
    public void clearCoverage(){coverage=null;overlay.invalidate();}
    public double zoom(){return map==null?0:map.getCameraPosition().zoom;}
    public void setBacktrack(List<double[]> line){geometryGeneration++;backtrack=line==null?Collections.emptyList():line;renderData();overlay.invalidate();}
    public void setBacktrackRoute(BacktrackRoute route) {
        if (destroyed) return; long request = ++geometryGeneration;
        if (route == null) { backtrack = Collections.emptyList(); renderData(); overlay.invalidate(); return; }
        geometryWorker.execute(() -> { List<double[]> copy = route.geometry(); post(() -> { if (destroyed || request != geometryGeneration) return; backtrack = copy; renderData(); overlay.invalidate(); }); });
    }
    public void showBacktrackRoute(BacktrackRoute route) {
        if (destroyed || route == null) return; long request = ++overviewGeneration;
        geometryWorker.execute(() -> {
            double north=-90, east=-180, south=90, west=180;
            for (BacktrackRoute.Segment segment : route.segments) for (BacktrackRoute.Point point : segment.points) { north=Math.max(north,point.latitude);south=Math.min(south,point.latitude);east=Math.max(east,point.longitude);west=Math.min(west,point.longitude); }
            double n=north,e=east,s=south,w=west;
            post(() -> { if (!destroyed && request == overviewGeneration) showBounds(n,e,s,w); });
        });
    }
    public void showTrack(List<double[]> line){if(destroyed||line==null||line.size()<2)return;LatLngBounds.Builder b=new LatLngBounds.Builder();int count=0;for(double[] p:line)if(p!=null&&p.length>=2&&FieldNavigation.validCoordinate(p[0],p[1])){b.include(new LatLng(p[0],p[1]));count++;}if(count>=2)showOverview(b.build());}
    public void showBounds(double n,double e,double s,double w){if(destroyed||!FieldNavigation.validCoordinate(n,e)||!FieldNavigation.validCoordinate(s,w)||n<s||e<w)return;showOverview(LatLngBounds.from(n,e,s,w));}
    private void showOverview(LatLngBounds bounds){
        overviewBounds=bounds;overviewPending=true;pendingCenter=null;pendingSelectedId=Long.MIN_VALUE;selectionCamera=null;selectionRestore=null;selectionGeneration++;insetGeneration++;
        follow=false;camera.gesture();notifyMode();positioned=true;fitOverview();
    }
    private void fitOverview(){
        if(destroyed||map==null||overviewBounds==null||getWidth()<=0||getHeight()<=0)return;
        overviewPending=false;
        CameraPosition before=map.getCameraPosition();double[] padding=cameraPadding();
        int visibleHeight=Math.max(0,getHeight()-(int)padding[1]-(int)padding[3]);
        int margin=Math.min((int)dp(24),visibleHeight/6);int[] fit={margin,(int)padding[1]+margin,margin,(int)padding[3]+margin};
        map.cancelTransitions();
        // Native fit queries see the current persistent camera padding. Remove inherited
        // padding for this calculation, supply panel insets exactly once, then persist only
        // the panel insets (the symmetric 24dp bounds margin is temporary).
        map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder(before).padding(0,0,0,0).build()));
        CameraPosition fitted=map.getCameraForLatLngBounds(overviewBounds,fit,before.bearing,0);
        if(fitted==null||!Double.isFinite(fitted.zoom)){map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder(before).padding(padding).build()));return;}
        double north=Math.toRadians(Math.min(85.05112878,overviewBounds.getLatNorth())),south=Math.toRadians(Math.max(-85.05112878,overviewBounds.getLatSouth()));
        double centerY=(Math.log(Math.tan(Math.PI/4+north/2))+Math.log(Math.tan(Math.PI/4+south/2)))/2;
        LatLng center=new LatLng(Math.toDegrees(2*Math.atan(Math.exp(centerY))-Math.PI/2),overviewBounds.getCenter().getLongitude());
        map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder(fitted).target(center).padding(padding).build()));
        overlay.invalidate();
        if(BuildConfig.DEBUG){final LatLngBounds checked=overviewBounds;final long token=insetGeneration;postOnAnimation(()->{
            if(destroyed||map==null||overviewBounds!=checked||token!=insetGeneration)return;
            float left=Float.POSITIVE_INFINITY,top=Float.POSITIVE_INFINITY,right=Float.NEGATIVE_INFINITY,bottom=Float.NEGATIVE_INFINITY;
            for(LatLng corner:new LatLng[]{new LatLng(checked.getLatNorth(),checked.getLonWest()),new LatLng(checked.getLatNorth(),checked.getLonEast()),new LatLng(checked.getLatSouth(),checked.getLonWest()),new LatLng(checked.getLatSouth(),checked.getLonEast())}){PointF point=map.getProjection().toScreenLocation(corner);left=Math.min(left,point.x);right=Math.max(right,point.x);top=Math.min(top,point.y);bottom=Math.max(bottom,point.y);}
            double[] actual=cameraPadding();boolean visible=left>=actual[0]-2&&right<=getWidth()-actual[2]+2&&top>=actual[1]-2&&bottom<=getHeight()-actual[3]+2;LatLng target=map.getCameraPosition().target;
            android.util.Log.d("FiskentraBoundsFit",String.format(Locale.US,"zoom=%.3f viewport=%dx%d padding=%.0f,%.0f,%.0f,%.0f boundsPixels=%.1f,%.1f,%.1f,%.1f centerInside=%s cornersVisible=%s",map.getCameraPosition().zoom,getWidth(),getHeight(),actual[0],actual[1],actual[2],actual[3],left,top,right,bottom,target!=null&&checked.contains(target),visible));
        });}
    }
    public void selectPoint(SavedPoint p){
        overviewBounds=null;insetGeneration++;
        selectionGeneration++;
        pendingSelectedId=map==null&&p!=null&&p.hasLocation()?p.id:Long.MIN_VALUE;
        pendingCenter=null;
        if(map!=null&&selectionCamera==null){selectionCamera=selectionRestore!=null?selectionRestore:map.getCameraPosition();selectionMode=selectionRestore!=null?selectionRestoreMode:camera.mode();selectionZoom=Math.max(15,map.getCameraPosition().zoom);}
        selectionRestore=null;selected=p;overlay.prepareMarkers();renderData();
        if(p!=null&&p.hasLocation()){follow=false;camera.gesture();notifyMode();positioned=true;applyControlInsets();queueSelectionCamera();}
    }
    public void closeSelection(){
        selectionGeneration++;pendingSelectedId=Long.MIN_VALUE;selected=null;focusedId=-1;overlay.prepareMarkers();renderData();overlay.invalidate();
        if(map!=null&&selectionCamera!=null){selectionRestore=selectionCamera;selectionRestoreMode=selectionMode;selectionCamera=null;map.cancelTransitions();queueSelectionCamera();}
    }
    private void renderData(){if(!destroyed&&renderer!=null){renderer.visibility(showPoints,showTrack,showLabels);renderer.data(points,track,selected,roadRoute,backtrack);}}
    public void beginDebugCamera(){
        if(!BuildConfig.DEBUG||debugCamera||destroyed)return;
        debugSavedCamera=map==null?null:map.getCameraPosition();debugSavedMode=camera.mode();
        debugSavedPositioned=positioned;debugSavedPendingCenter=pendingCenter;debugCamera=true;
    }
    public void endDebugCamera(){
        if(!BuildConfig.DEBUG||!debugCamera)return;
        if(map!=null){map.cancelTransitions();if(debugSavedCamera!=null)map.setCameraPosition(debugSavedCamera);}
        if(debugSavedMode!=null)camera.restore(debugSavedMode.name());
        positioned=debugSavedPositioned;pendingCenter=debugSavedPendingCenter;animatedFix=0;animatedBearing=Double.NaN;
        debugSavedCamera=null;debugSavedPendingCenter=null;debugCamera=false;notifyMode();
    }
    private void persistCamera(){if(map==null||destroyed||debugCamera)return;CameraPosition p=map.getCameraPosition();if(p.target==null)return;getContext().getSharedPreferences("field_map",0).edit().putString("camera_mode",camera.mode().name()).putString("camera_lat",Double.toString(p.target.getLatitude())).putString("camera_lon",Double.toString(p.target.getLongitude())).putFloat("camera_zoom",(float)p.zoom).putFloat("camera_bearing",(float)p.bearing).apply();}
    public void setDestination(SavedPoint point) { destination = point; overlay.prepareMarkers();overlay.invalidate(); }
    public void setDirectGuidance(boolean direct) { directGuidance=direct; overlay.invalidate(); }
    public void setRoadRoute(RoadRoute route) { roadRoute=route; renderData(); overlay.invalidate(); }
    public void showRoute() {
        if(map==null||roadRoute==null) return;
        LatLngBounds.Builder bounds=new LatLngBounds.Builder();
        for(RoadRoute.Coordinate p:roadRoute.shape)bounds.include(new LatLng(p.lat,p.lon));
        showOverview(bounds.build());
    }
    public void setLayers(boolean points, boolean track, boolean labels) {
        showPoints = points; showTrack = track; showLabels = labels; renderData(); overlay.invalidate();
    }
    public String getBaseStyle() { return baseStyle; }
    public String getMapStatus() { return mapStatus; }
    public String styleUrl() { return "https://api.maptiler.com/maps/" + baseStyle + "/style.json?key=" + BuildConfig.MAPTILER_API_KEY; }
    public static String styleUrl(String id) { return "https://api.maptiler.com/maps/" + normalizeStyleId(id) + "/style.json?key=" + BuildConfig.MAPTILER_API_KEY; }
    public void setBaseStyle(String style) {
        baseStyle = normalizeStyleId(style); mapStatus = "Loading map…";
        getContext().getSharedPreferences("field_map", 0).edit().putString("style", style).apply();
        if (map != null) loadBaseStyle();
    }
    public LatLng center() { return map == null ? null : map.getCameraPosition().target; }
    public boolean usingNeutralStyle() { return neutralStyle; }
    public void retryBaseStyle() { if (!destroyed && map != null) loadBaseStyle(); }
    private void loadBaseStyle() {
        if(destroyed||map==null)return;
        int token = ++styleGeneration; loadingStyle = true; neutralStyle = false;
        mapStatus = getContext().getString(com.fiskentra.app.R.string.map_basemap_loading);
        renderer.loading(); if (styleListener != null) styleListener.onStyleLoading(baseStyle);
        map.setStyle(styleUrl(), loaded -> { if (destroyed || token != styleGeneration) return; loadingStyle = false; mapStatus = ""; renderer.style(loaded); renderData(); if (styleListener != null) styleListener.onStyleLoaded(baseStyle); });
    }
    private void useNeutralStyle() {
        if(destroyed||map==null)return;
        int token = ++styleGeneration; loadingStyle = false; neutralStyle = true;
        renderer.loading();
        mapStatus = getContext().getString(com.fiskentra.app.R.string.map_basemap_missing);
        if (styleListener != null) styleListener.onStyleError(baseStyle);
        String json = "{\"version\":8,\"name\":\"Fiskentra offline\",\"sources\":{},\"layers\":[{\"id\":\"neutral-background\",\"type\":\"background\",\"paint\":{\"background-color\":\"#092f3e\"}}]}";
        map.setStyle(new Style.Builder().fromJson(json), loaded -> { if (destroyed || token != styleGeneration) return; renderer.style(loaded,false); renderData(); overlay.invalidate(); });
    }
    public LatLngBounds visibleBounds() { return map == null ? null : map.getProjection().getVisibleRegion().latLngBounds; }
    public void lookAt(double lat, double lon) {
        if (destroyed || !FieldNavigation.validCoordinate(lat, lon)) return;
        overviewBounds=null;insetGeneration++;
        pendingSelectedId=Long.MIN_VALUE;
        if (map == null) { pendingCenter = new LatLng(lat, lon); return; }
        selectionRestore=null;selectionCamera=null;selectionGeneration++;
        follow = false; camera.gesture(); notifyMode(); positioned = true;
        map.setCameraPosition(new CameraPosition.Builder(map.getCameraPosition()).target(new LatLng(lat, lon)).zoom(15).build());
    }
    public void locate() {
        if (!positionLive || !FiskentraLocationManager.isFresh(location) || map==null)return;
        overviewBounds=null;insetGeneration++;
        pendingSelectedId=Long.MIN_VALUE;
        selectionRestore=null;selectionGeneration++;
        updateOrientation();camera.locate(camera.orientationAvailable());animatedFix=0;animatedBearing=Double.NaN;positioned=true;selectionCamera=null;notifyMode();updateCamera();
    }
    public void north() { overviewBounds=null;insetGeneration++;camera.north();notifyMode();if(map!=null){map.cancelTransitions();map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder(map.getCameraPosition()).bearing(0).build()),350);} }
    public void setData(Location location, List<SavedPoint> points, List<double[]> track, SavedPoint selectedPoint) {
        this.location = location;
        this.points = points == null ? Collections.emptyList() : points;
        this.track = track == null ? Collections.emptyList() : track;
        selected = selectedPoint;
        overlay.prepareMarkers();overlay.prepareAccuracy();
        updateOrientation(); renderData(); updateCamera(); overlay.invalidate();
    }
    private void updateCamera() {
        if (map == null||destroyed) return;
        // Point sheets may open before MapLibre is ready; restore the saved viewport first,
        // then apply the still-current selection with measured sheet padding.
        if(pendingSelectedId!=Long.MIN_VALUE&&selected!=null&&selected.id==pendingSelectedId&&selected.hasLocation()){selectPoint(selected);return;}
        if(selectionRestore!=null)return;
        if(overviewBounds!=null&&camera.mode()==MapCameraPolicy.Mode.FREE){if(overviewPending)fitOverview();return;}
        if (pendingCenter != null) {
            LatLng center = pendingCenter; pendingCenter = null; positioned = true;
            lookAt(center.getLatitude(), center.getLongitude()); return;
        }
        boolean fresh=positionLive&&FiskentraLocationManager.isFresh(location);
        if(camera.mode()!=MapCameraPolicy.Mode.FREE && fresh){
            double bearing=camera.mode()==MapCameraPolicy.Mode.FOLLOW_HEADING&&Double.isFinite(orientation)?orientation:0;
            if(animatedFix==location.getElapsedRealtimeNanos()&&Double.isFinite(animatedBearing)&&Math.abs(MapCameraPolicy.delta(animatedBearing,bearing))<5)return;
            animatedFix=location.getElapsedRealtimeNanos();animatedBearing=bearing;map.cancelTransitions();
            map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder(map.getCameraPosition()).target(new LatLng(location.getLatitude(),location.getLongitude())).bearing(bearing).padding(cameraPadding()).build()),450);
        } else if(!positioned){
            SavedPoint p=null;for(SavedPoint candidate:points)if(Double.isFinite(candidate.latitude)&&Double.isFinite(candidate.longitude)){p=candidate;break;}
            if(fresh||p!=null){map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(new LatLng(fresh?location.getLatitude():p.latitude,fresh?location.getLongitude():p.longitude),13));positioned=true;}
        }
    }
    public void start() { if (!destroyed && !started) { mapView.onStart(); started = true; } }
    public void resume() { if (!destroyed && !resumed) { mapView.onResume(); resumed = true; } }
    public void pause() { if (!destroyed && resumed) { persistCamera(); mapView.onPause(); resumed = false; } }
    public void stop() { if (!destroyed && started) { mapView.onStop(); started = false; } }
    public void destroy() { if (!destroyed) { pause(); stop();destroyed=true;loadingStyle=false;styleGeneration++;selectionGeneration++; if(renderer!=null)renderer.close();listener=null;geometryGeneration++;overviewGeneration++;geometryWorker.shutdownNow();mapView.onDestroy(); } }
    public void onLowMemory() { if (!destroyed) mapView.onLowMemory(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); start(); resume(); }
    @Override protected void onDetachedFromWindow() { destroy(); super.onDetachedFromWindow(); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private final class MarkerOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Hit> hits = new ArrayList<>();
        private final MapMarkerImages markerImages;
        private final Map<Long,Bitmap> preparedMarkers=new HashMap<>();
        private final RectF markerBounds=new RectF();
        private final Path accuracyPath=new Path();
        private LatLng[] accuracyVertices;
        private double accuracyLat=Double.NaN,accuracyLon=Double.NaN,accuracyMeters=Double.NaN;
        MarkerOverlay(Context context) {
            super(context); setWillNotDraw(false); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            markerImages=new MapMarkerImages(context);
        }
        void prepareMarkers(){preparedMarkers.clear();for(SavedPoint point:new SavedPoint[]{selected,preview,destination})if(point!=null)preparedMarkers.put(point.id,markerImages.get(point));}
        void prepareAccuracy(){
            if(location==null||!location.hasAccuracy()||!LocationQualityPolicy.accuracy(location.getAccuracy())){accuracyVertices=null;return;}
            double lat=location.getLatitude(),lon=location.getLongitude(),meters=location.getAccuracy();
            if(accuracyVertices!=null&&lat==accuracyLat&&lon==accuracyLon&&meters==accuracyMeters)return;
            accuracyLat=lat;accuracyLon=lon;accuracyMeters=meters;accuracyVertices=new LatLng[65];
            for(int i=0;i<=64;i++){double[] point=LocationQualityPolicy.offset(lat,lon,meters,i*360d/64);accuracyVertices[i]=new LatLng(point[0],point[1]);}
        }
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
            if(coverage!=null){
                PointF a=project(coverage[0],coverage[3]),b=project(coverage[0],coverage[1]),d=project(coverage[2],coverage[1]),e=project(coverage[2],coverage[3]);
                Path box=new Path();box.moveTo(a.x,a.y);box.lineTo(b.x,b.y);box.lineTo(d.x,d.y);box.lineTo(e.x,e.y);box.close();paint.setColor(0x2235dfd1);canvas.drawPath(box,paint);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(coverageReady?0xff35dfd1:0xffffb53e);canvas.drawPath(box,paint);paint.setStyle(Paint.Style.FILL);
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
            for(SavedPoint p:new SavedPoint[]{selected,preview,destination})if(p!=null&&Double.isFinite(p.latitude)&&Double.isFinite(p.longitude)){
                PointF origin=project(p.latitude,p.longitude);float radius=dp(Math.max(13,p.size*.72f));
                int fill=p.color==0?colorFor(p.type):p.color;int ink=markerInk(fill);
                if(p.id>0)hits.add(new Hit(p,origin));paint.setColor(0xff00cce8);canvas.drawCircle(origin.x,origin.y,radius+dp(3),paint);paint.setColor(fill);canvas.drawCircle(origin.x,origin.y,radius,paint);
                Bitmap glyph=preparedMarkers.get(p.id);if(glyph!=null&&!glyph.isRecycled()){float half=dp(12)*p.size/18f;markerBounds.set(origin.x-half,origin.y-half,origin.x+half,origin.y+half);paint.setFilterBitmap(true);canvas.drawBitmap(glyph,null,markerBounds,paint);}
            }
            if(!backtrack.isEmpty()){double[] start=backtrack.get(backtrack.size()-1);if(start.length>=2&&Double.isFinite(start[0])){PointF p=project(start[0],start[1]);paint.setColor(0xff001e2e);canvas.drawRoundRect(p.x-dp(28),p.y-dp(27),p.x+dp(28),p.y-dp(4),dp(5),dp(5),paint);paint.setColor(0xff35dfd1);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(dp(13));canvas.drawText(getContext().getString(com.fiskentra.app.R.string.map_track_start),p.x,p.y-dp(11),paint);}}
            if (location != null) {
                PointF current = project(location.getLatitude(), location.getLongitude());
                boolean fresh=positionLive&&FiskentraLocationManager.isFresh(location);
                if(accuracyVertices!=null){
                    accuracyPath.reset();for(int i=0;i<accuracyVertices.length;i++){PointF p=map.getProjection().toScreenLocation(accuracyVertices[i]);if(i==0)accuracyPath.moveTo(p.x,p.y);else accuracyPath.lineTo(p.x,p.y);}accuracyPath.close();
                    paint.setColor(fresh?0x4435dfd1:0x33778899);canvas.drawPath(accuracyPath,paint);paint.setStyle(Paint.Style.STROKE);paint.setColor(fresh?0xff35dfd1:0xff8899aa);paint.setStrokeWidth(dp(1.5f));canvas.drawPath(accuracyPath,paint);paint.setStyle(Paint.Style.FILL);
                }
                if (fresh && Double.isFinite(orientation)) {
                    canvas.save(); canvas.rotate((float)(orientation - map.getCameraPosition().bearing), current.x, current.y);
                    paint.setColor(Color.rgb(0, 130, 175));
                    Path arrow = new Path(); arrow.moveTo(current.x, current.y - dp(28));
                    arrow.lineTo(current.x - dp(8), current.y - dp(14)); arrow.lineTo(current.x + dp(8), current.y - dp(14)); arrow.close();
                    canvas.drawPath(arrow, paint); canvas.restore();
                }
                paint.setColor(Color.WHITE); canvas.drawCircle(current.x, current.y, dp(12), paint);
                paint.setColor(fresh?0xff0059e8:0xff778899); canvas.drawCircle(current.x, current.y, dp(8), paint);
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
    static int markerInk(int color) { double luminance=.2126*linear(Color.red(color)/255d)+.7152*linear(Color.green(color)/255d)+.0722*linear(Color.blue(color)/255d);return luminance>.18?Color.BLACK:Color.WHITE; }
    private static double linear(double value) { return value<=.04045?value/12.92:Math.pow((value+.055)/1.055,2.4); }
    private static final class Hit {
        final SavedPoint point; final PointF screen;
        final List<SavedPoint> members = new ArrayList<>();
        Hit(SavedPoint point, PointF screen) { this.point = point; this.screen = screen; members.add(point); }
    }
}
