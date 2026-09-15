package com.fiskentra.app.offline;

import android.content.Context;
import android.location.Location;
import android.os.StatFs;
import com.fiskentra.app.BuildConfig;
import com.fiskentra.app.R;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;

/** Application-scoped SDK owner. Mutating calls and SDK callbacks use the Android main thread. */
public final class OfflineMapController {
    private static final String OWNER = "fiskentra";
    private static final int METADATA_VERSION = 2;
    public enum State { DRAFT, DOWNLOADING, READY, PAUSED, ERROR, DELETING }
    public interface Listener { void onOfflineMapChanged(); }

    public static final class Draft {
        public final double latitude, longitude, radiusKm, north, east, south, west;
        public final String styleId;
        public final long approximateBytes;
        private Draft(double latitude, double longitude, double radiusKm, String styleId) {
            double[] b = OfflineAreaPolicy.bounds(latitude, longitude, radiusKm);
            this.latitude=latitude; this.longitude=longitude; this.radiusKm=OfflineAreaPolicy.normalizeRadius(radiusKm); this.styleId=styleId;
            north=b[0]; east=b[1]; south=b[2]; west=b[3]; approximateBytes=OfflineAreaPolicy.approximateBytes(b);
        }
        public double[] bounds() { return new double[]{north,east,south,west}; }
    }
    /** Actual bounds/zoom are from the SDK definition; no credential-bearing URLs escape this owner. */
    public static final class Area {
        public final long id, completedBytes, createdAt, updatedAt, replacementOf;
        public final String name, styleId, styleName, status;
        public final int metadataVersion, progress;
        public final boolean complete, checked, indeterminate;
        public final State state;
        public final double centerLatitude, centerLongitude, radiusKm, north, east, south, west, minZoom, maxZoom;
        private Area(Entry e,Context context) {
            id=e.region.getId(); completedBytes=e.bytes; state=e.state; name=e.name; styleId=e.style;
            styleName=MapTilerMapView.styleName(styleId); status=context.getString(e.statusResource); progress=e.progress;
            complete=e.sdkComplete&&state!=State.DELETING; checked=e.checked; indeterminate=!e.precise&&!complete;
            metadataVersion=METADATA_VERSION; centerLatitude=e.latitude; centerLongitude=e.longitude; radiusKm=e.radius;
            north=e.bounds[0]; east=e.bounds[1]; south=e.bounds[2]; west=e.bounds[3]; minZoom=e.minZoom; maxZoom=e.maxZoom;
            createdAt=e.createdAt; updatedAt=e.updatedAt; replacementOf=e.replacementOf;
        }
        public double[] bounds() { return new double[]{north,east,south,west}; }
        public OfflineAreaPolicy.Coverage coverage(String style,double latitude,double longitude,double zoom) {
            return OfflineAreaPolicy.coverage(complete,bounds(),styleId,style,minZoom,maxZoom,latitude,longitude,zoom);
        }
    }
    /** Scalar fields retain the contract used by older settings screens. */
    public static final class Snapshot {
        public final List<Area> areas;
        public final Area readyArea,candidateArea;
        public final int packCount,progress;
        public final long completedBytes,freeBytes;
        public final boolean downloading,complete,available,checked,creating,indeterminate;
        public final String status,styleName;
        public final double centerLatitude,centerLongitude,radiusKm;
        private Snapshot(List<Area> areas,boolean checked,boolean creating,String status,long freeBytes) {
            this.areas=Collections.unmodifiableList(areas);this.checked=checked;this.creating=creating;this.freeBytes=freeBytes;packCount=areas.size();
            Area ready=null,candidate=null;
            for(Area area:areas) {
                if(area.complete) { if(ready==null||area.createdAt>=ready.createdAt)ready=area; }
                else if(area.state!=State.DELETING&&(candidate==null||area.createdAt>=candidate.createdAt))candidate=area;
            }
            readyArea=ready;candidateArea=candidate;
            Area selected=candidate!=null?candidate:ready!=null?ready:areas.isEmpty()?null:areas.get(0);
            available=selected!=null;complete=selected!=null&&selected.complete;
            downloading=creating||selected!=null&&selected.state==State.DOWNLOADING;
            progress=selected==null?0:Math.max(0,selected.progress);indeterminate=creating||selected!=null&&selected.indeterminate;
            completedBytes=selected==null?0:selected.completedBytes;this.status=status.isEmpty()&&selected!=null?selected.status:status;
            styleName=selected==null?"":selected.styleName;centerLatitude=selected==null?0:selected.centerLatitude;
            centerLongitude=selected==null?0:selected.centerLongitude;radiusKm=selected==null?0:selected.radiusKm;
        }
    }
    private static final class Write {
        final byte[] metadata;final Runnable success,rollback;
        Write(byte[] metadata,Runnable success,Runnable rollback){this.metadata=metadata;this.success=success;this.rollback=rollback;}
    }
    private static final class Entry {
        final OfflineRegion region;final ArrayDeque<Write>writes=new ArrayDeque<>();
        String name,style;double latitude,longitude,radius,minZoom,maxZoom;double[]bounds;
        long createdAt,updatedAt,bytes,replacementOf;
        int progress=-1,statusResource=R.string.offline_checking;
        boolean checked,sdkComplete,precise,writing,deleting,starting,metadataFailed,deleteIntentCommitted;State state=State.DRAFT;
        Entry(OfflineRegion region){this.region=region;}
    }
    private final Context app;private final OfflineManager manager;private final float pixelRatio;
    private final CopyOnWriteArraySet<Listener>listeners=new CopyOnWriteArraySet<>();
    private final Map<Long,Entry>entries=new LinkedHashMap<>();
    private boolean checked,creating,refreshing;private int generation;private String globalStatus;private long freeBytes;

