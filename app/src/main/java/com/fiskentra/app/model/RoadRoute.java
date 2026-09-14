package com.fiskentra.app.model;

import java.util.*;

/** Provider-independent route and GPS projection. All distances are meters, times seconds. */
public final class RoadRoute {
    public static final class Coordinate {
        public final double lat, lon;
        public Coordinate(double lat, double lon) {
            if (!FieldNavigation.validCoordinate(lat,lon)) throw new IllegalArgumentException("Invalid route coordinate");
            this.lat=lat; this.lon=lon;
        }
    }
    public static final class Step {
        public final int begin, end, type;
        public final String instruction;
        public final String street;
        public final int roundaboutExit;
        public final double seconds;
        public Step(int begin,int end,int type,String instruction,double seconds) {
            this(begin,end,type,instruction,seconds,"",0);
        }
        public Step(int begin,int end,int type,String instruction,double seconds,String street,int roundaboutExit) {
            if(begin<0 || end<begin || !Double.isFinite(seconds) || seconds<0) throw new IllegalArgumentException("Invalid maneuver");
            this.begin=begin; this.end=end; this.type=type; this.instruction=instruction; this.seconds=seconds;
            this.street=street==null?"":street; this.roundaboutExit=roundaboutExit;
        }
        public String maneuverTitle() {
            switch(type) {
                case 4: case 5: case 6: return "Arrive";
                case 9: return "Bear right";
                case 10: return "Turn right";
                case 11: return "Sharp right";
                case 12: case 13: return "Make a U-turn";
                case 14: return "Sharp left";
                case 15: return "Turn left";
                case 16: return "Bear left";
                case 17: return "Take the ramp";
                case 18: return "Ramp right";
                case 19: return "Ramp left";
                case 20: return "Exit right";
                case 21: return "Exit left";
                case 23: return "Keep right";
                case 24: return "Keep left";
                case 25: case 37: case 38: return "Merge";
                case 26: return roundaboutExit>0?"Take exit "+roundaboutExit:"Roundabout";
                case 27: return "Exit roundabout";
                case 28: return "Take the ferry";
                case 29: return "Leave the ferry";
                default: return "Continue";
            }
        }
        public String arrow() {
            if(type==4||type==5||type==6) return "⚑";
            if(type==12||type==13) return "↶";
            if(type==10||type==11||type==18||type==20) return "↱";
            if(type==14||type==15||type==19||type==21) return "↰";
            if(type==9||type==23||type==37) return "↗";
            if(type==16||type==24||type==38) return "↖";
            if(type==26||type==27) return "⟳";
            return "↑";
        }
    }
    public static final class Progress {
        public final double along, offRoute, remainingMeters, remainingSeconds, toTurn;
        public final int nextStep;
        public final boolean atEnd;
        Progress(double along,double off,double meters,double seconds,double turn,int step,boolean end) {
            this.along=along; offRoute=off; remainingMeters=meters; remainingSeconds=seconds; toTurn=turn; nextStep=step; atEnd=end;
        }
    }
    public final List<Coordinate> shape;
    public final List<Step> steps;
    public final double meters, seconds, targetLat, targetLon;
    public final String profile;
    private final double[] cumulative;
    public RoadRoute(List<Coordinate> shape,List<Step> steps,double meters,double seconds,String profile,double targetLat,double targetLon) {
        if(shape.size()<2 || steps.isEmpty() || !Double.isFinite(meters)||meters<0 || !Double.isFinite(seconds)||seconds<0 || !FieldNavigation.validCoordinate(targetLat,targetLon)) throw new IllegalArgumentException("Incomplete route");
        int last=-1;
        for(Step s:steps) { if(s.begin<last||s.end>=shape.size()) throw new IllegalArgumentException("Invalid step index"); last=s.begin; }
        this.shape=Collections.unmodifiableList(new ArrayList<>(shape)); this.steps=Collections.unmodifiableList(new ArrayList<>(steps));
        this.meters=meters; this.seconds=seconds; this.profile=profile; this.targetLat=targetLat; this.targetLon=targetLon;
        cumulative=new double[shape.size()];
        for(int i=1;i<shape.size();i++) cumulative[i]=cumulative[i-1]+distance(shape.get(i-1),shape.get(i));
        if(cumulative[cumulative.length-1]<1) throw new IllegalArgumentException("Route is too short");
    }
    public double destinationGap() { Coordinate end=shape.get(shape.size()-1); return FieldNavigation.distance(end.lat,end.lon,targetLat,targetLon); }
    public double stepDistance(int index) { return cumulative[steps.get(index).begin]; }
    public double length() { return cumulative[cumulative.length-1]; }
    public Progress progress(double lat,double lon,double previousAlong) {
        if(!FieldNavigation.validCoordinate(lat,lon)) throw new IllegalArgumentException("Invalid GPS");
        double best=Double.POSITIVE_INFINITY, along=0, score=Double.POSITIVE_INFINITY;
        double scale=Math.cos(Math.toRadians(lat)), units=111195;
        for(int i=1;i<shape.size();i++) {
            Coordinate a=shape.get(i-1),b=shape.get(i);
            double ax=longitudeDelta(a.lon-lon)*scale*units,ay=(a.lat-lat)*units;
            double bx=longitudeDelta(b.lon-lon)*scale*units,by=(b.lat-lat)*units;
            double dx=bx-ax,dy=by-ay, squared=dx*dx+dy*dy;
            double t=squared==0?0:Math.max(0,Math.min(1,-(ax*dx+ay*dy)/squared));
            double off=Math.hypot(ax+t*dx,ay+t*dy), candidate=cumulative[i-1]+t*(cumulative[i]-cumulative[i-1]);
            // At crossings prefer nearby progress; a truly nearer road still wins after a long GPS gap.
            double continuity=Double.isFinite(previousAlong)?Math.min(35,Math.abs(candidate-previousAlong)*.025):0;
            if(off+continuity<score) { score=off+continuity; best=off; along=candidate; }
        }
        double remaining=0;
        for(Step step:steps) {
            double start=cumulative[step.begin],end=cumulative[step.end];
            if(along<=start) remaining+=step.seconds;
            else if(along<end && end>start) remaining+=step.seconds*(end-along)/(end-start);
        }
        int next=steps.size()-1;
        for(int i=1;i<steps.size();i++) if(cumulative[steps.get(i).begin]>=along-5) { next=i; break; }
        boolean atEnd=length()-along<=20 && best<=25;
        return new Progress(along,best,meters*Math.max(0,1-along/length()),Math.max(0,remaining),Math.max(0,cumulative[steps.get(next).begin]-along),next,atEnd);
    }
    private static double longitudeDelta(double d) { return (d+540)%360-180; }
    private static double distance(Coordinate a,Coordinate b) { return FieldNavigation.distance(a.lat,a.lon,b.lat,b.lon); }
    public static List<Coordinate> decodePolyline6(String encoded) {
        List<Coordinate> result=new ArrayList<>(); int[] cursor={0}; long lat=0,lon=0;
        while(cursor[0]<encoded.length()) {
            lat+=decodeValue(encoded,cursor); lon+=decodeValue(encoded,cursor);
            result.add(new Coordinate(lat/1e6,lon/1e6));
            if(result.size()>100000) throw new IllegalArgumentException("Route too large");
        }
        return result;
    }
    private static long decodeValue(String encoded,int[] cursor) {
        long value=0; int shift=0,b;
        do {
            if(cursor[0]>=encoded.length()||shift>35) throw new IllegalArgumentException("Malformed route geometry");
            b=encoded.charAt(cursor[0]++)-63;
            if(b<0||b>63) throw new IllegalArgumentException("Malformed route geometry");
            value|=(long)(b&31)<<shift; shift+=5;
        } while(b>=32);
        return (value&1)!=0?~(value>>1):value>>1;
    }
}
