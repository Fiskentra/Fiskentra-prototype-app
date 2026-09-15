package com.fiskentra.app.model;

/** Camera input arbitration, independent of Android animations and sensor callbacks. */
public final class MapCameraPolicy {
    public enum Mode { FREE, FOLLOW_POSITION, FOLLOW_HEADING }
    private Mode mode = Mode.FREE;
    private boolean course;
    private boolean orientationAvailable;
    private double stable = Double.NaN;
    public static final long HEADING_MAX_AGE_MILLIS = 3000;
    public boolean orientationAvailable() { return orientationAvailable; }
    public static boolean sensorFresh(long sampledAt,long now) { return sampledAt>0 && now>=sampledAt && now-sampledAt<=HEADING_MAX_AGE_MILLIS; }
    public Mode mode() { return mode; }
    public void restore(String value) { try { mode=Mode.valueOf(value); } catch(Exception e) { mode=Mode.FREE; } }
    public void gesture() { mode=Mode.FREE; }
    public void locate(boolean headingAvailable) {
        mode=mode==Mode.FOLLOW_POSITION && headingAvailable ? Mode.FOLLOW_HEADING : Mode.FOLLOW_POSITION;
    }
    public void north() { if(mode==Mode.FOLLOW_HEADING)mode=Mode.FOLLOW_POSITION; }
    public double orientation(double speed, double bearing, double sensor, boolean sensorReliable) {
        if(!Double.isFinite(speed)||speed<1.2)course=false;
        else if(speed>=1.7)course=true;
        double target=course&&Double.isFinite(bearing)?bearing:sensorReliable?sensor:Double.NaN;
        orientationAvailable=Double.isFinite(target);
        if(!orientationAvailable && mode==Mode.FOLLOW_HEADING)mode=Mode.FOLLOW_POSITION;
        if(!Double.isFinite(target))return stable;
        target=normalize(target);
        if(!Double.isFinite(stable))stable=target;
        else { double delta=delta(stable,target); if(Math.abs(delta)>=5)stable=normalize(stable+delta*.3); }
        return stable;
    }
    public double orientation(double speed,double bearing,double sensor,boolean sensorReliable,long sampledAt,long now) {
        return orientation(speed,bearing,sensor,sensorReliable&&sensorFresh(sampledAt,now));
    }
    public static double normalize(double angle) { return (angle%360+360)%360; }
    public static double delta(double from,double to) { return (to-from+540)%360-180; }
}
