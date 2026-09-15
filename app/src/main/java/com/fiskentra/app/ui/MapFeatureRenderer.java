package com.fiskentra.app.ui;

import android.content.Context;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import com.fiskentra.app.R;
import com.fiskentra.app.model.*;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.*;
import org.maplibre.android.style.layers.*;
import org.maplibre.android.style.sources.*;
import org.maplibre.geojson.*;
import org.maplibre.geojson.Point;
import java.util.*;
import java.util.concurrent.*;
import static org.maplibre.android.style.layers.PropertyFactory.*;
import static org.maplibre.android.style.expressions.Expression.*;

/** Native clustered points and tiled lines; immutable geometry is prepared off the UI thread. */
final class MapFeatureRenderer implements AutoCloseable {
    private final Context context;
    private final MapLibreMap map;
    private final MapMarkerImages markerImages;
    private Map<String,Bitmap> imageData=Collections.emptyMap();
    private final Set<String> installedImages=new HashSet<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private GeoJsonSource pointSource,trackSource,roadSource,returnSource;
    private Style style;
    private long generation;
    private boolean closed,pointsVisible=true,trackVisible=true,labelsVisible=true,visibilityApplied;
    private List<SavedPoint> points=Collections.emptyList();
    private List<double[]> track=Collections.emptyList(),backtrack=Collections.emptyList();
    private RoadRoute road;
    private SavedPoint selected;
    private final Map<Long,SavedPoint> byId=new HashMap<>();
    private FeatureCollection pointData=FeatureCollection.fromFeatures(new Feature[0]);
    private FeatureCollection trackData=pointData,roadData=pointData,returnData=pointData;
    MapFeatureRenderer(Context context,MapLibreMap map) { this.context=context;this.map=map;markerImages=new MapMarkerImages(context); }
    void loading() { style=null;pointSource=null;trackSource=null;roadSource=null;returnSource=null;visibilityApplied=false;installedImages.clear(); }
    private boolean readyStyle(){
        if(closed||style==null)return false;
        // MapLibre invalidates Style handles on both replacement and MapView destruction.
        // Never touch layers/sources through a handle from the preceding style.
        if(!style.isFullyLoaded()||map.getStyle()!=style){loading();return false;}
        return true;
    }
    void style(Style loaded) { style(loaded,true); }
    void style(Style loaded,boolean textAvailable) {
        if(closed||loaded==null||!loaded.isFullyLoaded()||map.getStyle()!=loaded)return;
        loading();style=loaded;installImages();
        pointSource=new GeoJsonSource("fisk-points",pointData,new GeoJsonOptions().withCluster(true).withClusterRadius(42).withClusterMaxZoom(19));
        trackSource=new GeoJsonSource("fisk-track",trackData,new GeoJsonOptions().withTolerance(.6f));
        roadSource=new GeoJsonSource("fisk-road",roadData,new GeoJsonOptions().withTolerance(.3f));
        returnSource=new GeoJsonSource("fisk-return",returnData,new GeoJsonOptions().withTolerance(.3f));
        loaded.addSource(trackSource);loaded.addSource(roadSource);loaded.addSource(returnSource);loaded.addSource(pointSource);
        line(loaded,"fisk-track-case","fisk-track",0xff001e2e,6);line(loaded,"fisk-track-line","fisk-track",0xff35dfd1,3);
        line(loaded,"fisk-road-case","fisk-road",Color.WHITE,8);line(loaded,"fisk-road-line","fisk-road",0xff0059e8,5);
        line(loaded,"fisk-return-case","fisk-return",0xff001e2e,8);line(loaded,"fisk-return-line","fisk-return",0xff35dfd1,5);
        loaded.addLayer(new CircleLayer("fisk-clusters","fisk-points").withFilter(has("point_count")).withProperties(circleColor(0xff001e2e),circleRadius(19f),circleStrokeWidth(2f),circleStrokeColor(0xff35dfd1)));
        if(textAvailable)loaded.addLayer(new SymbolLayer("fisk-cluster-count","fisk-points").withFilter(has("point_count")).withProperties(textField(org.maplibre.android.style.expressions.Expression.toString(get("point_count"))),textSize(15f),textColor(Color.WHITE),textAllowOverlap(true),textIgnorePlacement(true)));
        loaded.addLayer(new CircleLayer("fisk-markers","fisk-points").withFilter(not(has("point_count"))).withProperties(circleColor(toColor(get("color"))),circleRadius(get("radius")),circleStrokeWidth(2f),circleStrokeColor(Color.WHITE)));
        loaded.addLayer(new SymbolLayer("fisk-icons","fisk-points").withFilter(not(has("point_count"))).withProperties(iconImage(get("icon")),iconSize(get("iconScale")),iconAllowOverlap(true),iconIgnorePlacement(true)));
        if(textAvailable)loaded.addLayer(new SymbolLayer("fisk-labels","fisk-points").withFilter(not(has("point_count"))).withProperties(textField(get("label")),textSize(12f),textColor(Color.WHITE),textHaloColor(0xff001e2e),textHaloWidth(2f),textOffset(new Float[]{1.5f,0f}),textAnchor(Property.TEXT_ANCHOR_LEFT)));
        visibility(pointsVisible,trackVisible,labelsVisible);
    }
    private void line(Style s,String id,String source,int color,float width) { s.addLayer(new LineLayer(id,source).withProperties(lineColor(color),lineWidth(width),lineCap(Property.LINE_CAP_ROUND),lineJoin(Property.LINE_JOIN_ROUND))); }
    private void installImages(){if(!readyStyle())return;for(Map.Entry<String,Bitmap> item:imageData.entrySet())if(installedImages.add(item.getKey()))style.addImage(item.getKey(),item.getValue());}
    void visibility(boolean points,boolean track,boolean labels) {
        if(closed)return;
        boolean changed=pointsVisible!=points||trackVisible!=track||labelsVisible!=labels;
        pointsVisible=points;trackVisible=track;labelsVisible=labels;
        if(!changed&&visibilityApplied)return;
        if(!readyStyle())return;
        for(String id:new String[]{"fisk-clusters","fisk-cluster-count","fisk-markers","fisk-icons","fisk-symbols","fisk-labels","fisk-track-case","fisk-track-line"}) {
            Layer layer=style.getLayer(id);if(layer!=null)layer.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility((id.startsWith("fisk-track")?track:points&&(!id.equals("fisk-labels")||labels))?Property.VISIBLE:Property.NONE));
        }
        visibilityApplied=true;
    }
    void data(List<SavedPoint> pointList,List<double[]> line,SavedPoint chosen,RoadRoute route,List<double[]> reverse) {
        if(closed)return;
        if(points==pointList&&track==line&&selected==chosen&&road==route&&backtrack==reverse)return;
        points=pointList;track=line;selected=chosen;road=route;backtrack=reverse;
        byId.clear();for(SavedPoint p:pointList)byId.put(p.id,p);
        long request=++generation;
        worker.execute(()->{
            ArrayList<Feature> markers=new ArrayList<>();
            Map<String,Bitmap> sprites=new HashMap<>();
            for(SavedPoint p:pointList) {
                if(!p.hasLocation()||chosen!=null&&p.id==chosen.id)continue;
                Feature f=Feature.fromGeometry(Point.fromLngLat(p.longitude,p.latitude),null,Long.toString(p.id));
                f.addNumberProperty("pointId",p.id);f.addStringProperty("color",String.format(Locale.US,"#%06x",(p.color==0?MapTilerMapView.colorFor(p.type):p.color)&0xffffff));
                f.addNumberProperty("radius",Math.max(10,p.size*.72));f.addStringProperty("symbol",p.symbol);f.addStringProperty("label",p.title);
                String image=MapMarkerImages.id(p);if(!sprites.containsKey(image))sprites.put(image,markerImages.get(p));
                f.addStringProperty("icon",image);f.addNumberProperty("iconScale",MapMarkerImages.DEFAULT_ICON_SCALE*p.size/18f);markers.add(f);
            }
            FeatureCollection pf=FeatureCollection.fromFeatures(markers),tf=lines(line),bf=lines(reverse);
            ArrayList<Point> roadPoints=new ArrayList<>();if(route!=null)for(RoadRoute.Coordinate p:route.shape)roadPoints.add(Point.fromLngLat(p.lon,p.lat));
            FeatureCollection rf=roadPoints.size()<2?FeatureCollection.fromFeatures(new Feature[0]):FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(roadPoints)));
            main.post(()->{if(closed||request!=generation)return;pointData=pf;trackData=tf;roadData=rf;returnData=bf;imageData=sprites;
                if(readyStyle()&&pointSource!=null&&trackSource!=null&&roadSource!=null&&returnSource!=null){installImages();pointSource.setGeoJson(pf);trackSource.setGeoJson(tf);roadSource.setGeoJson(rf);returnSource.setGeoJson(bf);}});
        });
    }
    private static FeatureCollection lines(List<double[]> coordinates) {
        ArrayList<Feature> lines=new ArrayList<>();ArrayList<Point> part=new ArrayList<>();double[] prior=null;
        for(double[] p:coordinates) {
            boolean valid=p!=null&&p.length>=2&&FieldNavigation.validCoordinate(p[0],p[1]);
            if(!valid||prior!=null&&FieldNavigation.segmentBreak(prior,p)) { if(part.size()>1)lines.add(Feature.fromGeometry(LineString.fromLngLats(part)));part=new ArrayList<>(); }
            if(valid)part.add(Point.fromLngLat(p[1],p[0]));prior=valid?p:null;
        }
        if(part.size()>1)lines.add(Feature.fromGeometry(LineString.fromLngLats(part)));return FeatureCollection.fromFeatures(lines);
    }
    boolean tap(PointF screen,MapTilerMapView.Listener listener) {
        if(!readyStyle()||pointSource==null||!pointsVisible)return false;
        float touch=24*context.getResources().getDisplayMetrics().density;
        List<Feature> hits=map.queryRenderedFeatures(new RectF(screen.x-touch,screen.y-touch,screen.x+touch,screen.y+touch),"fisk-markers","fisk-clusters");
        if(hits.isEmpty())return false;Feature f=hits.get(0);
        if(f.hasProperty("cluster_id")) {
            List<Feature> leaves=pointSource.getClusterLeaves(f,10001,0).features();ArrayList<SavedPoint> members=new ArrayList<>();
            if(leaves!=null)for(Feature leaf:leaves){SavedPoint p=byId.get(leaf.getNumberProperty("pointId").longValue());if(p!=null)members.add(p);}
            if(members.isEmpty())return false;
            boolean coincident=true;SavedPoint first=members.get(0);for(SavedPoint p:members)if(FieldNavigation.distance(first.latitude,first.longitude,p.latitude,p.longitude)>2){coincident=false;break;}
            if(coincident||map.getCameraPosition().zoom>=19)listener.onCluster(members);
            else { Point p=(Point)f.geometry();int zoom=pointSource.getClusterExpansionZoom(f);map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(new LatLng(p.latitude(),p.longitude()),Math.min(20,zoom+.3)),400); }
        } else { SavedPoint p=byId.get(f.getNumberProperty("pointId").longValue());if(p!=null)listener.onPoint(p); }
        return true;
    }
    @Override public void close() { if(closed)return;closed=true;++generation;loading();worker.shutdownNow();main.removeCallbacksAndMessages(null);markerImages.clear();imageData=Collections.emptyMap();byId.clear(); }
}
