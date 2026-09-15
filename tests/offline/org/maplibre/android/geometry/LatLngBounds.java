package org.maplibre.android.geometry;
public class LatLngBounds {
    private final double n,e,s,w;
    private LatLngBounds(double n,double e,double s,double w){this.n=n;this.e=e;this.s=s;this.w=w;}
    public static LatLngBounds from(double n,double e,double s,double w){return new LatLngBounds(n,e,s,w);}
    public double getLatNorth(){return n;}public double getLonEast(){return e;}public double getLatSouth(){return s;}public double getLonWest(){return w;}
    public Center getCenter(){return new Center((n+s)/2,(e+w)/2);}
    public static class Center{final double lat,lon;Center(double lat,double lon){this.lat=lat;this.lon=lon;}public double getLatitude(){return lat;}public double getLongitude(){return lon;}}
}
