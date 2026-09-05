import com.fiskentra.app.model.TrackPointPolicy;
import java.util.*;

public final class TrackPointPolicyTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        long now=1_000_000L,started=900_000L;
        List<double[]> empty=Collections.emptyList();
        check(TrackPointPolicy.shouldRecord(empty,true,started,60,16,now,5,now),"First fresh fix");
        check(!TrackPointPolicy.shouldRecord(empty,false,started,60,16,now,5,now),"Inactive trip");
        check(!TrackPointPolicy.shouldRecord(empty,true,started,91,16,now,5,now),"Invalid latitude");
        check(!TrackPointPolicy.shouldRecord(empty,true,started,60,16,now,76,now),"Poor accuracy");
        check(!TrackPointPolicy.shouldRecord(empty,true,started,60,16,started-1,5,now),"Pre-trip cached fix");
        check(!TrackPointPolicy.shouldRecord(empty,true,started,60,16,now-120001,5,now),"Stale fix");
        List<double[]> last=Collections.singletonList(new double[]{60,16,now-10_000});
        check(!TrackPointPolicy.shouldRecord(last,true,started,60.00001,16,now,5,now),"Movement below eight metres");
        check(TrackPointPolicy.shouldRecord(last,true,started,60.00010,16,now,5,now),"Walking movement");
        check(!TrackPointPolicy.shouldRecord(last,true,started,61,17,now,5,now),"Impossible GPS jump");
        check(!TrackPointPolicy.shouldRecord(last,true,started,60.00010,16,now-10_000,5,now),"Duplicate timestamp");
        System.out.println("TrackPointPolicy: "+checks+" checks passed");
    }
}
