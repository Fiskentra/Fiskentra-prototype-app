package com.fiskentra.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import com.fiskentra.app.R;
import com.fiskentra.app.model.MapFilterPolicy;
import com.fiskentra.app.model.SavedPoint;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Android-rendered marker sprites: no remote font glyph dependency and a fixed logical density. */
final class MapMarkerImages {
    static final int PIXELS = 72;
    static final float DEFAULT_ICON_SCALE = 1f / 3f; // 72px at160dpi ->24dp box, ~18dp visible glyph.
    private final Context context;
    private final Map<String,Bitmap> cache = new LinkedHashMap<String,Bitmap>(32,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Bitmap> oldest) { return size()>256; }
    };
    MapMarkerImages(Context context) { this.context=context.getApplicationContext(); }
    static String id(SavedPoint point) {
        int type=Math.max(0,Arrays.asList(MapFilterPolicy.TYPES).indexOf(MapFilterPolicy.category(point.type)));
        int fill=point.color==0?MapTilerMapView.colorFor(point.type):point.color;
        String symbol=Base64.encodeToString(point.symbol.getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        return "fisk-glyph-"+type+"-"+(MapTilerMapView.markerInk(fill)==Color.WHITE?"w":"b")+"-"+symbol;
    }
    synchronized Bitmap get(SavedPoint point) {
        String id=id(point);Bitmap old=cache.get(id);if(old!=null&&!old.isRecycled())return old;
        Bitmap bitmap=Bitmap.createBitmap(PIXELS,PIXELS,Bitmap.Config.ARGB_8888);
        // MapLibre Android uses density/160 as pixelRatio. Never inherit the device's density here.
        bitmap.setDensity(android.util.DisplayMetrics.DENSITY_DEFAULT);
        Canvas canvas=new Canvas(bitmap);Paint text=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
        int fill=point.color==0?MapTilerMapView.colorFor(point.type):point.color,ink=MapTilerMapView.markerInk(fill);
        text.setColor(ink);text.setTypeface(Typeface.DEFAULT);text.setTextSize(56);text.setTextAlign(Paint.Align.CENTER);
        boolean drawable=!point.symbol.isEmpty()&&text.hasGlyph(point.symbol);
        if(drawable){float width=text.measureText(point.symbol);if(width>64)text.setTextSize(text.getTextSize()*64/width);canvas.drawText(point.symbol,PIXELS/2f,(PIXELS-text.ascent()-text.descent())/2f,text);}
        else drawType(canvas,point.type,ink);
        // Color emoji fonts ignore Paint's text color; retain their alpha shape and enforce contrast.
        int[] pixels=new int[PIXELS*PIXELS];bitmap.getPixels(pixels,0,PIXELS,0,0,PIXELS,PIXELS);boolean visible=false;
        for(int i=0;i<pixels.length;i++){int alpha=Color.alpha(pixels[i]);if(alpha>0)visible=true;pixels[i]=(alpha<<24)|(ink&0xffffff);}
        bitmap.setPixels(pixels,0,PIXELS,0,0,PIXELS,PIXELS);
        if(!visible)drawType(canvas,point.type,ink);
        cache.put(id,bitmap);return bitmap;
    }
    private void drawType(Canvas canvas,String type,int ink){
        int[] ids={R.drawable.ic_fish,R.drawable.ic_map_pin,R.drawable.ic_tent,R.drawable.ic_alert_triangle,R.drawable.ic_fish_hook,R.drawable.ic_map_pin};
        int index=Math.max(0,Arrays.asList(MapFilterPolicy.TYPES).indexOf(MapFilterPolicy.category(type)));
        Drawable icon=context.getDrawable(ids[index]).mutate();icon.setTint(ink);icon.setBounds(4,4,PIXELS-4,PIXELS-4);icon.draw(canvas);
    }
    synchronized void clear(){cache.clear();}
}
