package com.fiskentra.app.model;

/** UI quality and the intentionally different operation gates share monotonic freshness. */
public final class LocationQualityPolicy {
    private LocationQualityPolicy() {}
    public enum State { LOCATING, FRESH, STALE, PERMISSION_REQUIRED, LOCATION_DISABLED, UNAVAILABLE }
    public static boolean fresh(long fixNanos,long nowNanos) { return fixNanos>0 && nowNanos>=fixNanos && nowNanos-fixNanos<=30_000_000_000L; }
    public static boolean accuracy(double meters) { return Double.isFinite(meters)&&meters>0; }
    public static String quality(double meters) { return !accuracy(meters)?"unknown":meters<=25?"good":meters<=75?"fair":"poor"; }
    public static boolean track(long fix,long now,double accuracy) { return fresh(fix,now)&&accuracy(accuracy)&&accuracy<=75; }
    public static boolean guidance(long fix,long now,double accuracy) { return fresh(fix,now)&&accuracy(accuracy)&&accuracy<=50; }
    public static boolean arrival(long fix,long now,double accuracy) { return fresh(fix,now)&&accuracy(accuracy)&&accuracy<=25; }
    public static State state(boolean permission,boolean enabled,boolean hasFix,long fix,long now) {
        if(!permission)return State.PERMISSION_REQUIRED;
        if(!enabled)return State.LOCATION_DISABLED;
        if(!hasFix)return State.LOCATING;
        return fresh(fix,now)?State.FRESH:State.STALE;
    }
    public static double[] offset(double lat,double lon,double meters,double bearing) {
        double a=Math.toRadians(lat),b=Math.toRadians(lon),c=Math.toRadians(bearing),d=meters/6371008.8;
        double y=Math.asin(Math.sin(a)*Math.cos(d)+Math.cos(a)*Math.sin(d)*Math.cos(c));
        double x=b+Math.atan2(Math.sin(c)*Math.sin(d)*Math.cos(a),Math.cos(d)-Math.sin(a)*Math.sin(y));
        return new double[]{Math.toDegrees(y),(Math.toDegrees(x)+540)%360-180};
    }
}
