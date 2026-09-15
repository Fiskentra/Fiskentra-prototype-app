package com.fiskentra.app.ui;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.*;
import android.view.*;
import java.util.Locale;
final class MapUi {
    static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static TextView text(Context c,CharSequence text,int size,boolean bold){TextView t=new TextView(c);t.setText(text);t.setTextSize(size);t.setTextColor(0xffeff4ff);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);t.setPadding(0,dp(c,4),0,dp(c,4));return t;}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static Button button(Context c,int res,Runnable action,boolean primary){Button b=new Button(c);b.setText(res);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(0xffeff4ff);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(c,48));b.setMinimumHeight(dp(c,48));b.setPadding(dp(c,10),dp(c,8),dp(c,10),dp(c,8));GradientDrawable bg=new GradientDrawable();bg.setColor(primary?0xff0059e8:0xff001e2e);bg.setCornerRadius(dp(c,12));bg.setStroke(dp(c,1),0xff53778f);b.setBackground(bg);b.setOnClickListener(v->action.run());return b;}
    static String distance(double m,boolean imperial){if(imperial)return m>=1609.344?String.format(Locale.getDefault(),"%.1f mi",m/1609.344):Math.round(m*3.28084)+" ft";return m>=1000?String.format(Locale.getDefault(),"%.1f km",m/1000):Math.round(m)+" m";}
}
