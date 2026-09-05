import com.fiskentra.app.model.TripStatistics;
import java.time.Instant;
import java.util.*;

/** Run with scripts/Test-TripStatistics.ps1; no Android or network required. */
public final class TripStatisticsTest {
    private static int checks;
    private static void check(boolean value,String message) {
        checks++; if(!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(!TripStatistics.includes(100,0,200),"No trip must not include lifetime catches");
        check(!TripStatistics.includes(100,200,100),"Invalid range");
        check(TripStatistics.includes(100,100,200),"Start boundary inclusive");
        check(TripStatistics.includes(200,100,200),"End boundary inclusive");
        check(!TripStatistics.includes(99,100,200),"Exclude previous trip");
        check(!TripStatistics.includes(201,100,200),"Exclude later trip");
        List<Long> times=Arrays.asList(Instant.parse("2026-09-04T03:59:00Z").toEpochMilli(),Instant.parse("2026-09-04T04:00:00Z").toEpochMilli(),Instant.parse("2026-09-04T23:59:00Z").toEpochMilli());
        check(Arrays.equals(TripStatistics.hourlyBuckets(times,TimeZone.getTimeZone("UTC")),new int[]{1,1,0,0,0,1}),"Four-hour boundaries");
        check(Arrays.equals(TripStatistics.hourlyBuckets(times,TimeZone.getTimeZone("GMT+02:00")),new int[]{1,2,0,0,0,0}),"Local timezone and midnight rollover");
        check(Arrays.equals(TripStatistics.hourlyBuckets(Collections.emptyList(),TimeZone.getTimeZone("UTC")),new int[6]),"Empty histogram");
        Map<String,Integer> lures=TripStatistics.lureCounts(Arrays.asList(" Bait ","Bait",null,"","Spinner","Wobbler","Jig"));
        check(lures.size()==4 && lures.get("Bait")==2 && lures.get("Not recorded")==2 && lures.get("Other lures")==2,"Top three, unknown and aggregate counts");
        check(lures.values().stream().mapToInt(Integer::intValue).sum()==7,"Never lose catches");
        check(TripStatistics.lureCounts(Collections.emptyList()).isEmpty(),"Empty lure chart");
        Map<String,Integer> collision=TripStatistics.lureCounts(Arrays.asList("Other lures","Other lures","Other lures","A","B","C","D"));
        check(collision.get("Other lures")==5 && collision.values().stream().mapToInt(Integer::intValue).sum()==7,"Aggregate-label collision preserves totals");
        check(new ArrayList<>(TripStatistics.lureCounts(Arrays.asList("B","A")).keySet()).equals(Arrays.asList("A","B")),"Stable ordering for ties");
        System.out.println("TripStatistics: "+checks+" checks passed");
    }
}
