package com.fiskentra.app.offline;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.fiskentra.app.R;
import com.fiskentra.app.model.OfflineAreaPolicy;
import com.fiskentra.app.ui.MapTilerMapView;
import java.util.Locale;

/** Embeddable bottom-sheet content; the existing map remains the area picker and geographic canvas. */
public final class OfflineAreaPanel extends ScrollView {
    public interface Host {
        void chooseCenterOnMap();
        void useMyLocation();
        void showArea(OfflineMapController.Area area);
        /** null clears a draft; READY outlines can remain visible independently. */
        void previewDraft(OfflineMapController.Draft draft);
        void close();
    }
    private static final int NAVY=Color.rgb(0,30,46),BLUE=Color.rgb(0,89,232),TEXT=Color.rgb(239,244,255),MUTED=Color.rgb(183,201,230),LINE=Color.rgb(83,119,143);
    private final OfflineMapController controller;
    private final Host host;
    private final LinearLayout body,areas,draftBody;
    private final TextView title,summary,status,coverage,route,center,estimate,validation,style,replacementNote;
    private final Button download,changeArea,manage;
    private final EditText name;
    private final Runnable refreshTask=this::refresh;
    private final OfflineMapController.Listener listener=()->{if(isAttachedToWindow()){removeCallbacks(refreshTask);post(refreshTask);}};
    private boolean editing,managing,imperial,hasCenter,cachedRoute;
    private double latitude,longitude,radius=5,mapLatitude=Double.NaN,mapLongitude=Double.NaN,mapZoom;
    private String styleId=MapTilerMapView.STYLE_HYBRID,mapStyle=MapTilerMapView.STYLE_HYBRID;
    private OfflineMapController.Draft draft;
    private String renderedAreas="";