    public OfflineMapController(Context context) {
        app=context.getApplicationContext();MapLibre.getInstance(app);manager=OfflineManager.getInstance(app);
        pixelRatio=app.getResources().getDisplayMetrics().density;globalStatus=app.getString(R.string.offline_checking);refresh();
    }
    public void addListener(Listener listener){if(listener!=null)listeners.add(listener);}
    public void removeListener(Listener listener){listeners.remove(listener);}
    public synchronized Snapshot snapshot(){List<Area>areas=new ArrayList<>();for(Entry e:entries.values())areas.add(new Area(e,app));return new Snapshot(areas,checked,creating,globalStatus,freeBytes);}
    public static Draft draft(double latitude,double longitude,double radiusKm,String styleId){return new Draft(latitude,longitude,radiusKm,MapTilerMapView.normalizeStyleId(styleId));}

    public void refresh(){
        if(creating||refreshing)return;
        for(Entry e:entries.values())if(e.writing||e.deleting)return;
        refreshing=true;checked=false;int request=++generation;updateFreeSpace();
        manager.listOfflineRegions(new OfflineManager.ListOfflineRegionsCallback(){
            @Override public void onList(OfflineRegion[]regions){
                if(request!=generation)return;
                for(Entry e:entries.values())e.region.setObserver(null);
                entries.clear();
                if(regions!=null)for(OfflineRegion region:regions){JSONObject meta=metadata(region);if(!OWNER.equals(meta.optString("owner"))||!(region.getDefinition() instanceof OfflineTilePyramidRegionDefinition))continue;Entry e=readEntry(region,meta);entries.put(region.getId(),e);observe(e);}
                refreshing=false;checked=entries.isEmpty();globalStatus=entries.isEmpty()?app.getString(R.string.offline_no_area):"";
                for(Entry e:new ArrayList<>(entries.values()))readStatus(e);notifyListeners();
            }
            @Override public void onError(String error){if(request!=generation)return;refreshing=false;checked=false;setGlobal(R.string.offline_check_failed);}
        });
    }
    /** GPS is optional; this wrapper preserves older callers. New map UI supplies a chosen center. */
    public void download(Location location,String styleId,double radiusKm){if(location==null){setGlobal(R.string.offline_choose_center_first);return;}startDownload(app.getString(R.string.offline_default_name),location.getLatitude(),location.getLongitude(),radiusKm,styleId);}
    public void startDownload(String name,double latitude,double longitude,double radiusKm,String styleId){
        if(!checked||refreshing){setGlobal(R.string.offline_checking);return;}
        if(creating){setGlobal(R.string.offline_preparing);return;}
        Snapshot current=snapshot();if(current.candidateArea!=null||entries.size()>1){setGlobal(R.string.offline_finish_candidate);return;}
        if(BuildConfig.MAPTILER_API_KEY==null||BuildConfig.MAPTILER_API_KEY.trim().isEmpty()){setGlobal(R.string.offline_key_missing);return;}
        Draft d;try{d=draft(latitude,longitude,radiusKm,styleId);}catch(IllegalArgumentException invalid){setGlobal(invalidAreaResource(invalid));return;}
        updateFreeSpace();if(!OfflineAreaPolicy.hasSpace(freeBytes,d.approximateBytes)){setGlobal(R.string.offline_space_shortage);return;}
        if(d.approximateBytes>OfflineAreaPolicy.DOWNLOAD_BUDGET_BYTES){setGlobal(R.string.offline_budget_large);return;}
        creating=true;setGlobal(R.string.offline_preparing);long replacement=current.readyArea==null?-1:current.readyArea.id;
        OfflineTilePyramidRegionDefinition definition=new OfflineTilePyramidRegionDefinition(MapTilerMapView.styleUrl(d.styleId),LatLngBounds.from(d.north,d.east,d.south,d.west),OfflineAreaPolicy.MIN_ZOOM,OfflineAreaPolicy.MAX_ZOOM,pixelRatio,false);
        JSONObject meta=initialMetadata(name,d,replacement);
        manager.createOfflineRegion(definition,bytes(meta),new OfflineManager.CreateOfflineRegionCallback(){
            @Override public void onCreate(OfflineRegion region){creating=false;Entry e=readEntry(region,meta);entries.put(region.getId(),e);e.checked=true;e.starting=true;e.state=State.DOWNLOADING;e.statusResource=R.string.offline_downloading;globalStatus="";observe(e);persist(e,()->{if(live(e)&&e.state==State.DOWNLOADING)e.region.setDownloadState(OfflineRegion.STATE_ACTIVE);e.starting=false;});notifyListeners();}
            @Override public void onError(String error){creating=false;setGlobal(classifyError(error,R.string.offline_create_failed));}
        });
    }
    public void pause(){Area area=snapshot().candidateArea;if(area!=null)pause(area.id);}
    public void resume(){Area area=snapshot().candidateArea;if(area!=null)resume(area.id);}
    public void pause(long id){Entry e=entries.get(id);if(e==null||e.sdkComplete||e.deleting||refreshing)return;e.starting=false;e.state=State.PAUSED;e.statusResource=R.string.offline_paused;globalStatus="";e.region.setDownloadState(OfflineRegion.STATE_INACTIVE);persist(e,null);notifyListeners();}
    public void resume(long id){
        Entry e=entries.get(id);if(e==null||e.sdkComplete||e.deleting||!e.checked||e.writing||refreshing)return;
        updateFreeSpace();if(freeBytes<OfflineAreaPolicy.FREE_RESERVE_BYTES){fail(e,R.string.offline_space_shortage);return;}
        if(e.bytes>=OfflineAreaPolicy.DOWNLOAD_BUDGET_BYTES){fail(e,R.string.offline_budget_large);return;}
        e.starting=true;e.state=State.DOWNLOADING;e.statusResource=R.string.offline_downloading;globalStatus="";persist(e,()->{if(live(e)&&e.state==State.DOWNLOADING)e.region.setDownloadState(OfflineRegion.STATE_ACTIVE);e.starting=false;});notifyListeners();
    }
    public void retry(long id){Entry e=entries.get(id);if(e==null)return;e.metadataFailed=false;if(e.state==State.DELETING){if(e.deleteIntentCommitted)deleteSdk(e);else persist(e,()->{e.deleteIntentCommitted=true;deleteSdk(e);});return;}if(!e.checked||e.sdkComplete){readStatus(e);return;}resume(id);}
    public void rename(long id,String name){Entry e=entries.get(id);if(e==null||e.deleting||e.writing||refreshing)return;String before=e.name;e.name=normalizedName(name);persist(e,null,()->e.name=before);notifyListeners();}
    public void remove(long id){Entry e=entries.get(id);if(e==null||e.deleting||e.state==State.DELETING||refreshing)return;e.starting=false;e.state=State.DELETING;e.statusResource=R.string.offline_deleting;globalStatus="";e.region.setDownloadState(OfflineRegion.STATE_INACTIVE);persist(e,()->{e.deleteIntentCommitted=true;deleteSdk(e);});notifyListeners();}
    public void deleteAll(){for(Entry e:new ArrayList<>(entries.values()))remove(e.region.getId());}
    private void deleteSdk(Entry e){
        if(!live(e)||e.deleting||!e.deleteIntentCommitted)return;e.deleting=true;e.state=State.DELETING;e.statusResource=R.string.offline_deleting;e.region.setDownloadState(OfflineRegion.STATE_INACTIVE);
        e.region.delete(new OfflineRegion.OfflineRegionDeleteCallback(){
            @Override public void onDelete(){if(!live(e))return;entries.remove(e.region.getId());e.deleting=false;globalStatus=entries.isEmpty()?app.getString(R.string.offline_no_area):"";updateFreeSpace();notifyListeners();}
            @Override public void onError(String error){if(!live(e))return;e.deleting=false;e.statusResource=R.string.offline_delete_failed;notifyListeners();}
        });
    }
    private void observe(Entry e){
        e.region.setDeliverInactiveMessages(true);e.region.setObserver(new OfflineRegion.OfflineRegionObserver(){
            @Override public void onStatusChanged(OfflineRegionStatus value){applyStatus(e,value);}
            @Override public void onError(OfflineRegionError error){if(!live(e)||e.deleting||e.sdkComplete)return;fail(e,OfflineRegionError.REASON_CONNECTION.equals(error.getReason())?R.string.offline_connection_error:classifyError(error.getMessage(),R.string.offline_provider_error));}
            @Override public void mapboxTileCountLimitExceeded(long limit){if(live(e))fail(e,R.string.offline_tile_limit);}
        });
    }
    private void readStatus(Entry e){e.region.getStatus(new OfflineRegion.OfflineRegionStatusCallback(){
        @Override public void onStatus(OfflineRegionStatus value){applyStatus(e,value);if(live(e)&&e.state==State.DELETING)deleteSdk(e);}
        @Override public void onError(String error){if(!live(e))return;e.statusResource=R.string.offline_status_failed;e.checked=false;checked=false;notifyListeners();}
    });}
    private void applyStatus(Entry e,OfflineRegionStatus value){
        if(!live(e)||e.deleting)return;e.checked=true;e.sdkComplete=value.isComplete();e.precise=value.isRequiredResourceCountPrecise();e.progress=OfflineAreaPolicy.progress(e.sdkComplete,e.precise,value.getCompletedResourceCount(),value.getRequiredResourceCount());e.bytes=value.getCompletedResourceSize();
        checked=true;for(Entry entry:entries.values())checked&=entry.checked;
        if(e.state==State.DELETING||e.metadataFailed){notifyListeners();return;}
        State previous=e.state;
        if(e.sdkComplete){e.state=State.READY;e.statusResource=R.string.offline_ready;if(value.getDownloadState()==OfflineRegion.STATE_ACTIVE)e.region.setDownloadState(OfflineRegion.STATE_INACTIVE);}
        else if(e.state!=State.ERROR){e.state=e.starting||value.getDownloadState()==OfflineRegion.STATE_ACTIVE?State.DOWNLOADING:State.PAUSED;e.statusResource=e.state==State.DOWNLOADING?R.string.offline_downloading:R.string.offline_paused;}
        if(e.state==State.DOWNLOADING){updateFreeSpace();if(freeBytes<OfflineAreaPolicy.FREE_RESERVE_BYTES||e.bytes>OfflineAreaPolicy.DOWNLOAD_BUDGET_BYTES){fail(e,freeBytes<OfflineAreaPolicy.FREE_RESERVE_BYTES?R.string.offline_space_shortage:R.string.offline_budget_large);return;}}
        if(e.state!=previous||e.sdkComplete&&e.replacementOf>=0)persist(e,e.sdkComplete?()->finishReplacement(e):null);
        notifyListeners();
    }
    private void finishReplacement(Entry e){if(!live(e)||!e.sdkComplete||e.state!=State.READY||e.replacementOf<0)return;Entry old=entries.get(e.replacementOf);if(old!=null&&old!=e)remove(old.region.getId());e.replacementOf=-1;persist(e,null);}
    private void fail(Entry e,int message){if(!live(e))return;e.starting=false;e.state=State.ERROR;e.statusResource=message;globalStatus="";e.region.setDownloadState(OfflineRegion.STATE_INACTIVE);persist(e,null);notifyListeners();}
    private Entry readEntry(OfflineRegion region,JSONObject meta){
        Entry e=new Entry(region);OfflineTilePyramidRegionDefinition definition=(OfflineTilePyramidRegionDefinition)region.getDefinition();LatLngBounds b=definition.getBounds();
        e.bounds=new double[]{b.getLatNorth(),b.getLonEast(),b.getLatSouth(),b.getLonWest()};e.minZoom=definition.getMinZoom();e.maxZoom=definition.getMaxZoom();
        e.latitude=meta.optDouble("lat",b.getCenter().getLatitude());e.longitude=meta.optDouble("lon",b.getCenter().getLongitude());e.radius=meta.optDouble("radius_km",0);e.style=MapTilerMapView.normalizeStyleId(meta.optString("style"));e.name=normalizedName(meta.optString("name"));e.createdAt=meta.optLong("created_at",0);e.updatedAt=meta.optLong("updated_at",e.createdAt);e.replacementOf=meta.optLong("replacement_of",-1);
        String state=meta.optString("state");e.state="DELETING".equals(state)?State.DELETING:"ERROR".equals(state)?State.ERROR:State.DRAFT;
        e.deleteIntentCommitted=e.state==State.DELETING;
        if(e.state==State.ERROR)e.statusResource=R.string.offline_provider_error;
        return e;
    }
    private JSONObject initialMetadata(String name,Draft d,long replacement){
        JSONObject meta=new JSONObject();try{meta.put("owner",OWNER).put("version",METADATA_VERSION).put("name",normalizedName(name)).put("created_at",System.currentTimeMillis()).put("updated_at",System.currentTimeMillis()).put("lat",d.latitude).put("lon",d.longitude).put("radius_km",d.radiusKm).put("style",d.styleId).put("state",State.DRAFT.name()).put("replacement_of",replacement);}catch(org.json.JSONException impossible){throw new IllegalArgumentException(impossible);}return meta;
    }
    private byte[] metadataBytes(Entry e){
        JSONObject meta=metadata(e.region);try{
            if(meta.optInt("version",0)<METADATA_VERSION&&!meta.has("legacy_metadata"))meta.put("legacy_metadata",new String(e.region.getMetadata(),StandardCharsets.UTF_8));
            meta.put("owner",OWNER).put("version",METADATA_VERSION).put("sdk_id",e.region.getId()).put("name",e.name).put("created_at",e.createdAt).put("updated_at",e.updatedAt).put("lat",e.latitude).put("lon",e.longitude).put("radius_km",e.radius).put("north",e.bounds[0]).put("east",e.bounds[1]).put("south",e.bounds[2]).put("west",e.bounds[3]).put("min_zoom",e.minZoom).put("max_zoom",e.maxZoom).put("style",e.style).put("state",e.state.name()).put("replacement_of",e.replacementOf);
        }catch(org.json.JSONException impossible){throw new IllegalArgumentException(impossible);}return bytes(meta);
    }
    private void persist(Entry e,Runnable success){persist(e,success,null);}
    private void persist(Entry e,Runnable success,Runnable rollback){if(!live(e))return;e.updatedAt=System.currentTimeMillis();e.writes.add(new Write(metadataBytes(e),success,rollback));writeNext(e);}
    private void writeNext(Entry e){
        if(!live(e)||e.writing||e.writes.isEmpty())return;e.writing=true;Write write=e.writes.remove();
        e.region.updateMetadata(write.metadata,new OfflineRegion.OfflineRegionUpdateMetadataCallback(){
            @Override public void onUpdate(byte[]metadata){e.writing=false;if(!live(e))return;if(write.success!=null)write.success.run();writeNext(e);notifyListeners();}
            @Override public void onError(String error){e.writing=false;if(!live(e))return;e.writes.clear();if(write.rollback!=null)write.rollback.run();e.starting=false;e.metadataFailed=true;if(e.state==State.DELETING)e.statusResource=R.string.offline_delete_intent_failed;else{e.state=State.ERROR;e.statusResource=R.string.offline_metadata_failed;}e.region.setDownloadState(OfflineRegion.STATE_INACTIVE);notifyListeners();}
        });
    }
    private boolean live(Entry e){return entries.get(e.region.getId())==e;}
    private String normalizedName(String value){String name=value==null?"":value.trim();return name.isEmpty()?app.getString(R.string.offline_default_name):name.substring(0,Math.min(80,name.length()));}
    private static JSONObject metadata(OfflineRegion region){try{return new JSONObject(new String(region.getMetadata(),StandardCharsets.UTF_8));}catch(Exception invalid){return new JSONObject();}}
    private static byte[]bytes(JSONObject value){return value.toString().getBytes(StandardCharsets.UTF_8);}
    public static int invalidAreaResource(IllegalArgumentException error){String text=error.getMessage();return text!=null&&text.contains("date line")?R.string.offline_date_line:text!=null&&text.contains("latitudes")?R.string.offline_polar:R.string.offline_invalid_center;}
    private static int classifyError(String value,int fallback){String text=value==null?"":value.toLowerCase(java.util.Locale.ROOT);return text.contains("full")||text.contains("space")||text.contains("sqlite_full")?R.string.offline_space_shortage:text.contains("401")||text.contains("403")?R.string.offline_access_error:text.contains("429")||text.contains("quota")?R.string.offline_quota_error:fallback;}
    private void updateFreeSpace(){try{freeBytes=new StatFs(app.getFilesDir().getAbsolutePath()).getAvailableBytes();}catch(RuntimeException unavailable){freeBytes=0;}}
    private void setGlobal(int resource){globalStatus=app.getString(resource);notifyListeners();}
    private void notifyListeners(){for(Listener listener:listeners)listener.onOfflineMapChanged();}
}
