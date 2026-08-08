package com.fiskentra.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.location.Location;
import android.view.View;

import com.fiskentra.app.model.SavedPoint;

import java.util.Collections;
import java.util.List;

public final class MapCanvasView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<SavedPoint> points = Collections.emptyList();
    private List<double[]> track = Collections.emptyList();
    private Location location;

    public MapCanvasView(Context context) { super(context); }

    public void setData(Location location, List<SavedPoint> points, List<double[]> track) {
        this.location = location;
        this.points = points == null ? Collections.emptyList() : points;
        this.track = track == null ? Collections.emptyList() : track;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        canvas.drawColor(Color.rgb(18, 34, 27));
        paint.setColor(Color.rgb(32, 57, 46)); paint.setStrokeWidth(2f);
        for (int i = 0; i < 8; i++) {
            float y = h * i / 7f; canvas.drawLine(0, y, w, y, paint);
        }
        for (int i = 0; i < 6; i++) {
            float x = w * i / 5f; canvas.drawLine(x, 0, x, h, paint);
        }

        double centerLat = location == null ? (points.isEmpty() ? 56.9496 : points.get(0).latitude) : location.getLatitude();
        double centerLon = location == null ? (points.isEmpty() ? 24.1052 : points.get(0).longitude) : location.getLongitude();
        double span = 0.035;

        paint.setColor(Color.rgb(99, 190, 116));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6f);
        float previousX = 0f, previousY = 0f;
        boolean hasPrevious = false;
        for (double[] p : track) {
            float x = (float) (w / 2 + (p[1] - centerLon) / span * w);
            float y = (float) (h / 2 - (p[0] - centerLat) / span * h);
            if (hasPrevious) canvas.drawLine(previousX, previousY, x, y, paint);
            previousX = x; previousY = y; hasPrevious = true;
        }
        paint.setStyle(Paint.Style.FILL);

        paint.setColor(Color.rgb(121, 227, 143));
        for (SavedPoint p : points) {
            float x = (float) (w / 2 + (p.longitude - centerLon) / span * w);
            float y = (float) (h / 2 - (p.latitude - centerLat) / span * h);
            if (x >= 0 && x <= w && y >= 0 && y <= h) canvas.drawCircle(x, y, 11f, paint);
        }
        if (location != null) {
            paint.setColor(Color.WHITE); canvas.drawCircle(w / 2f, h / 2f, 13f, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5f); paint.setColor(Color.rgb(121, 227, 143));
            canvas.drawCircle(w / 2f, h / 2f, 18f, paint); paint.setStyle(Paint.Style.FILL);
        }
    }
}
