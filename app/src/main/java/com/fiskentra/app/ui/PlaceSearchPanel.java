package com.fiskentra.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.fiskentra.app.BuildConfig;
import com.fiskentra.app.R;
import com.fiskentra.app.data.UserPreferences;
import com.fiskentra.app.model.FieldNavigation;
import com.fiskentra.app.model.SavedPoint;
import com.fiskentra.app.search.MapTilerGeocoding;
import com.fiskentra.app.search.LocalPointSearch;
import com.fiskentra.app.search.PlaceResult;
import com.fiskentra.app.search.PlaceSearchController;
import com.fiskentra.app.search.PlaceSearchProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;

/** Reusable content for the map's SEARCH overlay; the map owner controls camera and temporary markers. */
public final class PlaceSearchPanel extends LinearLayout implements AutoCloseable {
    public interface Actions {
        void selectPoint(SavedPoint point);
        void previewPlace(PlaceResult place);
        void clearPreview();
        void savePlace(PlaceResult place);
        void navigatePlace(PlaceResult place);
        void chooseOnMap(PlaceResult place);
        void close();
    }
    private static final int NAVY = 0xff001e2e, INK = 0xffeff4ff, MUTED = 0xffb7c9e6, BLUE = 0xff0059e8, CYAN = 0xff00a7ff, LINE = 0xff53778f;
    private final Actions actions;
    private final List<SavedPoint> filtered, all;
    private final double centerLat, centerLon;
    private final double[] bounds;
    private final PlaceSearchController controller;
    private final ExecutorService localWorker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile long localGeneration;
    private List<SavedPoint> localMatches = java.util.Collections.emptyList();
    private int localPage;
    private final SharedPreferences history;
    private final EditText input;
    private final CheckBox scope;
    private final TextView status, attribution;
    private final LinearLayout results;
    private final Button myPoints, places, retry;
    private boolean online, closed;
    private PlaceResult selected;
    private List<PlaceResult> lastPlaces=java.util.Collections.emptyList();
    private long lastPlacesGeneration=-1;
    private String lastPlacesQuery="";
    private boolean lastPlacesScope;
    public PlaceSearchPanel(Context context, List<SavedPoint> filtered, List<SavedPoint> all,
            double centerLat, double centerLon, double[] bounds, Actions actions) {
        super(context); this.actions = actions; this.filtered = new ArrayList<>(filtered); this.all = new ArrayList<>(all);
        this.centerLat = centerLat; this.centerLon = centerLon; this.bounds = PlaceResult.validBounds(bounds) ? bounds.clone() : null;
        history = context.getSharedPreferences("map_search_history", Context.MODE_PRIVATE);
        Handler main = new Handler(Looper.getMainLooper());
        controller = new PlaceSearchController(new MapTilerGeocoding(BuildConfig.GEOCODING_URL, BuildConfig.MAPTILER_API_KEY), main::post);
        setOrientation(VERTICAL); setPadding(dp(14), dp(10), dp(14), dp(14)); setBackground(surface(NAVY));
        LinearLayout heading = row(); TextView title = text(context.getString(R.string.map_search_title), 22); title.setTypeface(Typeface.DEFAULT_BOLD);
        heading.addView(title, new LayoutParams(0, -2, 1)); addView(heading);
        input = new EditText(context); input.setTextColor(INK); input.setHintTextColor(MUTED); input.setHint(R.string.map_search_hint);
        input.setSingleLine(true); input.setImeOptions(EditorInfo.IME_ACTION_SEARCH); input.setMinHeight(dp(48)); input.setBackgroundTintList(ColorStateList.valueOf(CYAN));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)}); addView(input, new LayoutParams(-1, -2));
        LinearLayout tabs = row(); myPoints = button(R.string.map_search_my_points, () -> source(false), false); places = button(R.string.map_search_places, () -> source(true), false);
        tabs.addView(myPoints, new LayoutParams(0, -2, 1)); tabs.addView(places, new LayoutParams(0, -2, 1)); addView(tabs);
        scope = new CheckBox(context); scope.setTextColor(MUTED); scope.setButtonTintList(ColorStateList.valueOf(CYAN)); scope.setMinHeight(dp(48)); addView(scope);
        status = text("", 13); status.setTextColor(MUTED); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); addView(status);
        retry = button(R.string.map_search_retry, this::refresh, false); addView(retry);
        ScrollView scroll = new ScrollView(context); scroll.setFillViewport(false);
        results = new LinearLayout(context); results.setOrientation(VERTICAL); scroll.addView(results); addView(scroll, new LayoutParams(-1, dp(190)));
        attribution = text("", 12); attribution.setTextColor(MUTED); attribution.setLinkTextColor(CYAN); attribution.setMovementMethod(LinkMovementMethod.getInstance()); addView(attribution);
        input.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) { } public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); } public void afterTextChanged(Editable e) { } });
        input.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { refresh(); return true; } return false; });
        scope.setOnCheckedChangeListener((button, checked) -> refresh()); source(false);
    }
    private void source(boolean usePlaces) {
        online = usePlaces; selected = null; controller.cancel();
        myPoints.setBackground(surface(online ? NAVY : BLUE)); places.setBackground(surface(online ? BLUE : NAVY));
        scope.setOnCheckedChangeListener(null); scope.setChecked(false);
        scope.setText(online ? R.string.map_search_area : R.string.map_search_all_points); scope.setEnabled(!online || bounds != null);
        scope.setOnCheckedChangeListener((button, checked) -> refresh()); refresh();
    }
    private void refresh() {
        if (closed) return; long localToken=++localGeneration; selected = null; actions.clearPreview(); retry.setVisibility(GONE); results.removeAllViews(); attribution.setText("");
        lastPlaces=java.util.Collections.emptyList();lastPlacesGeneration=-1;
        String query = input.getText().toString().trim();
        if (!online) {
            controller.cancel(); status.setText(getContext().getString(R.string.map_search_local) + (scope.isChecked() ? "" : " · " + getContext().getString(R.string.map_search_filtered)));
            List<SavedPoint> values = scope.isChecked() ? all : filtered;
            localWorker.execute(() -> {
                List<SavedPoint> matches = new ArrayList<>();
                for (SavedPoint point : values) { if(localToken!=localGeneration)return;if(LocalPointSearch.matches(query,point.title,point.type,point.note))matches.add(point); }
                ui.post(() -> { if(closed||online||localToken!=localGeneration)return;localMatches=matches;localPage=0;renderLocal(query); });
            });
            return;
        }
        controller.search(new PlaceSearchProvider.Request(query, centerLat, centerLon, scope.isChecked() ? bounds : null), state->{if(!closed&&online&&localToken==localGeneration)searchState(state);});
    }
    private void renderLocal(String query) {
        results.removeAllViews(); int first=localPage*50, end=Math.min(localMatches.size(),first+50);
        if(localMatches.isEmpty()){results.addView(text(getContext().getString(R.string.map_search_points_empty),15));return;}
        String source=getContext().getString(R.string.map_search_local)+(scope.isChecked()?"":" · "+getContext().getString(R.string.map_search_filtered));
        status.setText(source+"\n"+getContext().getString(R.string.map_search_page,first+1,end,localMatches.size()));
        for(int i=first;i<end;i++){SavedPoint point=localMatches.get(i);String label=point.title.isEmpty()?point.type:point.title;
            Button item=button(label+"\n"+point.type,()->{remember(query);actions.selectPoint(point);},false);item.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);results.addView(item,new LayoutParams(-1,-2));}
        if(localPage>0)results.addView(button(R.string.map_search_previous,()->{localPage--;renderLocal(query);},false),new LayoutParams(-1,-2));
        if(end<localMatches.size())results.addView(button(R.string.map_search_next,()->{localPage++;renderLocal(query);},false),new LayoutParams(-1,-2));
    }
    private void searchState(PlaceSearchController.State state) {
        if (closed || !online) return; results.removeAllViews(); retry.setVisibility(GONE);
        if (state.status == PlaceSearchController.Status.IDLE) { status.setText(R.string.map_search_minimum); showHistory(); return; }
        if (state.status == PlaceSearchController.Status.WAITING || state.status == PlaceSearchController.Status.LOADING) { status.setText(R.string.map_search_loading); return; }
        if (state.status == PlaceSearchController.Status.ERROR) {
            PlaceSearchProvider.Error error = state.failure.category; int label;
            switch (error) {
                case OFFLINE: label = R.string.map_search_offline; break;
                case TIMEOUT: label = R.string.map_search_timeout; break;
                case CONFIGURATION: label = R.string.map_search_configuration; break;
                case MALFORMED: label = R.string.map_search_malformed; break;
                case RATE_LIMIT: status.setText(getContext().getString(R.string.map_search_rate_limit, Math.max(1, (state.failure.retryAfterMillis + 999) / 1000))); retry.setVisibility(VISIBLE); return;
                default: label = R.string.map_search_service;
            }
            status.setText(label); retry.setVisibility(error == PlaceSearchProvider.Error.CONFIGURATION ? GONE : VISIBLE); return;
        }
        lastPlaces=new ArrayList<>(state.results);lastPlacesGeneration=localGeneration;lastPlacesQuery=input.getText().toString().trim();lastPlacesScope=scope.isChecked();
        renderPlaces(lastPlaces);
    }
    private void renderPlaces(List<PlaceResult> values){
        results.removeAllViews();retry.setVisibility(GONE);attribution.setText("");
        status.setText(values.isEmpty() ? R.string.map_search_empty : R.string.map_search_online);
        for (PlaceResult place : values) {
            String distance = FieldNavigation.validCoordinate(centerLat, centerLon) ? " · " + getContext().getString(R.string.map_search_from_center, distance(FieldNavigation.distance(centerLat, centerLon, place.latitude, place.longitude))) : "";
            String label = place.name + "\n" + (place.context.isEmpty() ? place.type : place.context) + distance;
            Button item = button(label, () -> select(place), false); item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); results.addView(item, new LayoutParams(-1, -2));
        }
        if (!values.isEmpty()) attribution.setText(Html.fromHtml(values.get(0).attribution, Html.FROM_HTML_MODE_LEGACY));
    }
    private void backToPlaces(long request){
        if(closed||!online||request!=localGeneration||request!=lastPlacesGeneration||!lastPlacesQuery.equals(input.getText().toString().trim())||lastPlacesScope!=scope.isChecked())return;
        selected=null;actions.clearPreview();renderPlaces(lastPlaces);
    }
    private void select(PlaceResult place) {
        selected = place; remember(input.getText().toString().trim()); actions.previewPlace(place); results.removeAllViews();
        long request=localGeneration;results.addView(button(R.string.map_search_back_results,()->backToPlaces(request),false),new LayoutParams(-1,-2));
        TextView label = text(place.name, 20); label.setTypeface(Typeface.DEFAULT_BOLD); results.addView(label); results.addView(text(place.context, 14));
        results.addView(button(R.string.map_search_show, () -> actions.previewPlace(place), true), new LayoutParams(-1, -2));
        results.addView(button(R.string.map_search_save, () -> actions.savePlace(place), false), new LayoutParams(-1, -2));
        if (place.areaDestination()) {
            results.addView(text(getContext().getString(R.string.map_search_area_destination), 13));
            results.addView(button(R.string.map_search_choose_destination, () -> actions.chooseOnMap(place), false), new LayoutParams(-1, -2));
        } else results.addView(button(R.string.map_search_navigate, () -> actions.navigatePlace(place), false), new LayoutParams(-1, -2));
        attribution.setText(Html.fromHtml(place.attribution, Html.FROM_HTML_MODE_LEGACY));
    }
    private void remember(String query) {
        if (query.isEmpty()) return;
        try { JSONArray old = new JSONArray(history.getString("queries", "[]")), next = new JSONArray().put(query);
            for (int i = 0; i < old.length() && next.length() < 8; i++) if (!query.equals(old.optString(i))) next.put(old.optString(i));
            history.edit().putString("queries", next.toString()).apply();
        } catch (Exception ignored) { }
    }
    private void showHistory() {
        try { JSONArray values = new JSONArray(history.getString("queries", "[]"));
            if (values.length() == 0) return; results.addView(text(getContext().getString(R.string.map_search_recent), 14));
            for (int i = 0; i < values.length(); i++) { String value = values.optString(i); results.addView(button(value, () -> input.setText(value), false), new LayoutParams(-1, -2)); }
            results.addView(button(R.string.map_search_clear_history, () -> { history.edit().remove("queries").apply(); results.removeAllViews(); }, false));
        } catch (Exception ignored) { }
    }
    private String distance(double meters) {
        if (new UserPreferences(getContext()).usesImperialUnits()) return meters < 1609.344 ? getContext().getString(R.string.map_search_feet, Math.round(meters * 3.28084)) : getContext().getString(R.string.map_search_miles, meters / 1609.344);
        return meters < 1000 ? getContext().getString(R.string.map_search_meters, Math.round(meters)) : getContext().getString(R.string.map_search_kilometers, meters / 1000);
    }
    public PlaceResult selectedPlace() { return selected; }
    @Override public void close() { closed = true; localGeneration++;lastPlaces=java.util.Collections.emptyList();lastPlacesGeneration=-1;localWorker.shutdownNow();ui.removeCallbacksAndMessages(null);controller.close(); }
    @Override protected void onDetachedFromWindow() { close(); super.onDetachedFromWindow(); }
    private LinearLayout row() { LinearLayout view = new LinearLayout(getContext()); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView text(String value, int size) { TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size); view.setTextColor(INK); view.setPadding(0, dp(4), 0, dp(4)); return view; }
    private Button button(int label, Runnable action, boolean primary) { return button(getContext().getString(label), action, primary); }
    private Button button(String label, Runnable action, boolean primary) {
        Button view = new Button(getContext()); view.setAllCaps(false); view.setText(label); view.setTextColor(INK); view.setTextSize(15); view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48));
        view.setPadding(dp(10), dp(10), dp(10), dp(10)); view.setBackground(surface(primary ? BLUE : NAVY)); view.setOnClickListener(v -> action.run()); return view;
    }
    private GradientDrawable surface(int color) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(14)); shape.setStroke(dp(1), LINE); return shape; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
