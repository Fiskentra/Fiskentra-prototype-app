import android.content.Context;
import android.os.StatFs;
import com.fiskentra.app.offline.OfflineMapController;
import com.fiskentra.app.model.OfflineAreaPolicy;
import org.maplibre.android.offline.*;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** SDK fault injection: verifies orchestration, not a substitute for native DB/airplane-mode device QA. */
public final class OfflineControllerTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        OfflineManager m=OfflineManager.INSTANCE=new OfflineManager();
        OfflineMapController c=new OfflineMapController(new Context());m.drain();
        check(c.snapshot().checked&&c.snapshot().areas.isEmpty(),"Empty SDK store is checked");
        c.download(null,"hybrid-v4",2);m.drain();check(m.regions.isEmpty(),"Missing GPS is not converted to zero coordinates");
        c.startDownload("Future lake",60,17,2,"hybrid-v4");m.drain();
        check(m.regions.size()==1,"Explicit future center downloads without GPS");
        OfflineRegion first=m.regions.values().iterator().next();long firstId=first.getId();
        check(first.downloadState==OfflineRegion.STATE_ACTIVE,"Download starts after metadata commit");
        check(c.snapshot().candidateArea.state==OfflineMapController.State.DOWNLOADING,"Downloading state visible");
        check(c.snapshot().candidateArea.indeterminate,"Unknown totals remain indeterminate");
        first.progress(false,false,100,100,1024);m.drain();
        check(!c.snapshot().candidateArea.complete&&c.snapshot().candidateArea.progress==-1,"Lower bound counts are not readiness");
        first.progress(true,false,100,100,2048);m.drain();check(c.snapshot().candidateArea.progress==99,"Resource count 100% still needs SDK complete");
        c.pause(firstId);m.drain();check(first.downloadState==OfflineRegion.STATE_INACTIVE&&c.snapshot().candidateArea.state==OfflineMapController.State.PAUSED,"Pause stops download and keeps bytes");
        check(c.snapshot().candidateArea.completedBytes==2048,"Pause preserves partial resources");
        c.resume(firstId);m.drain();check(first.downloadState==OfflineRegion.STATE_ACTIVE,"Resume reuses same SDK region");
        first.progress(true,true,100,100,4096);m.drain();
        check(c.snapshot().readyArea!=null&&c.snapshot().readyArea.id==firstId,"SDK complete becomes ready");
        check(first.downloadState==OfflineRegion.STATE_INACTIVE,"Complete pack stops active fetching");
        check(c.snapshot().readyArea.coverage("hybrid-v4",60,17,12)==OfflineAreaPolicy.Coverage.AVAILABLE,"Coverage uses matching actual definition");
        double[]b=c.snapshot().readyArea.bounds();b[0]=0;check(c.snapshot().readyArea.north>60,"Bounds are defensive copies");
        JSONObject meta=new JSONObject(new String(first.getMetadata(),StandardCharsets.UTF_8));
        check(meta.getLong("sdk_id")==firstId&&meta.getDouble("min_zoom")==8&&meta.getInt("version")==2,"Metadata records SDK ID, version and zoom");
        c.rename(firstId,"Week-end lake");m.drain();check(c.snapshot().readyArea.name.equals("Week-end lake"),"Rename commits to SDK metadata");
        m.crash();c=new OfflineMapController(new Context());m.drain();
        check(c.snapshot().readyArea!=null&&c.snapshot().readyArea.name.equals("Week-end lake"),"Restart restores actual complete pack and name");
        first.complete=false;m.crash();c=new OfflineMapController(new Context());
        check(c.snapshot().readyArea==null,"Persisted READY is not accepted before SDK status");m.drain();
        check(c.snapshot().readyArea==null,"SDK incomplete overrides persisted READY after restart");
        first.progress(true,true,100,100,4096);m.drain();
        StatFs.freeBytes=OfflineAreaPolicy.FREE_RESERVE_BYTES;c.startDownload("No space",61,18,2,"outdoor-v4");m.drain();
        check(m.regions.size()==1&&c.snapshot().readyArea.id==firstId,"Storage preflight keeps the ready map");
        StatFs.freeBytes=4L*1024*1024*1024;m.failCreate=true;c.startDownload("Provider failure",61,18,2,"outdoor-v4");m.drain();
        check(m.regions.size()==1&&c.snapshot().readyArea.id==firstId,"SDK creation failure keeps ready map");m.failCreate=false;
        c.startDownload("Replacement",61,18,2,"outdoor-v4");m.drain();
        OfflineRegion second=m.regions.get(c.snapshot().candidateArea.id);long secondId=second.getId();
        check(m.regions.size()==2&&c.snapshot().readyArea.id==firstId,"Candidate coexists with ready area");
        c.startDownload("Third",62,19,2,"outdoor-v4");m.drain();check(m.regions.size()==2,"Only one temporary candidate is allowed");
        second.error("connection","network disconnected");m.drain();check(c.snapshot().candidateArea.state==OfflineMapController.State.ERROR&&m.regions.containsKey(firstId),"Connection error pauses candidate without removing ready area");
        c.retry(secondId);m.drain();check(second.downloadState==OfflineRegion.STATE_ACTIVE,"Explicit retry recovers connection failure");
        StatFs.freeBytes=10;second.progress(true,false,10,100,4096);m.drain();
        check(second.downloadState==OfflineRegion.STATE_INACTIVE&&c.snapshot().candidateArea.state==OfflineMapController.State.ERROR&&m.regions.containsKey(firstId),"Disk pressure stops candidate and preserves old region");
        StatFs.freeBytes=4L*1024*1024*1024;c.retry(secondId);m.drain();
        second.failMetadata=true;second.progress(true,true,100,100,8192);m.drain();
        check(m.regions.containsKey(firstId),"Failed READY metadata commit cannot delete previous ready map");
        second.failMetadata=false;c.retry(secondId);m.drain();
        check(!m.regions.containsKey(firstId)&&m.regions.containsKey(secondId),"Old map is removed only after replacement ready metadata succeeds");
        check(c.snapshot().areas.size()==1&&c.snapshot().readyArea.id==secondId,"Replacement leaves one ready map");
        second.failMetadata=true;c.remove(secondId);m.drain();
        check(m.regions.containsKey(secondId)&&c.snapshot().areas.get(0).state==OfflineMapController.State.DELETING,"Deletion intent failure retains data and retryable operation");
        c.retry(secondId);m.drain();check(m.regions.containsKey(secondId),"Retry cannot bypass failed durable deletion intent");
        second.failMetadata=false;
        second.failDelete=true;c.retry(secondId);m.drain();
        check(c.snapshot().areas.size()==1&&c.snapshot().areas.get(0).state==OfflineMapController.State.DELETING,"Delete error retains region and pending intent");
        JSONObject deleting=new JSONObject(new String(second.getMetadata(),StandardCharsets.UTF_8));check(deleting.getString("state").equals("DELETING"),"Delete intent is durable before SDK deletion");
        m.crash();second.failDelete=false;c=new OfflineMapController(new Context());m.drain();
        check(m.regions.isEmpty()&&c.snapshot().areas.isEmpty(),"Restart resumes interrupted deletion");
        c.startDownload("Date line",0,179.99,5,"outdoor-v4");m.drain();check(m.regions.isEmpty(),"Date-line area rejected before SDK mutation");
        c.startDownload("Polar",85.05,0,5,"outdoor-v4");m.drain();check(m.regions.isEmpty(),"Polar radius rejected before SDK mutation");
        m.failList=true;c.refresh();m.drain();check(!c.snapshot().checked,"SDK list failure is not an empty checked database");
        System.out.println("OfflineController: "+checks+" checks passed");
    }
}
