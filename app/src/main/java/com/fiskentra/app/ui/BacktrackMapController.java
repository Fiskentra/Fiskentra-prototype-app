package com.fiskentra.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Toast;
import com.fiskentra.app.R;
import com.fiskentra.app.data.UserPreferences;
import com.fiskentra.app.location.FiskentraLocationManager;
import com.fiskentra.app.model.BacktrackProgress;
import com.fiskentra.app.model.BacktrackRoute;
import com.fiskentra.app.navigation.BacktrackStore;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns only local guidance, never recording or map camera state. Heavy preparation/projection stays off UI. */
public final class BacktrackMapController implements AutoCloseable {
    public interface Actions {
        void started(BacktrackRoute route);
        void changed(BacktrackRoute route, BacktrackProgress.Snapshot snapshot, String title, String detail);
        void stopped();
        void preview(BacktrackRoute.Point point);
        void overview(BacktrackRoute route);
        void speak(String message);
        boolean voiceEnabled();
        void toggleVoice();
    }
    public final LinearLayout panel;
    private final Activity activity;
    private final Actions actions;
    private final BacktrackStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TextView title, remaining, detail;
    private final Button voice, next, showNext;
    private volatile int generation;
    private volatile boolean closed, active;
    private BacktrackProgress engine; // accessed only on worker
    private volatile BacktrackProgress.Fix current;
    private BacktrackRoute displayedRoute;
    private BacktrackProgress.Snapshot displayed;
    private BacktrackProgress.Status announced;
    private long lastAnnouncement;
    private AlertDialog dialog;
    public BacktrackMapController(Activity activity, Actions actions) {
        this.activity = activity; this.actions = actions; store = new BacktrackStore(activity);
        panel = new LinearLayout(activity) {
            @Override protected void onMeasure(int width, int height) {
                int available = getResources().getDisplayMetrics().heightPixels;
                if (getParent() instanceof View && ((View)getParent()).getParent() instanceof View) {
                    int stage = ((View)((View)getParent()).getParent()).getHeight(); if (stage > 0) available = stage;
                }
                double fraction=getResources().getConfiguration().fontScale>1.3f?.50:.40;
                super.onMeasure(width, MeasureSpec.makeMeasureSpec((int)(available * fraction), MeasureSpec.AT_MOST));
            }
        }; panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(14), dp(10), dp(14), dp(10)); panel.setBackground(surface()); panel.setVisibility(View.GONE);
        LinearLayout heading = row(); title = text("", 18); title.setTypeface(Typeface.DEFAULT_BOLD); heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        voice = button(R.string.backtrack_voice_off, () -> { actions.toggleVoice(); renderVoice(); if (actions.voiceEnabled() && displayed != null) actions.speak(statusLabel(displayed.status)); }); heading.addView(voice); panel.addView(heading);
        remaining = text("", 23); remaining.setTypeface(Typeface.DEFAULT_BOLD); panel.addView(remaining);
        detail = text("", 13); detail.setTextColor(0xffb7c9e6); panel.addView(detail);
        next = button(R.string.backtrack_continue, this::continueGap); panel.addView(next, new LinearLayout.LayoutParams(-1, -2));
        showNext = button(R.string.backtrack_next_section, () -> { if (displayed != null && displayedRoute != null && displayed.segmentIndex + 1 < displayedRoute.segments.size()) actions.preview(displayedRoute.segments.get(displayed.segmentIndex + 1).points.get(0)); }); panel.addView(showNext, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout buttons = row(); buttons.addView(button(R.string.backtrack_stop, this::stop), new LinearLayout.LayoutParams(0, -2, 1));
        buttons.addView(button(R.string.backtrack_full, () -> { if (displayedRoute != null) actions.overview(displayedRoute); }), new LinearLayout.LayoutParams(0, -2, 1)); panel.addView(buttons);
        TextView options = text(activity.getString(R.string.backtrack_on_screen), 12); options.setTextColor(0xffb7c9e6); options.setMinHeight(dp(48)); options.setGravity(Gravity.CENTER_VERTICAL);
        options.setContentDescription(activity.getString(R.string.backtrack_on_screen) + ". " + activity.getString(R.string.backtrack_options)); options.setOnClickListener(v -> options()); panel.addView(options);
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        while (panel.getChildCount() > 0) { View child = panel.getChildAt(0); panel.removeViewAt(0); body.addView(child); }
        ScrollView scroll = new ScrollView(activity); scroll.addView(body); panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    }
    public boolean active() { return active; }
    public BacktrackRoute route() { return displayedRoute; }
    public BacktrackProgress.Snapshot snapshot() { return displayed; }
    public void restore() {
        int token = ++generation;
        store.load((loaded, failed) -> {
            if (closed || token != generation) return;
            if (failed) { toast(R.string.backtrack_storage_error); return; }
            if (loaded == null || loaded.snapshot().status == BacktrackProgress.Status.STOPPED) return;
            worker.execute(() -> { if (!valid(token)) return; engine = loaded; main.post(() -> { if (valid(token)) activate(loaded.route, loaded.snapshot(), false); }); });
        });
    }
    /** Caller supplies a stable track snapshot; BacktrackRoute creates its own immutable coordinate representation. */
    public void start(String id, long revision, List<double[]> points) {
        if (closed) return; int token = ++generation; dismissDialog(); toast(R.string.backtrack_loading);
        worker.execute(() -> {
            try {
                BacktrackProgress prepared = new BacktrackProgress(BacktrackRoute.fromTrack(id, revision, System.currentTimeMillis(), points));
                BacktrackProgress.Fix fix = current; List<BacktrackProgress.Candidate> candidates = prepared.startCandidates(fix);
                main.post(() -> { if (valid(token)) offer(prepared, candidates, fix, token); });
            } catch (IllegalArgumentException invalidTrack) { main.post(() -> { if (valid(token)) toast(R.string.backtrack_empty); }); }
        });
    }
    private void offer(BacktrackProgress prepared, List<BacktrackProgress.Candidate> candidates, BacktrackProgress.Fix fix, int token) {
        if (candidates.isEmpty()) {
            // Preserve the chosen snapshot even without GPS; offer the section once a fresh position arrives.
            commit(prepared, token, true); return;
        }
        if (candidates.size() > 1) {
            String[] labels = new String[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) { BacktrackProgress.Candidate c = candidates.get(i); labels[i] = activity.getString(R.string.backtrack_segment, c.segmentIndex + 1, distance(c.distanceMeters)) + " · " + activity.getString(R.string.backtrack_remaining, distance(prepared.route.knownRemaining(c.segmentIndex, c.alongMeters))); }
            dialog = new AlertDialog.Builder(activity).setTitle(R.string.backtrack_choose).setItems(labels, (d, which) -> choose(prepared, candidates.get(which), fix, token)).setNegativeButton(R.string.backtrack_cancel, null).show();
        } else choose(prepared, candidates.get(0), fix, token);
    }
    private void choose(BacktrackProgress prepared, BacktrackProgress.Candidate candidate, BacktrackProgress.Fix fix, int token) {
        if (!valid(token)) return;
        if (candidate.distanceMeters > BacktrackProgress.deviationThreshold(fix.accuracy)) {
            dialog = new AlertDialog.Builder(activity).setTitle(R.string.backtrack_approach)
                    .setMessage(activity.getString(R.string.backtrack_line_distance, distance(candidate.distanceMeters)))
                    .setPositiveButton(R.string.backtrack_continue_guidance, (d,w) -> select(prepared, candidate, fix, token))
                    .setNeutralButton(R.string.map_search_show, (d,w) -> { actions.preview(candidate.point); offer(prepared, java.util.Collections.singletonList(candidate), fix, token); })
                    .setNegativeButton(R.string.backtrack_cancel, null).show();
        } else select(prepared, candidate, fix, token);
    }
    private void select(BacktrackProgress prepared, BacktrackProgress.Candidate candidate, BacktrackProgress.Fix oldFix, int token) {
        worker.execute(() -> {
            if (!valid(token)) return;
            BacktrackProgress.Fix fix = current;
            // Revalidate the chosen edge using the latest fix, not a stale dialog position.
            if (fix == null || !fix.usable()) fix = oldFix;
            if (fix == null || SystemClock.elapsedRealtime() - fix.monotonicMillis > 30000) { main.post(() -> { if (valid(token)) toast(R.string.backtrack_gps); }); return; }
            prepared.select(candidate, fix); main.post(() -> { if (valid(token)) commit(prepared, token, true); });
        });
    }
    private void commit(BacktrackProgress prepared, int token, boolean announce) {
        store.save(prepared.route, prepared.snapshot(), saved -> {
            if (!valid(token)) return;
            if (!saved) { toast(R.string.backtrack_storage_error); return; }
            worker.execute(() -> { if (!valid(token)) return; engine = prepared; BacktrackProgress.Snapshot state = prepared.snapshot(); main.post(() -> { if (valid(token)) activate(prepared.route, state, announce); }); });
        });
    }
    private void activate(BacktrackRoute route, BacktrackProgress.Snapshot state, boolean announce) {
        active = true; displayedRoute = route; displayed = state; announced = state.status; actions.started(route); render(route, state);
        if (announce && actions.voiceEnabled()) { actions.speak(activity.getString(R.string.backtrack_title)); announced = state.status; lastAnnouncement = SystemClock.elapsedRealtime(); }
    }
    public void update(Location location) {
        BacktrackProgress.Fix fix = location == null ? null : new BacktrackProgress.Fix(location.getLatitude(), location.getLongitude(), location.hasAccuracy() ? location.getAccuracy() : Double.NaN, location.getElapsedRealtimeNanos() / 1_000_000, FiskentraLocationManager.isFresh(location));
        current = fix; if (closed || !active) return; int token = generation;
        worker.execute(() -> {
            if (!valid(token) || engine == null) return;
            if (engine.snapshot().status == BacktrackProgress.Status.CHOOSE_SEGMENT && fix != null && fix.usable()) {
                BacktrackProgress waiting = engine; List<BacktrackProgress.Candidate> candidates = waiting.startCandidates(fix);
                main.post(() -> { if (valid(token) && (dialog == null || !dialog.isShowing())) offer(waiting, candidates, fix, token); }); return;
            }
            BacktrackProgress.Snapshot before = engine.snapshot(), state = engine.update(fix); BacktrackRoute route = engine.route;
            if (before.alongMeters != state.alongMeters || before.status != state.status) store.save(route, state, saved -> { if (valid(token) && !saved) toast(R.string.backtrack_storage_error); });
            main.post(() -> { if (valid(token)) render(route, state); });
        });
    }
    private void continueGap() {
        if (!active || closed) return; int token = generation;
        worker.execute(() -> {
            if (!valid(token) || engine == null) return;
            if (!engine.continueAfterGap(current)) { main.post(() -> { if (valid(token)) toast(R.string.backtrack_gap_position); }); return; }
            BacktrackProgress.Snapshot state = engine.snapshot(); BacktrackRoute route = engine.route;
            store.save(route, state, saved -> { if (valid(token)) { if (!saved) toast(R.string.backtrack_storage_error); render(route, state); } });
        });
    }
    private void render(BacktrackRoute route, BacktrackProgress.Snapshot state) {
        displayedRoute = route; displayed = state; panel.setVisibility(View.VISIBLE); title.setText(R.string.backtrack_title);
        remaining.setText(activity.getString(state.remainingHasGap ? R.string.backtrack_remaining_known : R.string.backtrack_remaining, distance(state.knownRemainingMeters)));
        String label = statusLabel(state.status), secondary = label;
        if (Double.isFinite(state.distanceToTrackMeters)) secondary += " · " + activity.getString(R.string.backtrack_line_distance, distance(state.distanceToTrackMeters));
        if (current != null && current.usable()) secondary += " · " + activity.getString(R.string.backtrack_gps_quality, distance(current.accuracy));
        detail.setText(secondary); next.setVisibility(state.status == BacktrackProgress.Status.TRACK_GAP ? View.VISIBLE : View.GONE);
        showNext.setVisibility(state.status == BacktrackProgress.Status.TRACK_GAP ? View.VISIBLE : View.GONE);
        if (state.status == BacktrackProgress.Status.TRACK_GAP) detail.setText(activity.getString(R.string.backtrack_gap_message));
        renderVoice(); actions.changed(route, state, label, secondary);
        long now = SystemClock.elapsedRealtime();
        if (actions.voiceEnabled() && state.status != announced && (announced == null || now - lastAnnouncement >= 20000 || state.status == BacktrackProgress.Status.ARRIVED || state.status == BacktrackProgress.Status.TRACK_GAP)) {
            if (state.status != BacktrackProgress.Status.APPROACH && state.status != BacktrackProgress.Status.CHOOSE_SEGMENT) actions.speak(label);
            announced = state.status; lastAnnouncement = now;
        }
    }
    private void renderVoice() { voice.setText(actions.voiceEnabled() ? R.string.backtrack_voice_on : R.string.backtrack_voice_off); }
    private String statusLabel(BacktrackProgress.Status state) {
        switch (state) {
            case CHOOSE_SEGMENT: case GPS_PAUSED: return activity.getString(R.string.backtrack_gps);
            case APPROACH: return activity.getString(R.string.backtrack_approach);
            case OFF_ROUTE: return activity.getString(R.string.backtrack_off_route);
            case TRACK_GAP: return activity.getString(R.string.backtrack_gap);
            case ARRIVED: return activity.getString(R.string.backtrack_arrived);
            case STOPPED: return activity.getString(R.string.backtrack_stopped);
            default: return activity.getString(R.string.backtrack_title);
        }
    }
    private void options() {
        dialog = new AlertDialog.Builder(activity).setTitle(R.string.backtrack_options).setItems(new String[]{activity.getString(R.string.backtrack_finish)}, (d,w) -> {
            dialog = new AlertDialog.Builder(activity).setTitle(R.string.backtrack_finish).setMessage(R.string.backtrack_finish_message).setPositiveButton(R.string.backtrack_finish, (yes,which) -> { stop(); toast(R.string.backtrack_finished_manually); }).setNegativeButton(R.string.backtrack_cancel,null).show();
        }).setNegativeButton(R.string.backtrack_cancel, null).show();
    }
    public void stop() {
        if (closed) return;
        generation++; active = false; displayed = null; displayedRoute = null; dismissDialog(); panel.setVisibility(View.GONE); actions.stopped();
        worker.execute(() -> engine = null); store.clear(saved -> { if (!saved) toast(R.string.backtrack_storage_error); });
    }
    private String distance(double meters) {
        if (new UserPreferences(activity).usesImperialUnits()) return meters < 1609.344 ? activity.getString(R.string.map_search_feet, Math.round(meters * 3.28084)) : activity.getString(R.string.map_search_miles, meters / 1609.344);
        return meters < 1000 ? activity.getString(R.string.map_search_meters, Math.round(meters)) : activity.getString(R.string.map_search_kilometers, meters / 1000);
    }
    private boolean valid(int token) { return !closed && token == generation; }
    private void dismissDialog() { if (dialog != null) { dialog.dismiss(); dialog = null; } }
    private void toast(int resource) { Toast.makeText(activity, resource, Toast.LENGTH_LONG).show(); }
    @Override public void close() { closed = true; generation++; dismissDialog(); worker.shutdownNow(); store.close(); }
    private TextView text(String value, int size) { TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(0xffeff4ff); view.setPadding(0, dp(3), 0, dp(3)); return view; }
    private Button button(int text, Runnable action) { Button view = new Button(activity); view.setText(text); view.setAllCaps(false); view.setTextSize(14); view.setTextColor(0xffeff4ff); view.setMinHeight(dp(48)); view.setPadding(dp(8), dp(8), dp(8), dp(8)); view.setBackground(surface()); view.setOnClickListener(v -> action.run()); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(activity); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private GradientDrawable surface() { GradientDrawable shape = new GradientDrawable(); shape.setColor(0xff001e2e); shape.setCornerRadius(dp(14)); shape.setStroke(dp(1), 0xff53778f); return shape; }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
