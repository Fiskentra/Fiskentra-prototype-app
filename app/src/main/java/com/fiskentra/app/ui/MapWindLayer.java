package com.fiskentra.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.app.AlertDialog;

import com.fiskentra.app.R;
import com.fiskentra.app.model.WeatherSnapshot;
import com.fiskentra.app.model.WindLayerPolicy;
import com.fiskentra.app.weather.WeatherClient;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** A weather sample at one location. This is deliberately not an interpolated wind field. */
final class MapWindLayer implements AutoCloseable {
    private static final float SAMPLE_RADIUS_METERS = 5000f;

    private final Context context;
    private final WindHud indicator;
    private final WeatherClient weather;
    private final ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private final Runnable timer = new Runnable() {
        @Override public void run() {
            if (closed || !visible) return;
            tick();
            if (!closed && visible) main.postDelayed(this, WindLayerPolicy.RETRY_MS);
        }
    };
    private boolean closed, visible, imperial, fetching, lastSucceeded, restoredSample;
    private double latitude = Double.NaN, longitude = Double.NaN;
    private double requestedLatitude = Double.NaN, requestedLongitude = Double.NaN;
    private double sampleLatitude = Double.NaN, sampleLongitude = Double.NaN;
    private long lastAttempt;
    private WeatherSnapshot sample;
    private String failure = "";
    private volatile String status = "";

    MapWindLayer(Context context, Runnable onStatusChanged) {
        this.context = context;
        this.indicator = new WindHud(context);
        indicator.setVisibility(View.GONE);
        indicator.setOnClickListener(view -> new AlertDialog.Builder(context)
                .setTitle(R.string.wind_hud_title).setMessage(context.getString(R.string.wind_full_details,status))
                .setPositiveButton(android.R.string.ok, null).show());
        this.weather = new WeatherClient(context);
        this.connectivity = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        this.changed = onStatusChanged;
    }

    View indicator() { return indicator; }

