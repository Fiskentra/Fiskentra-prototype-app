package com.fiskentra.app.ui;
public class MapTilerMapView {
    public static final String STYLE_OUTDOOR="outdoor-v4",STYLE_HYBRID="hybrid-v4",STYLE_TOPO="topo-v4",STYLE_OCEAN="ocean-v4";
    public static String normalizeStyleId(String value){return STYLE_HYBRID.equals(value)||STYLE_TOPO.equals(value)||STYLE_OCEAN.equals(value)?value:STYLE_OUTDOOR;}
    public static String styleName(String value){return value;}
    public static String styleUrl(String value){return "https://example.invalid/maps/"+value+"/style.json";}
}
