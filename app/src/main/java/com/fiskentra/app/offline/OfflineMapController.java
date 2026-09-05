package com.fiskentra.app.offline;

import android.content.Context;
import android.location.Location;

import com.fiskentra.app.BuildConfig;
import com.fiskentra.app.model.OfflineAreaPolicy;
import com.fiskentra.app.ui.MapTilerMapView;

import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.offline.OfflineRegion;
import org.maplibre.android.offline.OfflineRegionError;
import org.maplibre.android.offline.OfflineRegionStatus;
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns user-requested MapLibre regions without exposing provider credentials to the UI. */
public final class OfflineMapController {
    private static final String OWNER = "fiskentra";

    public interface Listener { void onOfflineMapChanged(); }

    public static final class Snapshot {
        public final int packCount, progress;
        public final long completedBytes;
        public final boolean downloading, complete, available, checked;
        public final String status, styleName;
        public final double centerLatitude, centerLongitude, radiusKm;

        Snapshot(int packCount,int progress,long completedBytes,boolean downloading,
                 boolean complete,boolean available,boolean checked,String status,String styleName,
                 double centerLatitude,double centerLongitude,double radiusKm){
            this.packCount=packCount;this.progress=progress;this.completedBytes=completedBytes;
            this.downloading=downloading;this.complete=complete;this.available=available;this.checked=checked;
            this.status=status;this.styleName=styleName;this.centerLatitude=centerLatitude;
            this.centerLongitude=centerLongitude;this.radiusKm=radiusKm;
        }
    }

    private final OfflineManager manager;
    private final float pixelRatio;
    private final CopyOnWriteArraySet<Listener> listeners=new CopyOnWriteArraySet<>();
    private final List<OfflineRegion> ownedRegions=new ArrayList<>();
    private OfflineRegion selected;
    private int progress;
    private long completedBytes;
    private boolean downloading,complete,checked,creating;
    private String status="Checking saved maps…",styleName="";
    private double centerLatitude,centerLongitude,radiusKm;

    public OfflineMapController(Context context){
        Context app=context.getApplicationContext();
        MapLibre.getInstance(app);
        manager=OfflineManager.getInstance(app);
        pixelRatio=app.getResources().getDisplayMetrics().density;
        refresh();
    }

    public void addListener(Listener listener){if(listener!=null)listeners.add(listener);}
    public void removeListener(Listener listener){listeners.remove(listener);}

    public synchronized Snapshot snapshot(){
        return new Snapshot(ownedRegions.size(),progress,completedBytes,downloading,complete,
                selected!=null,checked,status,styleName,centerLatitude,centerLongitude,radiusKm);
    }

    public void refresh(){
        synchronized(this){checked=false;}
        manager.listOfflineRegions(new OfflineManager.ListOfflineRegionsCallback(){
            @Override public void onList(OfflineRegion[] regions){
                List<OfflineRegion> found=new ArrayList<>();OfflineRegion newest=null;long newestAt=Long.MIN_VALUE;
                if(regions!=null)for(OfflineRegion region:regions){JSONObject meta=metadata(region);if(!OWNER.equals(meta.optString("owner")))continue;found.add(region);long created=meta.optLong("created_at",0L);if(newest==null||created>=newestAt){newest=region;newestAt=created;}}
                synchronized(OfflineMapController.this){checked=true;ownedRegions.clear();ownedRegions.addAll(found);selected=newest;if(newest==null){progress=0;completedBytes=0;downloading=false;complete=false;status="No offline area saved";styleName="";centerLatitude=centerLongitude=radiusKm=0;}}
                if(newest!=null){readMetadata(newest);observe(newest);readStatus(newest);}else notifyListeners();
            }
            @Override public void onError(String error){synchronized(OfflineMapController.this){checked=false;status="Saved maps could not be checked · try again";}notifyListeners();}
        });
    }

    public void download(Location location,String styleId,double requestedRadiusKm){
        if(location==null){setStatus("Wait for a GPS position before downloading");return;}
        if(BuildConfig.MAPTILER_API_KEY==null||BuildConfig.MAPTILER_API_KEY.trim().isEmpty()){setStatus("Map provider key is not configured");return;}
        double radius=OfflineAreaPolicy.normalizeRadius(requestedRadiusKm),lat=location.getLatitude(),lon=location.getLongitude();
        double[] area;
        try{area=OfflineAreaPolicy.bounds(lat,lon,radius);}catch(IllegalArgumentException error){setStatus("Wait for a valid GPS position before downloading");return;}
        synchronized(this){if(!checked){status="Wait while saved maps are checked";notifyListeners();return;}if(creating){status="Offline area is already being prepared";notifyListeners();return;}if(!ownedRegions.isEmpty()||selected!=null){status="Delete the saved area before downloading another";notifyListeners();return;}status="Preparing offline area…";progress=0;completedBytes=0;downloading=true;complete=false;creating=true;}
        LatLngBounds bounds=LatLngBounds.from(area[0],area[1],area[2],area[3]);
        String normalized=MapTilerMapView.normalizeStyleId(styleId);
        OfflineTilePyramidRegionDefinition definition=new OfflineTilePyramidRegionDefinition(
                MapTilerMapView.styleUrl(normalized),bounds,OfflineAreaPolicy.MIN_ZOOM,OfflineAreaPolicy.MAX_ZOOM,pixelRatio,false);
        byte[] metadata=metadataBytes(lat,lon,radius,normalized);
        manager.createOfflineRegion(definition,metadata,new OfflineManager.CreateOfflineRegionCallback(){
            @Override public void onCreate(OfflineRegion region){synchronized(OfflineMapController.this){creating=false;ownedRegions.add(region);selected=region;centerLatitude=lat;centerLongitude=lon;radiusKm=radius;styleName=MapTilerMapView.styleName(normalized);status="Downloading offline area…";}observe(region);region.setDownloadState(OfflineRegion.STATE_ACTIVE);notifyListeners();}
            @Override public void onError(String error){synchronized(OfflineMapController.this){creating=false;downloading=false;status="Offline area could not be created";}notifyListeners();}
        });
    }