    void update(boolean enabled, double lat, double lon, boolean useImperial) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> update(enabled, lat, lon, useImperial));
            return;
        }
        if (closed) return;
        boolean wasVisible = visible;
        visible = enabled; latitude = lat; longitude = lon; imperial = useImperial;
        indicator.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            main.removeCallbacks(timer);
            hide(); setStatus("");
            return;
        }
        tick();
        if (!wasVisible) {
            main.removeCallbacks(timer);
            main.postDelayed(timer, WindLayerPolicy.RETRY_MS);
        }
    }

    String status() { return status; }

    /** Retry a failed source without queueing duplicate requests or bypassing its recent cache. */
    void retry() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::retry); return; }
        if (closed || !visible || fetching) return;
        lastAttempt = 0L;
        tick();
    }

    private void tick() {
        if (closed || !visible) return;
        if (!coordinate(latitude, longitude)) {
            hide(); setStatus(context.getString(R.string.wind_choose_location)); return;
        }
        long now = System.currentTimeMillis();
        boolean moved = !near(latitude, longitude, requestedLatitude, requestedLongitude);
        boolean due = WindLayerPolicy.due(now, lastAttempt, lastSucceeded && valid(sample, now));
        if (!fetching && (moved || due)) request();
        showSample();
    }

    private void request() {
        fetching = true;
        requestedLatitude = latitude; requestedLongitude = longitude;
        lastAttempt = System.currentTimeMillis();
        final double lat = requestedLatitude, lon = requestedLongitude;
        WeatherClient.CachedWind saved = weather.cachedWind(lat, lon);
        if (saved != null && (!valid(sample, lastAttempt)
                || !near(lat, lon, sampleLatitude, sampleLongitude)
                || saved.weather.observedAt > sample.observedAt)) {
            sample = saved.weather; sampleLatitude = saved.latitude; sampleLongitude = saved.longitude;
            restoredSample = true;
        }
        weather.fetch(lat, lon, (value, message) -> main.post(() -> {
            if (closed) return;
            fetching = false;
            lastSucceeded = valid(value, System.currentTimeMillis());
            if (lastSucceeded) {
                restoredSample = restoredSample && sample != null && sample.observedAt == value.observedAt;
                sample = value; sampleLatitude = lat; sampleLongitude = lon; failure = "";
            } else {
                failure = value == null ? message : context.getString(R.string.wind_too_old);
                if (failure == null || failure.isEmpty()) failure = context.getString(R.string.wind_service_unavailable);
            }
            if (!visible) return;
            // Coalesce rapid point changes: never queue one HTTP request for every GPS fix.
            if (!near(latitude, longitude, lat, lon)) tick();
            else showSample();
        }));
    }

    private void showSample() {
        if (closed || !visible) return;
        long now = System.currentTimeMillis();
        if (!valid(sample, now) || !near(latitude, longitude, sampleLatitude, sampleLongitude)) {
            hide();
            setStatus(fetching ? context.getString(R.string.wind_loading)
                    : context.getString(R.string.wind_failure, failure.isEmpty() ? context.getString(R.string.wind_unavailable) : failure));
            return;
        }
        boolean offline = !online();
        boolean stale = now - sample.observedAt > WindLayerPolicy.STALE_MS;
        String prefix = context.getString(offline ? R.string.wind_saved_offline : stale ? R.string.wind_saved_older
                : restoredSample ? R.string.wind_cached : R.string.wind_hud_title);
        if (!failure.isEmpty() && !fetching && !offline) prefix = context.getString(R.string.wind_saved_failed);
        String unit = imperial ? "mil/h" : "km/h";
        double speed = sample.windSpeedKmh / (imperial ? 1.609344d : 1d);
        String speedText = String.format(Locale.US, "%.1f %s", speed, unit);
        String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(sample.observedAt));
        String direction = sample.windDirection();
        boolean calm = sample.windSpeedKmh < .1d;
        String text = context.getString(R.string.wind_status, prefix,
                calm ? context.getString(R.string.wind_calm) : context.getString(R.string.wind_speed_from, speedText, direction), time);
        if (fetching) text = context.getString(R.string.wind_updating_status, text);
        setStatus(text);
        float flowBearing = WindLayerPolicy.flowBearing(sample.windDirectionDegrees);
        String state = context.getString(offline ? R.string.wind_offline : stale ? R.string.wind_older
                : !failure.isEmpty() || restoredSample ? R.string.wind_saved : fetching ? R.string.wind_updating : R.string.wind_live);
        indicator.sample(speedText, direction, time, Math.max(0, (now - sample.observedAt) / 60000L),
                flowBearing, calm, offline || stale || !failure.isEmpty() || restoredSample, state, text);
    }

    private void hide() {
        indicator.unavailable();
        indicator.state.setText(fetching?R.string.wind_updating:R.string.wind_unavailable);
    }
    private void setStatus(String value) {
        if (status.equals(value)) return;
        status = value;
        indicator.setContentDescription(value);
        if (!closed && changed != null) changed.run();
    }
    private static boolean valid(WeatherSnapshot value, long now) {
        return value != null && WindLayerPolicy.usable(value.observedAt, value.windSpeedKmh,
                value.windDirectionDegrees, now);
    }
    private static boolean coordinate(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }
    private static boolean near(double lat, double lon, double otherLat, double otherLon) {
        if (!coordinate(lat, lon) || !coordinate(otherLat, otherLon)) return false;
        float[] distance = new float[1];
        Location.distanceBetween(lat, lon, otherLat, otherLon, distance);
        return distance[0] < SAMPLE_RADIUS_METERS;
    }
    private boolean online() {
        if (connectivity == null) return false;
        Network network = connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : connectivity.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    @Override public void close() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::close); return; }
        if (closed) return;
        closed = true; visible = false;
        main.removeCallbacksAndMessages(null); weather.close();
        indicator.setVisibility(View.GONE); sample = null;
    }

    /** Fixed north-up instrument; weather coordinates select the sample, never its screen position. */
    private static final class WindHud extends LinearLayout {
        private final TextView speed, direction, age, source, state;
        private final FlowArrow arrow;
        WindHud(Context context) {
            super(context); setOrientation(VERTICAL); setGravity(Gravity.CENTER_HORIZONTAL);
            int padding=dp(5); setPadding(padding,padding,padding,padding);
            GradientDrawable background=new GradientDrawable();background.setColor(0xf2001e2e);
            background.setCornerRadius(dp(12));background.setStroke(dp(1),0xff53778f);setBackground(background);
            TextView title=label(10);title.setText(R.string.wind_hud_title);addView(title);
            arrow=new FlowArrow(context);addView(arrow,new LayoutParams(dp(32),dp(32)));
            speed=label(11);speed.setTypeface(Typeface.DEFAULT_BOLD);addView(speed);
            direction=label(10);addView(direction);source=label(10);addView(source);age=label(10);addView(age);
            state=label(10);addView(state);setClickable(true);setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);unavailable();
        }
        private TextView label(int size){TextView text=new TextView(getContext());text.setTextSize(size);text.setTextColor(0xffeff4ff);text.setGravity(Gravity.CENTER);text.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);return text;}
        private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
        void unavailable(){arrow.bearing=Float.NaN;arrow.invalidate();speed.setText(R.string.wind_no_speed);direction.setText(R.string.wind_no_direction);source.setText(R.string.wind_source);age.setText(R.string.wind_no_age);state.setText(R.string.wind_waiting);}
        void sample(String value,String from,String time,long minutes,float bearing,boolean calm,boolean saved,String status,String description){
            arrow.bearing=calm?Float.NaN:bearing;arrow.invalidate();speed.setText(value);
            direction.setText(calm?getContext().getString(R.string.wind_calm):getContext().getString(R.string.wind_from,from));
            source.setText(time);age.setText(getResources().getQuantityString(R.plurals.wind_age,(int)minutes,minutes));state.setText(status);
            state.setTextColor(saved?0xffffbd63:0xff35dfd1);setContentDescription(description);
        }
    }

    private static final class FlowArrow extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path=new Path();
        private float bearing=Float.NaN;
        FlowArrow(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float scale=Math.min(getWidth(),getHeight())/32f;
            canvas.save();canvas.translate(getWidth()/2f,getHeight()/2f);canvas.scale(scale,scale);
            paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(8);paint.setColor(0xffb7c9e6);canvas.drawText("N",0,-8,paint);
            if(Float.isFinite(bearing)){canvas.rotate(bearing);path.rewind();path.moveTo(0,-7);path.lineTo(6,3);path.lineTo(2,2);path.lineTo(2,10);path.lineTo(-2,10);path.lineTo(-2,2);path.lineTo(-6,3);path.close();paint.setColor(0xff35dfd1);canvas.drawPath(path,paint);}
            else {paint.setTextSize(16);canvas.drawText("—",0,9,paint);}canvas.restore();
        }
    }
}
