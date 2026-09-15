package com.fiskentra.app.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import com.fiskentra.app.R;

/** A single scrollable, bounded overlay host; map remains interactive above it. */
final class MapSheet extends LinearLayout {
    interface Expandable { void setExpanded(boolean expanded); }
    interface FooterContent { View footer(); default void prepareWidth(int widthPx) {} }
    private View body;
    private View footer;
    private final LinearLayout head;
    private final ScrollView scroll;
    private final Button handle;
    private boolean expanded;
    private float downY;
    MapSheet(Context c,Runnable close) {
        super(c);setOrientation(VERTICAL);setPadding(dp(14),0,dp(14),dp(12));
        GradientDrawable bg=new GradientDrawable();bg.setColor(0xff001e2e);bg.setCornerRadius(dp(18));bg.setStroke(dp(1),0xff53778f);setBackground(bg);setElevation(dp(6));
        head=new LinearLayout(c);head.setGravity(Gravity.CENTER_VERTICAL);
        handle=MapUi.button(c,R.string.map_expand,()->setExpanded(!expanded),false);handle.setText("━");handle.setTextSize(24);handle.setTextColor(0xff94bce6);handle.setBackgroundColor(android.graphics.Color.TRANSPARENT);handle.setContentDescription(c.getString(R.string.map_expand));head.addView(handle,new LayoutParams(0,dp(48),1));
        ImageButton done=new ImageButton(c);done.setImageResource(R.drawable.ic_nav_close);done.setContentDescription(c.getString(R.string.map_close));done.setBackgroundColor(android.graphics.Color.TRANSPARENT);done.setPadding(dp(12),dp(12),dp(12),dp(12));done.setOnClickListener(v->close.run());head.addView(done,new LayoutParams(dp(48),dp(48)));addView(head);
        handle.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN)downY=e.getRawY();if(e.getAction()==MotionEvent.ACTION_UP&&Math.abs(e.getRawY()-downY)>dp(24)){setExpanded(e.getRawY()<downY);return true;}return false;});
        scroll=new ScrollView(c);scroll.setFillViewport(false);addView(scroll,new LayoutParams(-1,-2));
    }
    void content(View body){this.body=body;if(footer!=null){removeView(footer);footer=null;}scroll.removeAllViews();scroll.addView(body);if(body instanceof FooterContent){footer=((FooterContent)body).footer();addView(footer,new LayoutParams(-1,-2));}if(body instanceof Expandable)((Expandable)body).setExpanded(expanded);scroll.scrollTo(0,0);}
    void setExpanded(boolean value){expanded=value;handle.setContentDescription(getContext().getString(expanded?R.string.map_collapse:R.string.map_expand));if(body instanceof Expandable)((Expandable)body).setExpanded(expanded);requestLayout();}
    boolean collapse(){if(!expanded)return false;setExpanded(false);return true;}
    @Override protected void onMeasure(int width,int height){
        int available=getParent() instanceof View?((View)getParent()).getHeight():MeasureSpec.getSize(height);
        if(available<=0)available=getResources().getDisplayMetrics().heightPixels;
        int cap=(int)(available*(expanded?.78:.56));
        if(MeasureSpec.getMode(height)!=MeasureSpec.UNSPECIFIED)cap=Math.min(cap,MeasureSpec.getSize(height));
        int innerWidth=Math.max(0,MeasureSpec.getSize(width)-getPaddingLeft()-getPaddingRight());
        if(body instanceof FooterContent)((FooterContent)body).prepareWidth(innerWidth);
        int childWidth=MeasureSpec.makeMeasureSpec(innerWidth,MeasureSpec.EXACTLY);
        head.measure(childWidth,MeasureSpec.makeMeasureSpec(dp(48),MeasureSpec.EXACTLY));
        int reserved=getPaddingTop()+getPaddingBottom()+head.getMeasuredHeight();
        if(footer!=null){footer.measure(childWidth,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));reserved+=footer.getMeasuredHeight();}
        // Measure actions first: a long point body must scroll rather than push actions below the sheet.
        scroll.measure(childWidth,MeasureSpec.makeMeasureSpec(Math.max(0,cap-reserved),MeasureSpec.AT_MOST));
        scroll.getLayoutParams().height=scroll.getMeasuredHeight();
        super.onMeasure(width,MeasureSpec.makeMeasureSpec(cap,MeasureSpec.AT_MOST));
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
