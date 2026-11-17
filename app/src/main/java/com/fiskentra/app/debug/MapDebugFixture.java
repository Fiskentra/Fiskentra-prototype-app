package com.fiskentra.app.debug;

import com.fiskentra.app.BuildConfig;
import com.fiskentra.app.model.SavedPoint;
import com.fiskentra.app.model.FishingDay;
import com.fiskentra.app.model.CatchDetails;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Deterministic in-memory datasets. No storage, sync, real GPS or photo access. */
public final class MapDebugFixture {
    public static final double CENTER_LATITUDE=52.52,CENTER_LONGITUDE=13.405;
    public static final long TRIP_ID=1788220800000L;
    public final String name;
    public final List<SavedPoint> points;
    public final List<double[]> track;
    public final double centerLatitude,centerLongitude;
    public final List<FishingDay> pastTrips;
    public final long tripId=TRIP_ID;
    private MapDebugFixture(String name,int pointCount,int coordinateCount){
        this.name=name;Random random=new Random(0xF15CE17AL);
        centerLatitude="layers".equals(name)?54.18:CENTER_LATITUDE;centerLongitude="layers".equals(name)?12.10:CENTER_LONGITUDE;
        ArrayList<SavedPoint> points=new ArrayList<>(pointCount);
        String[]types={"Catch","Waypoint","Catch","Camp","Hazard"};
        for(int i=0;i<pointCount;i++){
            double latitude=centerLatitude+(random.nextDouble()-.5)*.07;
            double longitude=centerLongitude+(random.nextDouble()-.5)*.11;
            // Exact coincident markers exercise cluster-list fallback without moving their coordinates.
            if(i>0&&i%37==0){latitude=points.get(i-1).latitude;longitude=points.get(i-1).longitude;}
            CatchDetails catchDetails="layers".equals(name)&&"Catch".equals(types[i%types.length])?new CatchDetails(i%2==0?"Perch":"Pike",0,0,"","",true,""):null;
            points.add(new SavedPoint(-1000000000L-i,latitude,longitude,TRIP_ID+i*1000L,types[i%types.length],"Debug fixture",null,catchDetails,
                    "QA "+types[i%types.length]+" "+i,"",0,18,"qa:"+name+":"+i,TRIP_ID,i%7==0,1,
                    "LOCATED",TRIP_ID+i*1000L,8,"debug"));
        }
        this.points=Collections.unmodifiableList(points);
        ArrayList<double[]>track=new ArrayList<>(coordinateCount);
        for(int i=0;i<coordinateCount;i++){
            double t=i*6*Math.PI/(coordinateCount-1d);
            track.add(new double[]{centerLatitude+.022*Math.sin(t)+.004*Math.sin(11*t),
                    centerLongitude+.038*Math.cos(t)+.006*Math.sin(17*t),TRIP_ID+i*1000L,i>0&&i%2500==0?1:0});
        }
        this.track=Collections.unmodifiableList(track);
        ArrayList<FishingDay> history=new ArrayList<>();
        if("layers".equals(name))for(int trip=0;trip<3;trip++){
            ArrayList<double[]> route=new ArrayList<>();long start=TRIP_ID-(trip+1)*86400000L;
            for(int i=0;i<90;i++){double phase=i/89d*Math.PI*2;route.add(new double[]{centerLatitude+.007*(trip+1)*Math.sin(phase),centerLongitude+.012*(trip+1)*Math.cos(phase),start+i*60000L,i==45?1:0});}
            history.add(new FishingDay(start,start,start+90*60000L,null,null,route));
        }
        pastTrips=Collections.unmodifiableList(history);
    }
    /** Call on a worker. Release builds cannot activate the synthetic dataset even via an intent. */
    public static MapDebugFixture create(String name){
        if(!BuildConfig.DEBUG)throw new IllegalStateException("Map fixtures are debug-only");
        if("standard".equals(name))return new MapDebugFixture(name,1000,10000);
        if("stress".equals(name))return new MapDebugFixture(name,10000,50000);
        if("layers".equals(name))return new MapDebugFixture(name,75,180);
        throw new IllegalArgumentException("Use standard or stress");
    }
    public static boolean synthetic(SavedPoint point){return point!=null&&point.id<=-1000000000L&&point.eventId.startsWith("qa:");}
}
