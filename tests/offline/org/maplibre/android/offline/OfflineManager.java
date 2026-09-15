package org.maplibre.android.offline;
import java.util.*;
public class OfflineManager {
    public interface ListOfflineRegionsCallback{void onList(OfflineRegion[]regions);void onError(String error);}
    public interface CreateOfflineRegionCallback{void onCreate(OfflineRegion region);void onError(String error);}
    public static OfflineManager INSTANCE=new OfflineManager();
    public final Map<Long,OfflineRegion>regions=new LinkedHashMap<>();public final ArrayDeque<Runnable>callbacks=new ArrayDeque<>();
    public boolean failCreate,failList;private long nextId=1;
    public static OfflineManager getInstance(android.content.Context context){return INSTANCE;}
    public void listOfflineRegions(ListOfflineRegionsCallback cb){callbacks.add(()->{if(failList)cb.onError("disk read error");else cb.onList(regions.values().toArray(new OfflineRegion[0]));});}
    public void createOfflineRegion(OfflineTilePyramidRegionDefinition def,byte[]meta,CreateOfflineRegionCallback cb){callbacks.add(()->{if(failCreate){cb.onError("creation failed");return;}OfflineRegion region=new OfflineRegion(nextId++,def,meta);regions.put(region.getId(),region);cb.onCreate(region);});}
    public void drain(){int count=0;while(!callbacks.isEmpty()){if(++count>300)throw new AssertionError("Unbounded callback loop");callbacks.remove().run();}}
    public void crash(){callbacks.clear();for(OfflineRegion region:regions.values()){region.observer=null;region.downloadState=OfflineRegion.STATE_INACTIVE;}}
}
