package org.maplibre.android.offline;
public class OfflineRegionStatus {
    public final boolean precise,complete;public final long done,required,bytes;public final int state;
    public OfflineRegionStatus(boolean precise,boolean complete,long done,long required,long bytes,int state){this.precise=precise;this.complete=complete;this.done=done;this.required=required;this.bytes=bytes;this.state=state;}
    public boolean isComplete(){return complete;}public boolean isRequiredResourceCountPrecise(){return precise;}
    public long getCompletedResourceCount(){return done;}public long getRequiredResourceCount(){return required;}public long getCompletedResourceSize(){return bytes;}public int getDownloadState(){return state;}
}
