package com.fiskentra.app.ui;

import android.app.DatePickerDialog;
import android.content.Context;
import android.view.View;
import android.widget.*;
import com.fiskentra.app.R;
import com.fiskentra.app.model.*;
import java.time.*;
import java.util.*;

final class MapFilterPanel extends LinearLayout implements MapSheet.FooterContent {
    interface Actions { void apply(MapFilterPolicy filter);void list(); }
    private MapFilterPolicy initial;
    private final List<SavedPoint> points;
    private final String currentTrip;
    private final List<FishingDay> trips;
    private final Spinner scope,trip;
    private final CheckBox[] types=new CheckBox[MapFilterPolicy.TYPES.length];
    private final CheckBox favorites;
    private final Button from,to,apply;
    private final TextView message,zone;
    private LocalDate first,last;
    private boolean ready;
    MapFilterPanel(Context c,MapFilterPolicy initial,List<SavedPoint> points,String currentTrip,List<FishingDay> trips,Actions actions){
        super(c);setOrientation(VERTICAL);this.initial=initial;this.points=points;this.currentTrip=currentTrip;this.trips=trips;
        LinearLayout heading=new LinearLayout(c);heading.setGravity(android.view.Gravity.CENTER_VERTICAL);heading.addView(MapUi.text(c,c.getString(R.string.map_filter_title),22,true),new LayoutParams(0,-2,1));Button reset=MapUi.button(c,R.string.map_filter_reset,()->actions.apply(MapFilterPolicy.all()),false);reset.setBackgroundColor(android.graphics.Color.TRANSPARENT);heading.addView(reset,new LayoutParams(-2,-2));addView(heading);
        scope=new Spinner(c);String[] labels={c.getString(R.string.map_filter_all),c.getString(R.string.map_filter_current),c.getString(R.string.map_filter_trip),c.getString(R.string.map_filter_dates)};adapter(scope,labels);addView(scope);scope.setSelection(Arrays.asList("all","current","trip","dates").indexOf(initial.scope));
        trip=new Spinner(c);String[] names=new String[trips.size()];for(int i=0;i<trips.size();i++){FishingDay d=trips.get(i);names[i]=java.text.DateFormat.getDateTimeInstance().format(new Date(d.startedAt));}adapter(trip,names);addView(trip);for(int i=0;i<trips.size();i++)if(Long.toString(trips.get(i).id).equals(initial.tripId))trip.setSelection(i);
        first="dates".equals(initial.scope)?Instant.ofEpochMilli(initial.fromUtc).atZone(ZoneId.systemDefault()).toLocalDate():LocalDate.now();last="dates".equals(initial.scope)?Instant.ofEpochMilli(initial.toUtc-1).atZone(ZoneId.systemDefault()).toLocalDate():first;
        from=MapUi.button(c,R.string.map_filter_dates,()->date(true),false);to=MapUi.button(c,R.string.map_filter_dates,()->date(false),false);addView(from);addView(to);zone=MapUi.text(c,c.getString(R.string.map_filter_zone,ZoneId.systemDefault().getId()),12,false);addView(zone);
        addView(MapUi.text(c,c.getString(R.string.map_filter_types),16,true));int[] ids={R.string.map_type_catch,R.string.map_type_waypoint,R.string.map_type_camp,R.string.map_type_hazard,R.string.map_type_tackle,R.string.map_type_other};
        LinearLayout pair=null;boolean columns=c.getResources().getConfiguration().fontScale<=1.3f;
        for(int i=0;i<types.length;i++){CheckBox check=new CheckBox(c);check.setText(ids[i]);check.setTextColor(0xffeff4ff);check.setMinHeight(MapUi.dp(c,48));check.setChecked(initial.types.contains(MapFilterPolicy.TYPES[i]));check.setOnCheckedChangeListener((b,v)->refresh());types[i]=check;if(columns){if(i%2==0){pair=new LinearLayout(c);addView(pair);}pair.addView(check,new LayoutParams(0,-2,1));}else addView(check);}
        favorites=new CheckBox(c);favorites.setText(R.string.map_filter_favorites);favorites.setTextColor(0xffeff4ff);favorites.setMinHeight(MapUi.dp(c,48));favorites.setChecked(initial.favorite);favorites.setOnCheckedChangeListener((b,v)->refresh());addView(favorites);
        addView(MapUi.button(c,R.string.map_filter_catches,()->{for(int i=0;i<types.length;i++)types[i].setChecked(i==0);},false));message=MapUi.text(c,"",13,false);addView(message);
        apply=MapUi.button(c,R.string.map_show,()->{MapFilterPolicy f=value();if(f!=null)actions.apply(f);},true);apply.setMinHeight(MapUi.dp(c,56));
        addView(MapUi.button(c,R.string.map_visible_points,actions::list,false));
        AdapterView.OnItemSelectedListener listener=new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int index,long id){refresh();}public void onNothingSelected(AdapterView<?> p){}};scope.setOnItemSelectedListener(listener);trip.setOnItemSelectedListener(listener);ready=true;refresh();
    }
    @Override public View footer(){return apply;}
    private void adapter(Spinner view,String[] labels){ArrayAdapter<String> a=new ArrayAdapter<>(getContext(),android.R.layout.simple_spinner_item,labels);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);view.setAdapter(a);view.setMinimumHeight(MapUi.dp(getContext(),48));}
    private void date(boolean start){LocalDate d=start?first:last;new DatePickerDialog(getContext(),(v,y,m,day)->{if(start)first=LocalDate.of(y,m+1,day);else last=LocalDate.of(y,m+1,day);refresh();},d.getYear(),d.getMonthValue()-1,d.getDayOfMonth()).show();}
    private MapFilterPolicy value(){if(last.isBefore(first)&&scope.getSelectedItemPosition()==3)return null;long[] range=MapFilterPolicy.dates(first,last.isBefore(first)?first:last,ZoneId.systemDefault());ArrayList<String> chosen=new ArrayList<>();for(int i=0;i<types.length;i++)if(types[i].isChecked())chosen.add(MapFilterPolicy.TYPES[i]);int selected=trip.getSelectedItemPosition();return new MapFilterPolicy(new String[]{"all","current","trip","dates"}[Math.max(0,scope.getSelectedItemPosition())],selected>=0&&selected<trips.size()?Long.toString(trips.get(selected).id):"",range[0],range[1],chosen,favorites.isChecked());}
    private void refresh(){if(!ready)return;boolean dates=scope.getSelectedItemPosition()==3;zone.setVisibility(dates?VISIBLE:GONE);from.setVisibility(dates?VISIBLE:GONE);to.setVisibility(dates?VISIBLE:GONE);trip.setVisibility(scope.getSelectedItemPosition()==2?VISIBLE:GONE);from.setText(getContext().getString(R.string.map_filter_from,first.toString()));to.setText(getContext().getString(R.string.map_filter_to,last.toString()));MapFilterPolicy f=value();apply.setEnabled(f!=null);if(f==null){message.setText(R.string.map_filter_bad_dates);return;}List<SavedPoint> filtered=f.apply(points,currentTrip);int missing=0;for(SavedPoint p:filtered)if(!Double.isFinite(p.latitude)||!Double.isFinite(p.longitude))missing++;apply.setText(getContext().getString(R.string.map_filter_count,filtered.size()-missing,missing));message.setText("current".equals(f.scope)&&("0".equals(currentTrip)||currentTrip.isEmpty())?R.string.map_filter_no_trip:filtered.isEmpty()?R.string.map_filter_empty:R.string.map_filter_types);}
}
