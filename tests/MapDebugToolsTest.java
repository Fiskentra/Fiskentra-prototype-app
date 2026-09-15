import com.fiskentra.app.debug.FrameTimingStats;
import com.fiskentra.app.debug.MapDebugFixture;
import com.fiskentra.app.model.FieldNavigation;
import com.fiskentra.app.model.SavedPoint;
import java.util.HashSet;

public final class MapDebugToolsTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[]args){
        MapDebugFixture standard=MapDebugFixture.create("standard"),stress=MapDebugFixture.create("stress");
        check(standard.points.size()==1000&&standard.track.size()==10000,"Standard exact acceptance workload");
        check(stress.points.size()==10000&&stress.track.size()==50000,"Stress exact acceptance workload");
        HashSet<Long>ids=new HashSet<>();for(SavedPoint p:stress.points){ids.add(p.id);if(!p.hasLocation()||!MapDebugFixture.synthetic(p))throw new AssertionError("Invalid synthetic point");}
        check(ids.size()==10000,"All synthetic IDs are stable and unique");
        check(stress.points.get(37).latitude==stress.points.get(36).latitude&&stress.points.get(37).longitude==stress.points.get(36).longitude,"Dataset includes exact coincident markers");
        check(standard.points.get(0).latitude==stress.points.get(0).latitude,"Seeded point positions are reproducible");
        check(standard.points.get(0).tripId==MapDebugFixture.TRIP_ID&&standard.points.get(0).favorite,"Fixture supports current-trip and favorites filters");
        int gaps=0;for(int i=1;i<stress.track.size();i++)if(FieldNavigation.segmentBreak(stress.track.get(i-1),stress.track.get(i)))gaps++;
        check(gaps==19,"Stress original geometry has 19 retained segment boundaries");
        boolean immutable=false;try{standard.points.clear();}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"Point dataset container is immutable");
        boolean invalid=false;try{MapDebugFixture.create("arbitrary");}catch(IllegalArgumentException expected){invalid=true;}check(invalid,"Intent accepts only bounded workloads");
        FrameTimingStats normal=new FrameTimingStats();for(int i=0;i<190;i++)normal.add(16000000,false,0);for(int i=0;i<10;i++)normal.add(60000000,false,0);
        FrameTimingStats.Summary n=normal.summary();check(n.p95Ms==16&&n.over50Percent==5,"Nearest-rank p95 and strict >50ms percentage");check(n.meetsStandardBudget(60),"Exact 5% slow-frame boundary is permitted");check(!n.meetsStandardBudget(120),"A 120Hz result cannot be claimed as the required 60Hz benchmark");
        normal.add(100000000,true,0);check(normal.summary().count==200&&normal.summary().firstDrawExcluded==1,"First draw is separately counted, not silently mixed");
        normal.add(16000000,false,2);check(!normal.summary().completeSample&&normal.summary().droppedReports==2,"Dropped reports invalidate complete measurement without calling them dropped frames");
        FrameTimingStats empty=new FrameTimingStats();check(Double.isNaN(empty.summary().p95Ms)&&!empty.summary().completeSample,"Empty samples cannot pass");
        empty.add(-1,false,0);check(empty.summary().invalid==1,"Unsupported metric is not reported as a fast frame");
        FrameTimingStats bounded=new FrameTimingStats();for(int i=0;i<25000;i++)bounded.add(16000000,false,0);check(bounded.summary().count==24000&&bounded.summary().overflow==1000&&!bounded.summary().completeSample,"Capture is bounded and overflow is visible");
        System.out.println("MapDebugTools: "+checks+" checks passed");
    }
}
