package com.fiskentra.app.ui;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Heading dial with a destination needle; hidden by the host outside navigation. */
final class CompassDial extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path needle = new Path();
    private double heading = Double.NaN, target = Double.NaN;
    CompassDial(Context context) { super(context); setClickable(true); }
    void setHeading(double value) { heading = value;
        setContentDescription(Double.isFinite(heading) ? "Compass " + Math.round(heading) + " degrees. Tap for north-up map." : "Compass waiting for reliable heading. Move phone in a figure eight to calibrate. Tap for north-up map.");
        invalidate(); }
    void setTarget(double value) { target = value; invalidate(); }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float size = Math.min(getWidth(), getHeight()), radius = size / 2 - 2;
        c.save(); c.translate(getWidth()/2f, getHeight()/2f);
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xff001e2e); c.drawCircle(0,0,radius,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(size/80); paint.setColor(0xff53778f); c.drawCircle(0,0,radius,paint);
        c.save(); c.rotate(Double.isFinite(heading) ? (float)-heading : 0);
        paint.setColor(0xffb7c9e6);
        for (int i=0;i<36;i++) { c.drawLine(0,-radius+size*.05f,0,-radius+size*(i%9==0?.13f:.08f),paint); c.rotate(10); }
        paint.setStyle(Paint.Style.FILL); paint.setTextSize(size*.115f); paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawText("N",0,-radius*.50f,paint); c.drawText("S",0,radius*.70f,paint); c.drawText("W",-radius*.65f,size*.04f,paint); c.drawText("E",radius*.65f,size*.04f,paint);
        paint.setColor(0xffff574e); c.drawCircle(0,-radius*.84f,size*.026f,paint); c.restore();
        if (Double.isFinite(heading) && Double.isFinite(target)) {
            c.save(); c.rotate((float)(target-heading)); paint.setColor(0xffffbc3e);
            needle.reset(); needle.moveTo(0,-radius*.40f); needle.lineTo(-radius*.17f,radius*.19f); needle.lineTo(0,radius*.10f); needle.lineTo(radius*.17f,radius*.19f); needle.close(); c.drawPath(needle,paint); c.restore();
        } else { paint.setColor(0xffb7c9e6); c.drawText("—",0,size*.04f,paint); }
        c.restore();
    }
}
