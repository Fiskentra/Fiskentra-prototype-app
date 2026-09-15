import com.fiskentra.app.model.*;
import com.fiskentra.app.navigation.BacktrackCodec;
import java.util.*;
import java.nio.file.*;

public class BacktrackTest {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static double[] p(double north, double east) { return new double[]{north / 111195, east / 111195, 1000, 0}; }
    private static BacktrackProgress.Fix fix(double north, double east, long time) { return new BacktrackProgress.Fix(north / 111195, east / 111195, 5, time, true); }
    private static BacktrackRoute route(double... north) { List<double[]> points = new ArrayList<>(); for (double n : north) points.add(p(n, 0)); return BacktrackRoute.fromTrack("track", 7, 12345, points); }
    private static BacktrackProgress start(BacktrackRoute route, BacktrackProgress.Fix fix) { BacktrackProgress value = new BacktrackProgress(route); value.select(value.startCandidates(fix).get(0), fix); return value; }
    public static void main(String[] args) throws Exception {
        List<double[]> raw = new ArrayList<>(Arrays.asList(p(0, 0), p(100, 0), p(200, 0)));
        BacktrackRoute r = BacktrackRoute.fromTrack("trip", 3, 99, raw); raw.get(2)[0] = 88; raw.add(p(400, 0));
        check(Math.abs(r.segments.get(0).points.get(0).latitude * 111195 - 200) < .001, "snapshot is detached from recording");
        check(r.revision == 3 && r.capturedAtUtc == 99, "snapshot identity");
        check(r.start().latitude == 0 && r.geometry().size() == 3, "reverse end is original start");
        List<double[]> geometry = r.geometry(); geometry.get(0)[0] = 99; check(r.geometry().get(0)[0] != 99, "renderer copy is defensive");
        try { r.segments.clear(); throw new AssertionError("mutable"); } catch (UnsupportedOperationException expected) { checks++; }
        try { route(0, 0); throw new AssertionError("zero route"); } catch (IllegalArgumentException expected) { checks++; }
        List<double[]> split = new ArrayList<>(Arrays.asList(p(0,0),p(100,0),p(300,0),p(400,0))); split.get(2)[3] = 1;
        BacktrackRoute gaps = BacktrackRoute.fromTrack("gaps", 1, 1, split);
        check(gaps.segments.size() == 2 && gaps.hasGaps(), "pause remains two segments");
        check(Math.abs(gaps.knownLength() - 200) < 1, "unknown gap excluded from length");
        check(gaps.geometry().get(2)[3] == 1, "reverse boundary kept");
        check(split.get(0)[0] == 0 && split.get(2)[3] == 1, "original track preserved");
        BacktrackProgress engine = start(route(0,100,200,300), fix(300,0,1000));
        check(engine.snapshot().status == BacktrackProgress.Status.TRACKING, "on track starts");
        engine.update(fix(270,0,6000)); check(engine.snapshot().alongMeters > 29, "progress on line");
        double before = engine.snapshot().alongMeters; engine.update(fix(100,0,6000)); check(engine.snapshot().alongMeters == before, "duplicate timestamp rejected");
        engine.update(new BacktrackProgress.Fix(0,0,51,7000,true)); check(engine.snapshot().status == BacktrackProgress.Status.GPS_PAUSED && engine.snapshot().alongMeters == before, "poor accuracy never advances");
        engine.update(new BacktrackProgress.Fix(0,0,5,8000,false)); check(engine.snapshot().alongMeters == before, "stale position never advances");
        engine.update(fix(260,100,10000)); engine.update(fix(260,100,13000)); check(engine.snapshot().status != BacktrackProgress.Status.OFF_ROUTE, "off route needs three fresh fixes");
        engine.update(fix(260,100,16000)); check(engine.snapshot().status == BacktrackProgress.Status.OFF_ROUTE, "off route after three");
        engine.update(fix(260,0,19000)); check(engine.snapshot().status == BacktrackProgress.Status.OFF_ROUTE, "rejoin hysteresis");
        engine.update(fix(260,0,22000)); check(engine.snapshot().status == BacktrackProgress.Status.TRACKING, "two close fixes recover");
        double limitedBefore = engine.snapshot().alongMeters; engine.update(fix(0,0,23000)); check(engine.snapshot().alongMeters - limitedBefore < 50, "teleport cannot finish track");
        check(engine.snapshot().status != BacktrackProgress.Status.ARRIVED, "endpoint requires sequential progress");
        BacktrackProgress middle = start(route(0,100,200,300), fix(150,0,1000)); check(Math.abs(middle.snapshot().knownRemainingMeters - 150) < 1, "start partway by projection");
        BacktrackProgress approach = start(route(0,100,200), fix(200,500,1000)); check(approach.snapshot().status == BacktrackProgress.Status.APPROACH, "distant approach not invented route");
        BacktrackProgress gap = start(gaps, fix(400,0,1000)); gap.update(fix(350,0,6000)); gap.update(fix(305,0,11000)); gap.update(fix(300,0,14000));
        check(gap.snapshot().status == BacktrackProgress.Status.TRACK_GAP, "stops before gap");
        int segment = gap.snapshot().segmentIndex; gap.update(fix(100,0,17000)); check(gap.snapshot().segmentIndex == segment, "no implicit segment switch");
        check(!gap.continueAfterGap(fix(300,0,18000)), "cannot resume far from next segment");
        check(gap.continueAfterGap(fix(100,0,19000)), "explicit confirmed next segment");
        gap.update(fix(50,0,24000)); gap.update(fix(5,0,29000)); check(gap.snapshot().status != BacktrackProgress.Status.ARRIVED, "arrival requires two confirmations");
        gap.update(fix(0,0,32000)); check(gap.snapshot().status == BacktrackProgress.Status.ARRIVED, "arrival after sequential progress and two fixes");
        List<double[]> loop = Arrays.asList(p(0,0),p(100,0),p(100,100),p(0,100),p(0,5));
        BacktrackRoute loopRoute = BacktrackRoute.fromTrack("loop",1,1,loop); BacktrackProgress choice = new BacktrackProgress(loopRoute);
        List<BacktrackProgress.Candidate> candidates = choice.startCandidates(fix(0,5,1000)); check(candidates.size() >= 2, "near loop ends offers ambiguous sections");
        BacktrackProgress.Candidate first = candidates.stream().min(Comparator.comparingDouble(c -> c.alongMeters)).get(); choice.select(first,fix(0,5,1000));
        choice.update(fix(0,0,4000)); choice.update(fix(0,0,7000)); check(choice.snapshot().status != BacktrackProgress.Status.ARRIVED, "loop proximity cannot finish early");
        List<double[]> parallel = Arrays.asList(p(0,0),p(200,0),p(200,8),p(0,8));
        BacktrackProgress par = start(BacktrackRoute.fromTrack("parallel",1,1,parallel),fix(0,8,1000)); par.update(fix(25,0,4000));
        check(par.snapshot().alongMeters < 60 && par.snapshot().edgeIndex == 0, "parallel adjacent branch does not steal progress");
        BacktrackRoute crossing=BacktrackRoute.fromTrack("crossing",1,1,Arrays.asList(p(-100,-100),p(100,100),p(-100,100),p(100,-100)));
        BacktrackProgress cross=new BacktrackProgress(crossing);List<BacktrackProgress.Candidate> crossChoices=cross.startCandidates(fix(0,0,1000));
        check(crossChoices.size()>=2,"self-intersection has explicit route-position choices");
        cross.select(crossChoices.stream().min(Comparator.comparingDouble(c->c.alongMeters)).get(),fix(0,0,1000));
        cross.update(fix(-5,5,4000));check(cross.snapshot().edgeIndex==0&&cross.snapshot().alongMeters<170,"crossing continuation stays on chosen edge");
        String encoded = BacktrackCodec.encode(gaps, gap.snapshot()); Path file = Files.createTempFile("fiskentra-backtrack-test", ".json");
        try { Files.writeString(file, encoded); BacktrackProgress reopened = BacktrackCodec.decode(Files.readString(file)); check(reopened.route.id.equals("gaps") && reopened.route.segments.size() == 2, "persisted snapshot reopened from disk"); check(reopened.snapshot().status == BacktrackProgress.Status.ARRIVED, "arrival restored"); }
        finally { Files.delete(file); }
        BacktrackProgress restored = BacktrackCodec.decode(BacktrackCodec.encode(middle.route, middle.snapshot()));
        check(restored.snapshot().status == BacktrackProgress.Status.GPS_PAUSED && restored.snapshot().alongMeters == middle.snapshot().alongMeters, "resume requires new fresh fix");
        restored.update(new BacktrackProgress.Fix(0,0,5,1,false)); check(restored.snapshot().status == BacktrackProgress.Status.GPS_PAUSED, "old fix after restart rejected");
        try { BacktrackCodec.decode(encoded.replace("\"schema\":1", "\"schema\":9")); throw new AssertionError("unknown schema"); } catch (IllegalArgumentException expected) { checks++; }
        check(BacktrackProgress.deviationThreshold(20) == 40, "accuracy-scaled deviation threshold");
        check(Double.isNaN(restored.snapshot().bearing),"paused GPS never supplies a guidance bearing");
        check(Double.isNaN(gap.snapshot().bearing),"arrival has no stale guidance bearing");
        BacktrackProgress progressRestored=BacktrackCodec.restoreProgress(new BacktrackProgress(middle.route),BacktrackCodec.encodeProgress(middle.route,middle.snapshot()));
        check(progressRestored.snapshot().alongMeters==middle.snapshot().alongMeters,"small progress record restores immutable route identity");
        BacktrackProgress other=new BacktrackProgress(route(0,10));
        check(BacktrackCodec.restoreProgress(other,BacktrackCodec.encodeProgress(gaps,gap.snapshot()))==other,"another snapshot cannot acquire stale progress");
        try{BacktrackProgress.restore(middle.route,0,0,0,100,BacktrackProgress.Status.TRACKING);throw new AssertionError("corrupt progress");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("PASS " + checks + " backtrack checks");
    }
}
