package org.maplibre.android.offline;
import org.maplibre.android.geometry.LatLngBounds;
public class OfflineTilePyramidRegionDefinition {
    private final String style;private final LatLngBounds bounds;private final double min,max;
    public OfflineTilePyramidRegionDefinition(String style,LatLngBounds bounds,double min,double max,float ratio,boolean ideographs){this.style=style;this.bounds=bounds;this.min=min;this.max=max;}
    public LatLngBounds getBounds(){return bounds;}public double getMinZoom(){return min;}public double getMaxZoom(){return max;}public String getStyleURL(){return style;}
}
