package org.maplibre.android.offline;
public class OfflineRegion {
    public static final int STATE_INACTIVE=0,STATE_ACTIVE=1;
    public interface OfflineRegionObserver{void onStatusChanged(OfflineRegionStatus status);void onError(OfflineRegionError error);void mapboxTileCountLimitExceeded(long limit);}
    public interface OfflineRegionStatusCallback{void onStatus(OfflineRegionStatus status);void onError(String error);}
    public interface OfflineRegionDeleteCallback{void onDelete();void onError(String error);}
    public interface OfflineRegionUpdateMetadataCallback{void onUpdate(byte[]metadata);void onError(String error);}
    private final long id;private final OfflineTilePyramidRegionDefinition definition;private byte[]metadata;
    public OfflineRegionObserver observer;public int downloadState;public boolean failMetadata,failDelete,failStatus,complete,precise;public long done,required,bytes;
    public OfflineRegion(long id,OfflineTilePyramidRegionDefinition def,byte[]meta){this.id=id;definition=def;metadata=meta;}
    public long getId(){return id;}public Object getDefinition(){return definition;}public byte[]getMetadata(){return metadata;}
    public void setObserver(OfflineRegionObserver observer){this.observer=observer;}public void setDeliverInactiveMessages(boolean enabled){}
    public OfflineRegionStatus status(){return new OfflineRegionStatus(precise,complete,done,required,bytes,downloadState);}
    public void setDownloadState(int state){downloadState=state;emit();}
    public void getStatus(OfflineRegionStatusCallback cb){OfflineManager.INSTANCE.callbacks.add(()->{if(failStatus)cb.onError("unreadable status");else cb.onStatus(status());});}
    public void updateMetadata(byte[]value,OfflineRegionUpdateMetadataCallback cb){OfflineManager.INSTANCE.callbacks.add(()->{if(failMetadata)cb.onError("full");else{metadata=value;cb.onUpdate(value);}});}
    public void delete(OfflineRegionDeleteCallback cb){OfflineManager.INSTANCE.callbacks.add(()->{if(failDelete)cb.onError("busy");else{OfflineManager.INSTANCE.regions.remove(id);cb.onDelete();}});}
    public void emit(){OfflineRegionObserver target=observer;OfflineRegionStatus value=status();if(target!=null)OfflineManager.INSTANCE.callbacks.add(()->target.onStatusChanged(value));}
    public void progress(boolean precise,boolean complete,long done,long required,long bytes){this.precise=precise;this.complete=complete;this.done=done;this.required=required;this.bytes=bytes;emit();}
    public void error(String reason,String message){OfflineRegionObserver target=observer;if(target!=null)OfflineManager.INSTANCE.callbacks.add(()->target.onError(new OfflineRegionError(reason,message)));}
}
