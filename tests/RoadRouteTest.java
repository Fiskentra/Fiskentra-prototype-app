import com.fiskentra.app.model.RoadRoute;
import com.fiskentra.app.model.FieldNavigation;
import java.util.*;

public final class RoadRouteTest {
    private static int checks;
    private static void check(boolean test,String label) { checks++; if(!test)throw new AssertionError(label); }
    private static void near(double actual,double expected,double tolerance,String label) { check(Math.abs(actual-expected)<=tolerance,label+": "+actual); }
    private static void rejects(Runnable code,String label) { boolean rejected=false; try{code.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected,label); }
    private static RoadRoute.Coordinate p(double lat,double lon) { return new RoadRoute.Coordinate(lat,lon); }
    public static void main(String[] args) {
        List<RoadRoute.Coordinate> shape=Arrays.asList(p(0,0),p(.001,0),p(.001,.001));
        List<RoadRoute.Step> steps=Arrays.asList(new RoadRoute.Step(0,1,1,"Head north",100),new RoadRoute.Step(1,2,10,"Turn right",200),new RoadRoute.Step(2,2,4,"Arrive",0));
        double half=FieldNavigation.distance(0,0,.001,0);
        RoadRoute r=new RoadRoute(shape,steps,half*2,300,"auto",.001,.001);
        RoadRoute.Progress start=r.progress(0,0,Double.NaN);
        near(start.remainingSeconds,300,.01,"Initial ETA"); check(start.nextStep==1,"First turn skips departure"); near(start.toTurn,half,.01,"Distance to first turn"); check(!start.atEnd,"Start is not arrival");
        RoadRoute.Progress middle=r.progress(.0005,0,start.along);
        near(middle.remainingSeconds,250,.1,"ETA uses step-specific speed"); near(middle.remainingMeters,half*1.5,.1,"Remaining route length"); near(middle.toTurn,half/2,.1,"Turn approach distance");
        RoadRoute.Progress turned=r.progress(.001,.0005,middle.along);
        check(turned.nextStep==2,"Passed maneuver advances"); near(turned.remainingSeconds,100,.1,"ETA after turn");
        RoadRoute.Progress end=r.progress(.001,.001,turned.along);
        check(end.atEnd,"Arrival at route end"); near(end.remainingSeconds,0,.01,"Arrival ETA zero"); near(end.remainingMeters,0,.01,"Arrival distance zero");
        RoadRoute.Progress off=r.progress(.005,.005,middle.along);
        check(off.offRoute>400,"Off-route detection"); check(!off.atEnd,"Projection to route end is not arrival when far away");
        RoadRoute.Progress back=r.progress(.0001,0,middle.along);
        check(back.along<middle.along,"Backtracking does not falsely advance");
        RoadRoute gap=new RoadRoute(shape,steps,half*2,300,"auto",.002,.001);
        near(gap.destinationGap(),half,.1,"Off-road destination gap remains explicit");
        List<RoadRoute.Coordinate> decoded=RoadRoute.decodePolyline6("??o}@??n}@");
        check(decoded.size()==3,"Polyline point count"); near(decoded.get(1).lat,.001,1e-9,"Six digit precision"); near(decoded.get(2).lon,-.001,1e-9,"Negative longitude decode");
        rejects(()->RoadRoute.decodePolyline6("_"),"Truncated varint rejected"); rejects(()->RoadRoute.decodePolyline6("?"),"Missing longitude rejected"); rejects(()->RoadRoute.decodePolyline6("!!"),"Invalid encoded characters rejected");
        rejects(()->new RoadRoute.Coordinate(Double.NaN,0),"NaN GPS rejected");
        rejects(()->new RoadRoute(shape,Arrays.asList(new RoadRoute.Step(0,99,1,"Bad",0)),1,1,"auto",0,0),"Invalid maneuver index rejected");
        rejects(()->new RoadRoute(shape,steps,1,Double.NaN,"auto",0,0),"NaN duration rejected");
        rejects(()->new RoadRoute(Arrays.asList(p(0,0),p(0,0)),Arrays.asList(new RoadRoute.Step(0,1,1,"Zero",0)),0,0,"auto",0,0),"Zero geometry rejected");
        check(new RoadRoute.Step(0,1,21,"Exit left",1).arrow().equals("↰"),"Exit-left icon"); check(new RoadRoute.Step(0,1,22,"Stay straight",1).arrow().equals("↑"),"Stay-straight icon");
        List<RoadRoute.Coordinate> loop=Arrays.asList(p(0,0),p(.001,0),p(.001,.001),p(0,.001),p(0,0));
        RoadRoute loopRoute=new RoadRoute(loop,Arrays.asList(new RoadRoute.Step(0,4,1,"Loop",400),new RoadRoute.Step(4,4,4,"Arrive",0)),444,400,"auto",0,0);
        check(!loopRoute.progress(0,0,0).atEnd,"Loop start not mistaken for destination");
        check(loopRoute.progress(0,0,loopRoute.length()-5).atEnd,"Loop completion respects progress");
        boolean immutable=false; try{r.shape.clear();}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"Route geometry immutable");
        com.fiskentra.app.model.NavigationCue cues=new com.fiskentra.app.model.NavigationCue();
        check(cues.advance(1,500,false),"First voice cue");
        check(!cues.advance(1,450,false),"No repeated distant cue");
        check(cues.advance(1,99,false),"Approach voice cue");
        check(!cues.advance(1,101,false)&&!cues.advance(1,99,false),"No cue chatter at threshold");
        check(cues.advance(1,20,false),"Immediate turn cue");
        check(cues.advance(2,400,false),"Next maneuver cue");
        check(cues.advance(2,0,true)&&!cues.advance(2,0,true),"Arrival spoken once");
        check(!cues.advance(2,50,false),"Arrival jitter stays quiet");
        cues.reset();check(cues.advance(1,500,false),"New route resets cues");
        System.out.println("PASS: "+checks+" road route checks");
    }
}