    public void pause(){OfflineRegion region; synchronized(this){region=selected;if(region==null)return;downloading=false;status="Download paused";}region.setDownloadState(OfflineRegion.STATE_INACTIVE);notifyListeners();}
    public void resume(){OfflineRegion region; synchronized(this){region=selected;if(region==null||complete)return;downloading=true;status="Downloading offline area…";}observe(region);region.setDownloadState(OfflineRegion.STATE_ACTIVE);notifyListeners();}

    public void deleteAll(){
        List<OfflineRegion> regions; synchronized(this){regions=new ArrayList<>(ownedRegions);if(regions.isEmpty()){status="No offline area to delete";notifyListeners();return;}status="Deleting offline area…";downloading=false;for(OfflineRegion region:regions)region.setDownloadState(OfflineRegion.STATE_INACTIVE);}
        AtomicInteger remaining=new AtomicInteger(regions.size());
        for(OfflineRegion region:regions)region.delete(new OfflineRegion.OfflineRegionDeleteCallback(){
            @Override public void onDelete(){if(remaining.decrementAndGet()==0)refresh();}
            @Override public void onError(String error){synchronized(OfflineMapController.this){status="Offline area could not be deleted";}notifyListeners();}
        });
    }

    private void observe(OfflineRegion region){
        region.setDeliverInactiveMessages(true);
        region.setObserver(new OfflineRegion.OfflineRegionObserver(){
            @Override public void onStatusChanged(OfflineRegionStatus value){applyStatus(region,value);}
            @Override public void onError(OfflineRegionError error){synchronized(OfflineMapController.this){boolean connection=OfflineRegionError.REASON_CONNECTION.equals(error.getReason());downloading=connection;status=connection?"Waiting for an internet connection":"Map download stopped · try again";}notifyListeners();}
            @Override public void mapboxTileCountLimitExceeded(long limit){region.setDownloadState(OfflineRegion.STATE_INACTIVE);synchronized(OfflineMapController.this){downloading=false;status="Area is too large for the offline tile limit";}notifyListeners();}
        });
    }

    private void readStatus(OfflineRegion region){region.getStatus(new OfflineRegion.OfflineRegionStatusCallback(){@Override public void onStatus(OfflineRegionStatus value){applyStatus(region,value);}@Override public void onError(String error){setStatus("Offline status is temporarily unavailable");}});}

    private void applyStatus(OfflineRegion region,OfflineRegionStatus value){
        synchronized(this){if(selected==null||selected.getId()!=region.getId())return;long required=value.getRequiredResourceCount(),done=value.getCompletedResourceCount();progress=required>0?(int)Math.min(100,Math.round(done*100d/required)):value.isComplete()?100:0;completedBytes=value.getCompletedResourceSize();complete=value.isComplete();downloading=!complete&&value.getDownloadState()==OfflineRegion.STATE_ACTIVE;status=complete?"Ready offline":downloading?"Downloading · "+progress+"%":"Download paused";}
        notifyListeners();
    }

    private synchronized void readMetadata(OfflineRegion region){JSONObject meta=metadata(region);centerLatitude=meta.optDouble("lat",0);centerLongitude=meta.optDouble("lon",0);radiusKm=meta.optDouble("radius_km",0);styleName=MapTilerMapView.styleName(meta.optString("style"));}
    private JSONObject metadata(OfflineRegion region){try{return new JSONObject(new String(region.getMetadata(),StandardCharsets.UTF_8));}catch(Exception ignored){return new JSONObject();}}
    private byte[] metadataBytes(double lat,double lon,double radius,String style){try{JSONObject value=new JSONObject();value.put("owner",OWNER);value.put("created_at",System.currentTimeMillis());value.put("lat",lat);value.put("lon",lon);value.put("radius_km",radius);value.put("style",style);return value.toString().getBytes(StandardCharsets.UTF_8);}catch(Exception ignored){return new byte[0];}}
    private void setStatus(String value){synchronized(this){status=value;}notifyListeners();}
    private void notifyListeners(){for(Listener listener:listeners)listener.onOfflineMapChanged();}
}
