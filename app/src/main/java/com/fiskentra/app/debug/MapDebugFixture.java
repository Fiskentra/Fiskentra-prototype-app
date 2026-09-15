package com.fiskentra.app.debug;

import com.fiskentra.app.BuildConfig;
import com.fiskentra.app.model.SavedPoint;
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
    public final double centerLatitude=CENTER_LATITUDE,centerLongitude=CENTER_LONGITUDE;
    public final long tripId=TRIP_ID;
    private MapDebugFixture(String name,int pointCount,int coordinateCount){
        this.name=name;Random random=new Random(0xF15CE17AL);
        ArrayList<SavedPoint> points=new ArrayList<>(pointCount);
        String[]types={"Catch","Waypoint","Catch","Camp","Hazard"};
        for(int i=0;i<pointCount;i++){
            double latitude=CENTER_LATITUDE+(random.nextDouble()-.5)*.07;
            double longitude=CENTER_LONGITUDE+(random.nextDouble()-.5)*.11;
            // Exact coincident markers exercise cluster-list fallback without moving their coordinates.
            if(i>0&&i%37==0){latitude=points.get(i-1).latitude;longitude=points.get(i-1).longitude;}
            points.add(new SavedPoint(-1000000000L-i,latitude,longitude,TRIP_ID+i*1000L,types[i%types.length],"Debug fixture",null,null,
                    "QA "+types[i%types.length]+" "+i,"",0,18,"qa:"+name+":"+i,TRIP_ID,i%7==0,1,
                    "LOCATED",TRIP_ID+i*1000L,8,"debug"));
        }
        this.points=Collections.unmodifiableList(points);
        ArrayList<double[]>track=new ArrayList<>(coordinateCount);
        for(int i=0;i<coordinateCount;i++){
            double t=i*6*Math.PI/(coordinateCount-1d);
            track.add(new double[]{CENTER_LATITUDE+.022*Math.sin(t)+.004*Math.sin(11*t),
                    CENTER_LONGITUDE+.038*Math.cos(t)+.006*Math.sin(17*t),TRIP_ID+i*1000L,i>0&&i%2500==0?1:0});
        }
        this.track=Collections.unmodifiableList(track);
    }
    /** Call on a worker. Release builds cannot activate the synthetic dataset even via an intent. */
    public static MapDebugFixture create(String name){
        if(!BuildConfig.DEBUG)throw new IllegalStateException("Map fixtures are debug-only");
        if("standard".equals(name))return new MapDebugFixture(name,1000,10000);
        if("stress".equals(name))return new MapDebugFixture(name,10000,50000);
        throw new IllegalArgumentException("Use standard or stress");
    }
    public static boolean synthetic(SavedPoint point){return point!=null&&point.id<=-1000000000L&&point.eventId.startsWith("qa:");}
}