    public OfflineAreaPanel(Context context,OfflineMapController controller,Host host){
        super(context);this.controller=controller;this.host=host;setFillViewport(false);setBackgroundColor(NAVY);
        // The map's shared sheet owns its handle/close controls; this is compact content only.
        body=column();body.setPadding(dp(4),0,dp(4),dp(4));addView(body,new LayoutParams(-1,-2));
        title=text(getContext().getString(R.string.offline_title),20,TEXT,true);body.addView(title);
        summary=text("",12,MUTED,false);body.addView(summary,compactGap());
        status=text("",13,MUTED,false);body.addView(status,compactGap());
        LinearLayout topActions=new LinearLayout(context);topActions.setOrientation(LinearLayout.HORIZONTAL);
        changeArea=button(R.string.offline_change_area,()->{editing=!editing;managing=false;if(!editing)host.previewDraft(null);updateDraft();});
        changeArea.setBackground(background(BLUE,BLUE,12));
        manage=button("Manage",()->{managing=!managing;editing=false;host.previewDraft(null);refresh();});
        LinearLayout.LayoutParams changeParams=new LinearLayout.LayoutParams(0,-2,1);changeParams.rightMargin=dp(6);
        topActions.addView(changeArea,changeParams);topActions.addView(manage,new LinearLayout.LayoutParams(0,-2,1));body.addView(topActions,gap());
        coverage=text("",12,TEXT,false);body.addView(coverage,compactGap());
        route=text("",12,TEXT,false);body.addView(route,compactGap());
        body.addView(text(context.getString(R.string.offline_new_routes_online),11,MUTED,false),compactGap());
        areas=column();body.addView(areas,gap());
        draftBody=column();body.addView(draftBody,gap());
        name=new EditText(context);name.setTextColor(TEXT);name.setHintTextColor(MUTED);name.setHint(R.string.offline_area_name);name.setSingleLine(true);name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});name.setMinHeight(dp(48));draftBody.addView(name,gap());
        center=text("",13,MUTED,false);draftBody.addView(center,gap());
        draftBody.addView(button(R.string.offline_choose_on_map,host::chooseCenterOnMap),gap());
        draftBody.addView(button(R.string.offline_use_location,host::useMyLocation),gap());
        LinearLayout radii=new LinearLayout(context);radii.setOrientation(LinearLayout.HORIZONTAL);
        for(int km:new int[]{2,5,10}){Button choice=button(radiusText(km),()->{radius=km;updateDraft();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(2),0,dp(2),0);radii.addView(choice,p);choice.setTag(km);}
        draftBody.addView(radii,gap());
        style=text("",15,TEXT,true);style.setGravity(Gravity.CENTER_VERTICAL);style.setMinHeight(dp(48));style.setBackground(background(NAVY,LINE,12));style.setPadding(dp(12),dp(8),dp(12),dp(8));style.setOnClickListener(v->chooseStyle());draftBody.addView(style,gap());
        validation=text("",13,Color.rgb(255,183,62),false);draftBody.addView(validation,gap());
        draftBody.addView(text(context.getString(R.string.offline_bounds_explanation),12,MUTED,false),gap());
        estimate=text("",12,MUTED,false);draftBody.addView(estimate,gap());
        draftBody.addView(text(context.getString(R.string.offline_estimate_limit),12,MUTED,false),gap());
        replacementNote=text(context.getString(R.string.offline_replacement_keeps_old),12,MUTED,false);draftBody.addView(replacementNote,gap());
        download=button(R.string.offline_download,()->{if(draft!=null){editing=false;managing=false;host.previewDraft(null);controller.startDownload(name.getText().toString(),draft.latitude,draft.longitude,draft.radiusKm,draft.styleId);refresh();}});download.setBackground(background(BLUE,BLUE,14));draftBody.addView(download,gap());
        editing=controller.snapshot().areas.isEmpty();refresh();
    }

    public void setImperial(boolean imperial){this.imperial=imperial;updateDraft();renderedAreas="";refresh();}
    public void setMapContext(double latitude,double longitude,double zoom,String style,boolean cachedRoute){
        mapLatitude=latitude;mapLongitude=longitude;mapZoom=zoom;mapStyle=style;this.cachedRoute=cachedRoute;
        if(!hasCenter&&Double.isFinite(latitude)&&Double.isFinite(longitude)){this.latitude=latitude;this.longitude=longitude;styleId=MapTilerMapView.normalizeStyleId(style);hasCenter=true;updateDraft();}
        updateCoverage();
    }
    public void setDraftCenter(double latitude,double longitude){this.latitude=latitude;this.longitude=longitude;hasCenter=true;editing=true;updateDraft();refresh();}
    public OfflineMapController.Draft draft(){return draft;}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();controller.addListener(listener);refresh();}
    @Override protected void onDetachedFromWindow(){controller.removeListener(listener);removeCallbacks(refreshTask);host.previewDraft(null);super.onDetachedFromWindow();}

    private void refresh(){
        OfflineMapController.Snapshot snapshot=controller.snapshot();
        OfflineMapController.Area ready=snapshot.readyArea;
        boolean compactReady=ready!=null&&ready.state==OfflineMapController.State.READY&&snapshot.checked&&!snapshot.creating&&snapshot.areas.size()==1;
        title.setText(compactReady?ready.name:getContext().getString(R.string.offline_title));
        summary.setVisibility(compactReady?VISIBLE:GONE);
        if(compactReady)summary.setText(ready.styleName+" · "+radiusText(ready.radiusKm)+" · "+formatBytes(getContext(),ready.completedBytes));
        // A controller failure still takes precedence over a previously downloaded area's READY state.
        status.setText(snapshot.status);status.setTextColor(compactReady&&snapshot.status.equals(ready.status)?Color.rgb(53,223,209):MUTED);
        changeArea.setText(editing?R.string.offline_cancel:R.string.offline_change_area);
        manage.setVisibility(snapshot.areas.isEmpty()?GONE:VISIBLE);manage.setSelected(managing);manage.setBackground(background(managing?BLUE:NAVY,LINE,12));
        updateCoverage();
        StringBuilder key=new StringBuilder().append(snapshot.checked);for(OfflineMapController.Area area:snapshot.areas)key.append(area.id).append('/').append(area.state).append('/').append(area.progress).append('/').append(area.completedBytes).append('/').append(area.name).append('/').append(area.status).append(';');
        if(!renderedAreas.equals(key.toString())){renderedAreas=key.toString();areas.removeAllViews();for(OfflineMapController.Area area:snapshot.areas)addArea(area);}
        areas.setVisibility(!editing&&(!compactReady||managing)?VISIBLE:GONE);
        draftBody.setVisibility(editing?VISIBLE:GONE);
        download.setText(snapshot.readyArea==null?R.string.offline_download:R.string.offline_download_replacement);
        replacementNote.setVisibility(snapshot.readyArea==null?GONE:VISIBLE);
        download.setEnabled(draft!=null&&snapshot.checked&&!snapshot.creating&&snapshot.candidateArea==null&&snapshot.areas.size()<=1);
        if(!snapshot.checked&&!snapshot.creating&&snapshot.areas.isEmpty()&&areas.getChildCount()==0){areas.addView(button(R.string.offline_retry,controller::refresh),gap());areas.setVisibility(VISIBLE);}
        if(draft!=null)estimate.setText(getContext().getString(R.string.offline_estimate,formatBytes(getContext(),draft.approximateBytes),formatBytes(getContext(),snapshot.freeBytes)));
    }
    private void updateCoverage(){
        OfflineMapController.Area ready=controller.snapshot().readyArea;
        OfflineAreaPolicy.Coverage value=ready==null?OfflineAreaPolicy.Coverage.NO_AREA:ready.coverage(mapStyle,mapLatitude,mapLongitude,mapZoom);
        coverage.setText(getContext().getString(R.string.offline_coverage_title)+" · "+getContext().getString(coverageResource(value)));
        coverage.setTextColor(value==OfflineAreaPolicy.Coverage.AVAILABLE?TEXT:Color.rgb(255,183,62));
        route.setText(cachedRoute?R.string.offline_route_saved:R.string.offline_route_not_saved);
    }
    private void updateDraft(){
        draft=null;validation.setText("");
        if(hasCenter){try{draft=OfflineMapController.draft(latitude,longitude,radius,styleId);}catch(IllegalArgumentException invalid){validation.setText(OfflineMapController.invalidAreaResource(invalid));}}
        center.setText(hasCenter?getContext().getString(R.string.offline_center,latitude,longitude):getContext().getString(R.string.offline_choose_center_first));
        style.setText(getContext().getString(R.string.offline_style)+" · "+MapTilerMapView.styleName(styleId));
        for(int i=0;i<draftBody.getChildCount();i++){View child=draftBody.getChildAt(i);if(child instanceof LinearLayout){LinearLayout row=(LinearLayout)child;for(int j=0;j<row.getChildCount();j++){View view=row.getChildAt(j);if(view instanceof Button&&view.getTag() instanceof Integer){int km=(Integer)view.getTag();((Button)view).setText(radiusText(km));view.setSelected(km==(int)radius);view.setBackground(background(km==(int)radius?BLUE:NAVY,LINE,12));}}}}
        if(editing)host.previewDraft(draft);refresh();
    }
    private void addArea(OfflineMapController.Area area){
        LinearLayout card=column();card.setPadding(dp(12),dp(10),dp(12),dp(10));card.setBackground(background(NAVY,LINE,16));
        card.addView(text(area.name,20,TEXT,true));card.addView(text(area.status,14,area.complete?Color.rgb(53,223,209):MUTED,true),gap());
        card.addView(text(getContext().getString(R.string.offline_region_details,area.styleName,area.minZoom,area.maxZoom,radiusText(area.radiusKm)),12,MUTED,false),gap());
        ProgressBar progress=new ProgressBar(getContext(),null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setIndeterminate(area.indeterminate);progress.setProgress(Math.max(0,area.progress));card.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));
        String percent=area.indeterminate?getContext().getString(R.string.offline_progress_discovering):getContext().getString(R.string.offline_progress_percent,area.progress);
        card.addView(text(getContext().getString(R.string.offline_download_details,percent,formatBytes(getContext(),area.completedBytes)),12,MUTED,false),gap());
        card.addView(button(R.string.offline_show_area,()->host.showArea(area)),gap());
        if(area.state==OfflineMapController.State.DOWNLOADING)card.addView(button(R.string.offline_pause,()->controller.pause(area.id)),gap());
        else if(!area.complete||area.state==OfflineMapController.State.ERROR)card.addView(button(area.state==OfflineMapController.State.PAUSED?R.string.offline_resume:R.string.offline_retry,()->controller.retry(area.id)),gap());
        if(area.state!=OfflineMapController.State.DELETING){card.addView(button(R.string.offline_rename,()->rename(area)),gap());card.addView(button(R.string.offline_delete,()->remove(area)),gap());}
        if(area.replacementOf>=0)card.addView(text(getContext().getString(R.string.offline_replacement_keeps_old),12,MUTED,false),gap());
        areas.addView(card,gap());
    }
    private void chooseStyle(){
        String[] ids={MapTilerMapView.STYLE_OUTDOOR,MapTilerMapView.STYLE_HYBRID,MapTilerMapView.STYLE_TOPO,MapTilerMapView.STYLE_OCEAN};
        String[] labels={getContext().getString(R.string.offline_style_outdoor),getContext().getString(R.string.offline_style_satellite),getContext().getString(R.string.offline_style_topographic),getContext().getString(R.string.offline_style_ocean)};
        int selected=0;for(int i=0;i<ids.length;i++)if(ids[i].equals(styleId))selected=i;
        new AlertDialog.Builder(getContext()).setTitle(R.string.offline_style).setSingleChoiceItems(labels,selected,(dialog,index)->{styleId=ids[index];dialog.dismiss();updateDraft();}).setNegativeButton(R.string.offline_cancel,null).show();
    }
    private void rename(OfflineMapController.Area area){EditText input=new EditText(getContext());input.setSingleLine(true);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});input.setText(area.name);new AlertDialog.Builder(getContext()).setTitle(R.string.offline_rename).setView(input).setNegativeButton(R.string.offline_cancel,null).setPositiveButton(R.string.offline_save,(d,w)->controller.rename(area.id,input.getText().toString())).show();}
    private void remove(OfflineMapController.Area area){new AlertDialog.Builder(getContext()).setTitle(R.string.offline_delete_title).setMessage(getContext().getString(R.string.offline_delete_message,area.name,formatBytes(getContext(),area.completedBytes))).setNegativeButton(R.string.offline_cancel,null).setPositiveButton(R.string.offline_delete,(d,w)->controller.remove(area.id)).show();}
    public static int coverageResource(OfflineAreaPolicy.Coverage value){switch(value){case AVAILABLE:return R.string.offline_coverage_available;case INCOMPLETE:return R.string.offline_coverage_incomplete;case DIFFERENT_STYLE:return R.string.offline_coverage_style;case OUTSIDE_BOUNDS:return R.string.offline_coverage_bounds;case OUTSIDE_ZOOM:return R.string.offline_coverage_zoom;default:return R.string.offline_no_area;}}
    public static String formatBytes(Context context,long bytes){return bytes<1024?context.getString(R.string.offline_bytes,bytes):bytes<1024L*1024?context.getString(R.string.offline_kib,bytes/1024d):bytes<1024L*1024*1024?context.getString(R.string.offline_mib,bytes/(1024d*1024)):context.getString(R.string.offline_gib,bytes/(1024d*1024*1024));}
    private String radiusText(double km){return imperial?getContext().getString(R.string.offline_radius_miles,String.format(Locale.getDefault(),"%.1f",km*.621371)):getContext().getString(R.string.offline_radius_km,(int)km);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(getContext());view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int sp,int color,boolean bold){TextView view=new TextView(getContext());view.setText(value);view.setTextSize(sp);view.setTextColor(color);if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return view;}
    private Button button(int resource,Runnable action){return button(getContext().getString(resource),action);}
    private Button button(String value,Runnable action){Button button=new Button(getContext());button.setText(value);button.setAllCaps(false);button.setTextSize(14);button.setTextColor(TEXT);button.setMinHeight(dp(48));button.setMinimumHeight(dp(48));button.setPadding(dp(10),dp(10),dp(10),dp(10));button.setBackground(background(NAVY,LINE,12));button.setOnClickListener(v->action.run());return button;}
    private GradientDrawable background(int fill,int stroke,int radius){GradientDrawable drawable=new GradientDrawable();drawable.setColor(fill);drawable.setCornerRadius(dp(radius));if(stroke!=0)drawable.setStroke(dp(1),stroke);return drawable;}
    private LinearLayout.LayoutParams gap(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);return p;}
    private LinearLayout.LayoutParams compactGap(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(4);return p;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
