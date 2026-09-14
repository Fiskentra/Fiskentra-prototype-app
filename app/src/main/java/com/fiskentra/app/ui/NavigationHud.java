package com.fiskentra.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import com.fiskentra.app.R;
import com.fiskentra.app.model.RoadRoute;

/** Navigation-only controls, leaving the map and trip panels independent. */
final class NavigationHud {
    interface Actions { void profile(String profile); void steps(); void voice(); void options(); void stop(); void choose(); }
    final LinearLayout turnPanel, routePanel;
    private final TextView distance, maneuver, street, summary, detail;
    private final ImageView arrow, travel;
    private final Button walking, driving;
    private final ImageButton sound;
    private final View steps;
    private static final int NAVY=0xff001e2e, INK=0xffeff4ff, MUTED=0xffb7c9e6, BLUE=0xff00a7ff, BUTTON=0xff0059e8, LINE=0xff53778f;
    private final Context context;
    NavigationHud(Context context,Actions actions) {
        this.context=context;
        turnPanel=row(); turnPanel.setPadding(dp(8),dp(8),dp(2),dp(8)); turnPanel.setBackground(surface(10,NAVY,LINE));
        arrow=icon(R.drawable.ic_nav_straight,BLUE); turnPanel.addView(arrow,new LinearLayout.LayoutParams(dp(48),dp(56)));
        LinearLayout copy=column(); copy.setPadding(dp(5),0,0,0);
        distance=text(11,false,MUTED); maneuver=text(18,true,INK); street=text(12,false,MUTED);
        maneuver.setMaxLines(2); street.setMaxLines(2); street.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(distance); copy.addView(maneuver); copy.addView(street); copy.setOnClickListener(v->actions.options());
        turnPanel.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        ImageButton close=iconButton(R.drawable.ic_nav_close,"Stop navigation",actions::stop);
        LinearLayout.LayoutParams closeLp=new LinearLayout.LayoutParams(dp(36),dp(40)); closeLp.gravity=Gravity.TOP; turnPanel.addView(close,closeLp);
        turnPanel.setOnClickListener(v->actions.options());

        routePanel=column(); routePanel.setPadding(dp(10),dp(8),dp(10),dp(8)); routePanel.setBackground(surface(12,NAVY,LINE));
        LinearLayout modes=row();
        walking=modeButton("Walking",R.drawable.ic_nav_directions_walk,()->actions.profile("pedestrian"));
        driving=modeButton("Driving",R.drawable.ic_nav_directions_car,()->actions.profile("auto"));
        LinearLayout.LayoutParams choice=new LinearLayout.LayoutParams(0,dp(38),1); choice.rightMargin=dp(5); modes.addView(walking,choice);
        LinearLayout.LayoutParams other=new LinearLayout.LayoutParams(0,dp(38),1); other.rightMargin=dp(5); modes.addView(driving,other);
        modes.addView(separator(),new LinearLayout.LayoutParams(dp(1),dp(30)));
        Button list=modeButton("Steps",R.drawable.ic_list_check,actions::steps); list.setBackgroundColor(android.graphics.Color.TRANSPARENT); list.setContentDescription("Route steps");
        modes.addView(list,new LinearLayout.LayoutParams(dp(68),dp(40))); steps=list;
        modes.addView(separator(),new LinearLayout.LayoutParams(dp(1),dp(30)));
        sound=iconButton(R.drawable.ic_nav_volume_off,"Enable voice guidance",actions::voice); modes.addView(sound,new LinearLayout.LayoutParams(dp(36),dp(40)));
        routePanel.addView(modes);
        LinearLayout.LayoutParams rule=new LinearLayout.LayoutParams(-1,dp(1)); rule.setMargins(0,dp(7),0,dp(6)); routePanel.addView(separator(),rule);
        LinearLayout info=row(); travel=icon(R.drawable.ic_nav_directions_car,INK); info.addView(travel,new LinearLayout.LayoutParams(dp(34),dp(40)));
        LinearLayout values=column(); values.setPadding(dp(10),0,0,0); summary=text(20,true,INK); detail=text(12,false,MUTED); detail.setMaxLines(2);
        values.addView(summary); values.addView(detail); info.addView(values,new LinearLayout.LayoutParams(0,-2,1)); info.setMinimumHeight(dp(46));
        info.setContentDescription("Route summary and options"); info.setOnClickListener(v->{if(steps.isEnabled())actions.options();else actions.choose();}); routePanel.addView(info);
    }
    void profile(String profile,boolean hasDestination,boolean voice) {
        select(walking,"pedestrian".equals(profile)); select(driving,"auto".equals(profile));
        travel.setImageResource("auto".equals(profile)?R.drawable.ic_nav_directions_car:"pedestrian".equals(profile)?R.drawable.ic_nav_directions_walk:R.drawable.ic_navigation);
        steps.setEnabled(hasDestination); steps.setAlpha(hasDestination?1:.45f);
        sound.setImageResource(voice?R.drawable.ic_volume:R.drawable.ic_nav_volume_off); sound.setContentDescription(voice?"Mute voice guidance":"Enable voice guidance");
    }
    void status(String message) {
        String[] lines=message.split("\\n",2); distance.setVisibility(View.GONE); street.setVisibility(lines.length>1?View.VISIBLE:View.GONE);
        maneuver.setText(lines[0]); street.setText(lines.length>1?lines[1]:""); arrow.setImageResource(R.drawable.ic_navigation);
    }
    void maneuver(RoadRoute.Step step,String remaining,String profile) {
        distance.setVisibility(View.VISIBLE); distance.setText(remaining); maneuver.setText(step.maneuverTitle());
        street.setText(step.street.isEmpty()?("pedestrian".equals(profile)?"Unnamed path":"Unnamed road"):step.street); street.setVisibility(View.VISIBLE);
        arrow.setImageResource(maneuverIcon(step.type));
    }
    void summary(String main,String secondary) { summary.setText(main); detail.setText(secondary); }
    private int maneuverIcon(int type) {
        switch(type) {
            case 4: case 5: case 6:return R.drawable.ic_nav_flag;
            case 9: case 23: case 37:return R.drawable.ic_nav_turn_slight_right;
            case 10: case 11: case 18: case 20:return R.drawable.ic_nav_turn_right;
            case 12: case 13:return R.drawable.ic_nav_u_turn_left;
            case 14: case 15: case 19: case 21:return R.drawable.ic_nav_turn_left;
            case 16: case 24: case 38:return R.drawable.ic_nav_turn_slight_left;
            case 26: case 27:return R.drawable.ic_nav_roundabout_right;
            default:return R.drawable.ic_nav_straight;
        }
    }
    private void select(Button button,boolean selected) { button.setBackground(surface(20,selected?BUTTON:NAVY,BLUE)); button.setTextColor(selected?INK:BLUE); for(android.graphics.drawable.Drawable d:button.getCompoundDrawables())if(d!=null)d.setTint(selected?INK:BLUE); }
    private Button modeButton(String label,int resource,Runnable action) {
        Button b=new Button(context); b.setText(label); b.setTextSize(11); b.setAllCaps(false); b.setTextColor(BLUE); b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(6),0,dp(5),0);
        android.graphics.drawable.Drawable d=context.getDrawable(resource).mutate(); d.setBounds(0,0,dp(20),dp(20)); d.setTint(BLUE); b.setCompoundDrawables(d,null,null,null); b.setCompoundDrawablePadding(dp(3)); b.setOnClickListener(v->action.run()); return b;
    }
    private ImageButton iconButton(int resource,String label,Runnable action) { ImageButton b=new ImageButton(context); b.setImageResource(resource); b.setColorFilter(INK); b.setBackgroundColor(android.graphics.Color.TRANSPARENT); b.setPadding(dp(7),dp(7),dp(7),dp(7)); b.setContentDescription(label); b.setOnClickListener(v->action.run()); return b; }
    private ImageView icon(int resource,int color) { ImageView v=new ImageView(context); v.setImageResource(resource); v.setColorFilter(color); return v; }
    private TextView text(int size,boolean bold,int color) { TextView t=new TextView(context); t.setTextSize(size); t.setTextColor(color); t.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT); t.setIncludeFontPadding(false); return t; }
    private LinearLayout row() { LinearLayout v=new LinearLayout(context); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout column() { LinearLayout v=new LinearLayout(context); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private View separator() { View v=new View(context); v.setBackgroundColor(LINE); return v; }
    private GradientDrawable surface(int radius,int fill,int stroke) { GradientDrawable d=new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); d.setStroke(dp(1),stroke); return d; }
    private int dp(int value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
}
