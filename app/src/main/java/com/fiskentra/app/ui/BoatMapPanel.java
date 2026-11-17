package com.fiskentra.app.ui;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.*;
import android.location.Location;
import android.view.*;
import android.widget.*;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import com.fiskentra.app.data.*;
import com.fiskentra.app.location.*;
import com.fiskentra.app.model.HazardPolygonGeometry;
import com.fiskentra.app.model.MapHazard;
import com.fiskentra.app.model.SavedPoint;
import com.fiskentra.app.model.FieldNavigation;
import com.fiskentra.app.navigation.NavigationVoice;
import com.fiskentra.app.R;
import com.fiskentra.app.model.MapFilterPolicy;
import com.fiskentra.app.model.MapCameraPolicy;
import com.fiskentra.app.model.LocationQualityPolicy;
import com.fiskentra.app.model.BoatArrivalPolicy;
import com.fiskentra.app.model.BoatSpeedPolicy;
import com.fiskentra.app.model.FishingDay;
import com.fiskentra.app.search.PlaceResult;
import com.fiskentra.app.backend.PointSyncQueue;
import com.fiskentra.app.backend.CloudDataPolicy;
import java.util.concurrent.*;
import org.maplibre.android.geometry.LatLng;
import java.util.*;

/** Compact field workspace; dialogs contain secondary controls so the map stays usable. */
// Constructed programmatically with its Activity and action owner; never inflated by Android.
@android.annotation.SuppressLint("ViewConstructor")
public final class BoatMapPanel extends LinearLayout implements SensorEventListener {
    public interface Actions {
        void saved(SavedPoint point);
        default void sync(SavedPoint point) { saved(point); }
        void details(SavedPoint point);void editDetails(SavedPoint point);void delete(SavedPoint point);void setLocation(SavedPoint point);
        void requestLocation();
        void toggleTrip(); void history(); void offline(); void export(); void navigationChanged(); void forecast();
        default void hazardsChanged() {}
    }
    private final Activity activity;
    private final Actions actions;
    private final PointStore points;
    private FrameLayout stage;
    private LinearLayout lower;
    private final LinearLayout searchBar, information, backtrackDock;
    private final FrameLayout statusChip;
    private final EditText searchInput;
    private MapSheet sheet;
    private PointSheet pointSheet;
    private String overlayMode="NONE";
    private View overlayFocus;
    private ImageButton locateButton;
    private TextView filterBadge;
    private Location lastPosition;
    private MapFilterPolicy filter;
    private List<SavedPoint> allPoints=Collections.emptyList(),filteredPoints=Collections.emptyList(),mapPoints=Collections.emptyList();
    private List<double[]> trackSnapshot=Collections.emptyList();
    private List<FishingDay> historyTrips=Collections.emptyList();
    private long historyRevision=Long.MIN_VALUE;
    private long dataRevision=-1;
    private String currentTrip="0", appliedTrip="";
    private List<SavedPoint> tripEvents=Collections.emptyList();
    private MapFilterPolicy appliedFilter;
    private boolean disposed;
    private final ExecutorService dataWorker=Executors.newSingleThreadExecutor();
    private final android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
    private int dataGeneration;
    private boolean imperial;
    private boolean compact;
    private LinearLayout tripMetrics;
    private double trackDistance;
    private BacktrackMapController backtrack;
    private String backtrackTitle="",backtrackDetail="";
    private com.fiskentra.app.debug.MapDebugFixture debugFixture;
    private MapFilterPolicy realFilter;
    private String realMode;
    private int debugDatasetGeneration;

    private final TrackStore tracks;
    private final SharedPreferences prefs;
    private final MapTilerMapView map;
    private final MapHazardDialogs hazardDialogs;
    private final LinearLayout hazardPlacementBanner;
    private final TextView hazardPlacementHint;
    private final Button redrawHazardZone, finishHazardZone;
    private List<MapHazard.Vertex> hazardBoundary = Collections.emptyList();
    private Boolean placeHazardZone;

    private final TextView status, gps, speed, mapNotice, navigation, tripInfo, title, subtitle, state, metricOne, metricTwo, metricThree;
    private final TextView metricOneLabel, metricTwoLabel, metricThreeLabel;
    private final LinearLayout card, thirdMetric, tools, guidance;
    private final Button primary, secondary;
    private final CompassDial compass;
    private final FrameLayout windHud;
    private final ImageView bluetoothIcon;
    private String mode;
    private final BoatNavigationHud hud;
    private final NavigationVoice voice;
    private boolean voiceEnabled;
    private final SensorManager sensors;
    private final Sensor rotation;
    private Location location;
    private SavedPoint selected, destination;

    private double heading = Double.NaN;
    private long lastSensorUpdate;
    private final Runnable headingExpired=this::invalidateHeading;
    private String lastSignals = "";
    private boolean listening, arrived, nearDestination;
    private final BoatArrivalPolicy arrivalPolicy=new BoatArrivalPolicy();
    private final BoatSpeedPolicy.Measurement speedMeasurement=new BoatSpeedPolicy.Measurement();
    private final boolean[] layers;
    private static final String[] LAYER_KEYS={"points","track","labels","layer_wind","layer_depths","layer_seamarks","layer_heatmap","layer_history"};
    private static final String[] LAYER_NAMES={"Saved points","Trip track","Point labels","Wind","Depth contours","Sea marks","Catch heatmap","Past trips"};
    private float depthOpacity,heatOpacity;
    private String heatSpecies;
    private Set<String> historyIds;
    private static final int DEPTH_CREDIT = 1, SEA_MARKS_CREDIT = 2;
    // The map panel is recreated on tab changes. Show each credit once per app session.
    private static int shownMarineCredits;
    private PopupWindow marineCredits;
    private TextView layerDialogStatus;
    private AlertDialog layerOptionsDialog;
    private static final int INK = 0xffeff4ff, MUTED = 0xffb7c9e6, NAVY = 0xff001e2e,
            BLUE = 0xff00a7ff, BUTTON = 0xff0059e8, LINE = 0xff53778f, AQUA = 0xff35dfd1;
    public BoatMapPanel(Activity activity, SavedPoint selected, Actions actions) {
        super(activity); this.activity = activity; this.actions = actions; this.selected = selected;
        points = new PointStore(activity); tracks = new TrackStore(activity);
        prefs = activity.getSharedPreferences("field_map", 0);
        // Previously selected walking/driving destinations resume with offline boat guidance.
        // Road routing code is retained elsewhere for a later release, with no active entrypoint.
        prefs.edit().putString("route_profile", "direct").apply();
        layers = new boolean[LAYER_KEYS.length];for(int i=0;i<layers.length;i++)layers[i]=prefs.getBoolean(LAYER_KEYS[i],i<3);
        depthOpacity=Math.max(.2f,Math.min(1f,prefs.getFloat("depth_opacity",.60f)));heatOpacity=Math.max(.2f,Math.min(1f,prefs.getFloat("heat_opacity",.70f)));
        heatSpecies=prefs.getString("heat_species","");historyIds=new HashSet<>(prefs.getStringSet("history_ids",Collections.emptySet()));
        mode = prefs.getString("panel_mode", "Trip");
        imperial=new UserPreferences(activity).usesImperialUnits();compact=prefs.getBoolean("compact_hud",true);
        filter=new MapFilterPolicy(prefs.getString("filter_scope","all"),prefs.getString("filter_trip",""),prefs.getLong("filter_from",0),prefs.getLong("filter_to",Long.MAX_VALUE),prefs.getStringSet("filter_types",new HashSet<>(Arrays.asList(MapFilterPolicy.TYPES))),prefs.getBoolean("filter_favorite",false));
        setOrientation(VERTICAL); setBackgroundColor(NAVY); setFocusableInTouchMode(true);
        stage = new FrameLayout(activity); addView(stage, new LayoutParams(-1, 0, 1));
        String savedStyle = prefs.getString("style", MapTilerMapView.STYLE_OUTDOOR);
        String baseStyle = MapTilerMapView.normalizeOnlineStyleId(savedStyle);
        if (!baseStyle.equals(savedStyle)) {
            prefs.edit().putString("style", baseStyle).apply();
        }
        map = new MapTilerMapView(activity, baseStyle, null);
        stage.addView(map, new FrameLayout.LayoutParams(-1, -1));
        map.setDirectGuidance(true);
        hazardDialogs = new MapHazardDialogs(activity, map, actions::hazardsChanged);

        searchBar=row();searchBar.setMinimumHeight(dp(52));searchBar.setPadding(dp(14),dp(2),dp(2),dp(2));
        searchBar.setBackground(surface(NAVY,24,LINE));searchBar.setElevation(dp(4));
        ImageView searchIcon=new ImageView(activity);searchIcon.setImageResource(R.drawable.ic_search);searchIcon.setColorFilter(BLUE);
        searchIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        searchBar.addView(searchIcon,new LayoutParams(dp(24),dp(24)));
        searchInput=new EditText(activity);searchInput.setSingleLine(true);searchInput.setTextSize(14);searchInput.setTextColor(INK);searchInput.setHintTextColor(MUTED);
        searchInput.setHint("Search places");searchInput.setContentDescription("Search places");
        searchInput.setBackgroundColor(Color.TRANSPARENT);searchInput.setPadding(dp(10),0,dp(4),0);searchInput.setMinHeight(dp(48));
        searchInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        searchBar.addView(searchInput,new LayoutParams(0,-2,1));
        searchBar.addView(iconButton(R.drawable.ic_layers,"Map layers and tools",()->{closeOverlay();layers();},false),new LayoutParams(dp(48),dp(48)));
        FrameLayout.LayoutParams searchLp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);searchLp.setMargins(dp(14),dp(12),dp(14),0);stage.addView(searchBar,searchLp);
        searchInput.setOnFocusChangeListener((v,focused)->{if(focused&&!disposed&&!"SEARCH".equals(overlayMode))search();});
        searchInput.setOnClickListener(v->search());

        information=new LinearLayout(activity);information.setOrientation(VERTICAL);
        FrameLayout.LayoutParams informationLp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.START);informationLp.setMargins(dp(14),dp(72),dp(92),0);stage.addView(information,informationLp);
        statusChip=new FrameLayout(activity);statusChip.setMinimumHeight(dp(44));statusChip.setOnClickListener(v->signalDetails());
        statusChip.setContentDescription("Flic and GPS connection details");
        LinearLayout signals=row();signals.setPadding(dp(8),0,dp(8),0);signals.setMinimumHeight(dp(32));signals.setBackground(surface(NAVY,18,LINE));
        bluetoothIcon=new ImageView(activity);bluetoothIcon.setImageResource(R.drawable.ic_bluetooth);bluetoothIcon.setColorFilter(0xffffb53e);
        signals.addView(bluetoothIcon,new LayoutParams(dp(16),dp(16)));
        status=text("Flic",12);status.setPadding(dp(4),0,dp(8),0);status.setSingleLine(true);signals.addView(status);
        View signalRule=rule();signals.addView(signalRule,new LayoutParams(dp(1),dp(16)));
        ImageView gpsIcon=new ImageView(activity);gpsIcon.setImageResource(R.drawable.ic_map_pin);gpsIcon.setColorFilter(MUTED);
        LayoutParams gpsIconLp=new LayoutParams(dp(16),dp(16));gpsIconLp.setMargins(dp(8),0,dp(4),0);signals.addView(gpsIcon,gpsIconLp);
        gps=text("GPS...",12);gps.setSingleLine(true);gps.setEllipsize(android.text.TextUtils.TruncateAt.END);gps.setTextColor(MUTED);signals.addView(gps,new LayoutParams(-2,-2));
        statusChip.addView(signals,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_VERTICAL|Gravity.START));information.addView(statusChip,new LayoutParams(-2,-2));
        mapNotice=text("Loading map.",12);mapNotice.setPadding(dp(10),dp(6),dp(10),dp(6));mapNotice.setMaxLines(2);mapNotice.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mapNotice.setBackground(surface(NAVY,12,LINE));mapNotice.setOnClickListener(v->signalDetails());information.addView(mapNotice,new LayoutParams(-1,-2));
        filterBadge=MapUi.text(activity,"",12,false);filterBadge.setBackground(surface(NAVY,12,LINE));filterBadge.setPadding(dp(8),dp(6),dp(8),dp(6));filterBadge.setOnClickListener(v->filters());filterBadge.setMinHeight(dp(40));filterBadge.setMaxLines(1);filterBadge.setEllipsize(android.text.TextUtils.TruncateAt.END);filterBadge.setVisibility(GONE);
        information.addView(filterBadge,new LayoutParams(-2,-2));
        tripInfo=text("",12);tripInfo.setBackground(surface(NAVY,12,LINE));tripInfo.setPadding(dp(8),dp(6),dp(8),dp(6));tripInfo.setMinHeight(dp(40));tripInfo.setOnClickListener(v->trips());tripInfo.setVisibility(GONE);information.addView(tripInfo,new LayoutParams(-2,-2));
        hazardPlacementBanner = row();
        hazardPlacementBanner.setOrientation(VERTICAL);
        hazardPlacementBanner.setBackground(surface(NAVY, 12, LINE));
        hazardPlacementBanner.setPadding(dp(8), dp(8), dp(8), 0);
        hazardPlacementHint = text("", 13);
        hazardPlacementHint.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        hazardPlacementBanner.addView(hazardPlacementHint, new LayoutParams(-1, -2));
        LinearLayout hazardControls = row();
        redrawHazardZone = button(activity.getString(R.string.map_hazard_redraw), this::redrawHazardZone);
        hazardControls.addView(redrawHazardZone, new LayoutParams(0, dp(48), 1));
        Button cancelPlacement = button(activity.getString(R.string.map_cancel), this::cancelHazardPlacement);
        hazardControls.addView(cancelPlacement, new LayoutParams(0, dp(48), 1));
        finishHazardZone = button(activity.getString(R.string.map_hazard_finish), this::finishHazardZone);
        hazardControls.addView(finishHazardZone, new LayoutParams(0, dp(48), 1));
        hazardPlacementBanner.addView(hazardControls, new LayoutParams(-1, -2));
        hazardPlacementBanner.setVisibility(GONE);
        information.addView(hazardPlacementBanner, new LayoutParams(-1, -2));

        compass=new CompassDial(activity);compass.setOnClickListener(v->map.north());compass.setContentDescription("Compass heading. Tap for north-up map");
        FrameLayout.LayoutParams compassLp=new FrameLayout.LayoutParams(dp(64),dp(64),Gravity.TOP|Gravity.END);compassLp.setMargins(0,dp(76),dp(14),0);stage.addView(compass,compassLp);
        speed = text("", 20);
        speed.setGravity(Gravity.CENTER);
        speed.setBackground(surface(NAVY, 32, LINE));
        speed.setPadding(dp(4), dp(4), dp(4), dp(4));
        speed.setIncludeFontPadding(false);
        speed.setMaxLines(2);
        speed.setAutoSizeTextTypeUniformWithConfiguration(10, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        FrameLayout.LayoutParams speedLp = new FrameLayout.LayoutParams(compassLp);
        speedLp.topMargin = compassLp.topMargin + compassLp.height + dp(8);
        stage.addView(speed, speedLp);
        updateSpeed(null);
        windHud=new FrameLayout(activity);windHud.addView(map.windIndicator(),new FrameLayout.LayoutParams(-1,-2));
        FrameLayout.LayoutParams windLp=new FrameLayout.LayoutParams(dp(76),-2,Gravity.TOP|Gravity.END);windLp.setMargins(0,speedLp.topMargin+speedLp.height+dp(8),dp(8),0);stage.addView(windHud,windLp);
        windHud.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateMapInsets());
        hud=new BoatNavigationHud(activity,new BoatNavigationHud.Actions(){
            public void destination(){showDestination();}
            public void voice(){toggleVoice();}
            public void options(){routeOptions();}
            public void stop(){if(backtrack!=null&&backtrack.active())backtrack.stop();else navigate(null);}
            public void choose(){search();}
        });
        voiceEnabled=prefs.getBoolean("navigation_voice",false);
        voice=new NavigationVoice(activity,()->{voiceEnabled=false;prefs.edit().putBoolean("navigation_voice",false).apply();hud.profile(destination!=null,false);toast("English voice unavailable. Check Android text-to-speech settings.");});
        voice.setEnabled(voiceEnabled);guidance=hud.turnPanel;navigation=text("Choose a destination",14);
        LayoutParams guidanceLp=new LayoutParams(-1,-2);guidanceLp.topMargin=dp(4);information.addView(guidance,guidanceLp);

        lower=new LinearLayout(activity);lower.setOrientation(HORIZONTAL);lower.setGravity(Gravity.CENTER_VERTICAL);lower.setPadding(dp(14),0,dp(14),dp(12));
        if(getResources().getConfiguration().screenWidthDp>=360)lower.addView(new View(activity),new LayoutParams(dp(48),1));
        lower.addView(hud.routePanel,new LayoutParams(0,-2,1));
        ImageButton add=floatingButton(R.drawable.ic_plus,"Add point",this::addMenu,true);
        LayoutParams addLp=new LayoutParams(dp(56),dp(56));addLp.setMarginStart(dp(12));lower.addView(add,addLp);
        stage.addView(lower,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        tools=new LinearLayout(activity);tools.setOrientation(VERTICAL);
        ImageButton hazardButton = floatingButton(R.drawable.ic_alert_triangle,
                activity.getString(R.string.map_hazards), this::showHazards, false);
        hazardButton.setColorFilter(0xffff9083);
        LayoutParams hazardButtonLp = new LayoutParams(dp(48), dp(48));
        hazardButtonLp.bottomMargin = dp(8);
        tools.addView(hazardButton, hazardButtonLp);
        locateButton=floatingButton(R.drawable.ic_current_location,"Locate me",this::locate,false);tools.addView(locateButton,new LayoutParams(dp(48),dp(48)));
        FrameLayout.LayoutParams toolsLp=new FrameLayout.LayoutParams(dp(48),-2,Gravity.BOTTOM|Gravity.END);toolsLp.setMargins(0,0,dp(18),dp(84));stage.addView(tools,toolsLp);

        // Trip details remain available on demand, rather than covering the map at rest.
        card=new LinearLayout(activity);card.setOrientation(VERTICAL);card.setPadding(dp(8),dp(8),dp(8),dp(8));
        LinearLayout cardHeader=row();title=text("Current trip",19);title.setTypeface(Typeface.DEFAULT_BOLD);cardHeader.addView(title,new LayoutParams(0,-2,1));
        state=text("Ready",11);state.setTextColor(AQUA);cardHeader.addView(state);card.addView(cardHeader);
        cardHeader.setMinimumHeight(dp(48));cardHeader.setOnClickListener(v->{compact=!compact;prefs.edit().putBoolean("compact_hud",compact).apply();renderPanel();});
        subtitle=text("Start a fishing trip",13);subtitle.setTextColor(MUTED);card.addView(subtitle);
        tripMetrics=row();tripMetrics.setPadding(0,dp(12),0,dp(10));
        metricOne=text("0 min",23);metricTwo=text("0",23);metricThree=text("0",23);
        metricOneLabel=text("Duration",12);metricTwoLabel=text("Catches",12);metricThreeLabel=text("Points",12);
        tripMetrics.addView(metric(metricOne,metricOneLabel),new LayoutParams(0,-2,1));tripMetrics.addView(rule(),new LayoutParams(dp(1),dp(38)));
        tripMetrics.addView(metric(metricTwo,metricTwoLabel),new LayoutParams(0,-2,1));thirdMetric=metric(metricThree,metricThreeLabel);tripMetrics.addView(thirdMetric,new LayoutParams(0,-2,1));card.addView(tripMetrics);card.addView(rule());
        LinearLayout cardActions=row();cardActions.setPadding(0,dp(8),0,0);
        secondary=button("Events",this::secondaryAction);primary=button("Start trip",this::primaryAction);
        LayoutParams leftAction=new LayoutParams(0,-2,1);leftAction.rightMargin=dp(10);secondary.setMinHeight(dp(48));cardActions.addView(secondary,leftAction);primary.setMinHeight(dp(48));cardActions.addView(primary,new LayoutParams(0,-2,1));card.addView(cardActions);

        backtrack=new BacktrackMapController(activity,new BacktrackMapController.Actions(){
            public void started(com.fiskentra.app.model.BacktrackRoute route){navigate(null);mode="Track";map.setBacktrackRoute(route);renderPanel();}
            public void changed(com.fiskentra.app.model.BacktrackRoute route,com.fiskentra.app.model.BacktrackProgress.Snapshot snapshot,String title,String detail){backtrackTitle=title;backtrackDetail=detail.equals(title)?"":detail.startsWith(title+" ")?detail.substring(title.length()).trim():detail;compass.setTarget(snapshot.bearing);updateNavigation();updateMapInsets();}
            public void stopped(){map.setBacktrack(null);voice.pause();renderPanel();updateNavigation();}
            public void preview(com.fiskentra.app.model.BacktrackRoute.Point p){map.lookAt(p.latitude,p.longitude);}
            public void overview(com.fiskentra.app.model.BacktrackRoute route){map.showBacktrackRoute(route);}
            public void speak(String message){voice.announce(message);}
            public boolean voiceEnabled(){return voiceEnabled;}
            public void toggleVoice(){BoatMapPanel.this.toggleVoice();}
        });
        backtrackDock=new LinearLayout(activity);backtrackDock.setOrientation(VERTICAL);backtrackDock.setPadding(dp(14),0,dp(14),dp(12));backtrackDock.addView(backtrack.panel,new LayoutParams(-1,-2));backtrackDock.setVisibility(GONE);
        stage.addView(backtrackDock,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        lower.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateMapInsets());
        tools.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateMapInsets());
        information.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateMapInsets());
        searchBar.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateMapInsets());
        backtrackDock.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateMapInsets());

        map.setLayers(layers[0],layers[1],layers[2]);
        map.setListener(new MapTilerMapView.Listener(){
            public void onLayersChanged(){updateLayerDialogStatus();}
            public void onMapStatusChanged(){post(()->{if(!disposed)updateGps(lastPosition);});}
            public void onPlace(double latitude, double longitude) {
                if (placeHazardZone != null) {
                    if (!placeHazardZone) {
                        cancelHazardPlacement();
                        hazardDialogs.addPoint(latitude, longitude);
                    }
                } else {
                    map.setTapToPlace(false);
                    place(latitude, longitude);
                }
            }
            @Override public void onHazardStroke(List<MapHazard.Vertex> samples) {
                acceptHazardStroke(samples);
            }
            public void onPoint(SavedPoint point){pointMenu(point);}
            public void onHazard(MapHazard hazard) { if (!debugReadOnly()) hazardDialogs.showDetails(hazard); }
            public void onCameraMode(MapCameraPolicy.Mode value,boolean available){updateLocate(value,available);}
            public void onCameraIdle(){updateOfflineCoverage();}
            public void onCluster(List<SavedPoint> group){
                String[] labels=new String[group.size()];java.text.SimpleDateFormat time=new java.text.SimpleDateFormat("dd MMM - HH:mm",Locale.getDefault());
                for(int i=0;i<group.size();i++){SavedPoint p=group.get(i);labels[i]=(p.title.isEmpty()?p.type:p.title)+" - "+time.format(new Date(p.timestamp));}
                new AlertDialog.Builder(activity).setTitle(group.size()+" points here").setItems(labels,(d,w)->pointMenu(group.get(w))).setNegativeButton("Close",null).show();
            }
        });
        hazardDialogs.refresh();
        applyFishingLayers();
        sensors = activity.getSystemService(SensorManager.class); rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (prefs.contains("nav_lat")) {
            try { destination = new SavedPoint(prefs.getLong("nav_id",-2), Double.parseDouble(prefs.getString("nav_lat", "0")), Double.parseDouble(prefs.getString("nav_lon", "0")), 0, "Waypoint", "", null, null, prefs.getString("nav_label", "Destination")); }
            catch (NumberFormatException ignored) { }
        }
        map.setDestination(destination);
        renderPanel(); updateNavigation();
        backtrack.restore();
        if(selected!=null)post(()->{
            if(!disposed)pointMenu(selected);
        });
    }
    public MapTilerMapView map() { return map; }
    public void update(Location current, String signalText) {
        if(disposed)return;lastPosition=current;
        imperial=new UserPreferences(activity).usesImperialUnits();
        lastSignals = signalText;
        location = FiskentraLocationManager.isFresh(current) ? current : null;
        boolean connected = signalText.contains("Flic · connected");
        status.setContentDescription(connected?"Flic connected":signalText.contains("not paired")?"Flic not paired":"Flic disconnected");
        bluetoothIcon.setColorFilter(connected ? AQUA : 0xffffb53e);
        updateGps(current);
        backtrack.update(location);
        refreshData();
        renderPanel();
        updateNavigation();
    }
    private void locate() {
        if (!fresh()) { requestGps(); toast("Waiting for a fresh location"); } else map.locate();
    }
    private void requestGps(){android.location.LocationManager manager=activity.getSystemService(android.location.LocationManager.class);boolean enabled=manager!=null&&(manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)||manager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER));if(!enabled)activity.startActivity(new android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));else actions.requestLocation();}
    private void signalDetails() {
        new AlertDialog.Builder(activity).setTitle("Connections & map").setMessage(lastSignals + "\n" + map.getMapStatus())
                .setPositiveButton("Done", null).setNeutralButton("Request GPS", (d,w) -> requestGps()).setNegativeButton(R.string.map_basemap_retry,(d,w)->map.retryBaseStyle()).show();
    }
    private void modeMenu() {
        new AlertDialog.Builder(activity).setTitle("Map workspace")
                .setItems(new String[]{"Trip", "Track", "Navigation", "Map only", "Trip history", "Export GPX", "Weather & forecast"}, (d,which) -> {
                    if (which < 4) {
                        mode = new String[]{"Trip", "Track", "Navigation", "Map"}[which];
                        prefs.edit().putString("panel_mode", mode).apply(); renderPanel();
                        if (which == 2 && destination == null) toast("Hold any place on the map, then choose Navigate here");
                    } else if (which == 4) actions.history(); else if (which == 5) actions.export(); else actions.forecast();
                }).show();
    }
    private void renderPanel() {
        boolean active = tracks.isActive(), paused = tracks.isPaused(), trackMode = "Track".equals(mode), navMode = "Navigation".equals(mode);
        boolean returning=backtrack!=null&&backtrack.active();
        card.setVisibility(VISIBLE);
        hud.routePanel.setVisibility(VISIBLE);
        lower.setVisibility(sheet==null&&!returning?VISIBLE:GONE);backtrackDock.setVisibility(sheet==null&&returning?VISIBLE:GONE);tools.setVisibility(sheet==null?VISIBLE:GONE);
        String distance = MapUi.distance(trackDistance,imperial);
        setTextIfChanged(tripInfo,active?(paused?"Track paused - ":"Track - ")+distance:"");tripInfo.setVisibility(active?VISIBLE:GONE);
        title.setText(navMode ? "Navigation" : trackMode ? (active ? "Recording track" : "Track") : tracks.startedAt() > 0 && !active ? "Last trip" : "Current trip");
        state.setText(navMode ? (destination == null ? "Ready" : "● Active") : active ? (paused ? "Ⅱ Paused" : "● Active") : tracks.startedAt() > 0 ? "Saved" : "Ready");
        long start = tracks.startedAt(), end = active ? System.currentTimeMillis() : tracks.stoppedAt();
        long seconds = start > 0 ? Math.max(0, end - start) / 1000 : 0;
        int catches = 0, count = 0;
        for (SavedPoint p : tripEvents) { count++; if ("Catch".equals(p.type)) catches++; }
        subtitle.setText(navMode ? "Boat guidance - direct distance and bearing" : compact&&start>0?duration(seconds)+" · "+(trackMode?distance:getResources().getQuantityString(R.plurals.map_catch_count,catches,catches)):trackMode ? "Saved locally · " + (paused ? "recording paused" : "GPS track") : start > 0 ? new java.text.SimpleDateFormat("EEE, d MMM · HH:mm", Locale.getDefault()).format(new Date(start)) : "Your next fishing trip");
        metricOne.setText(trackMode ? distance : duration(seconds)); metricOneLabel.setText(trackMode ? "Distance" : "Duration");
        metricTwo.setText(trackMode ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) : String.format(Locale.getDefault(),"%d",catches));
        metricTwoLabel.setText(trackMode ? "Trip elapsed" : "Catches");
        metricThree.setText(String.format(Locale.getDefault(),"%d",count)); thirdMetric.setVisibility(trackMode || navMode ? GONE : VISIBLE);
        secondary.setText(navMode ? "Show point" : trackMode ? (paused ? "▶ Resume" : "Ⅱ Pause") : "☷ Events · " + count);
        secondary.setEnabled(!navMode || destination != null); secondary.setAlpha(secondary.isEnabled() ? 1f : .45f);
        if (trackMode) { secondary.setEnabled(active); secondary.setAlpha(active ? 1f : .45f); }
        secondary.setBackground(surface(trackMode && active ? BUTTON : NAVY, 12, trackMode && active ? BUTTON : LINE));
        primary.setText(navMode ? (destination == null ? "Choose point" : "Stop") : active ? (trackMode ? "■ Stop & save" : "End trip") : "Start trip");
        primary.setTextColor(BLUE); primary.setBackground(surface(NAVY, 12, BLUE));
        tripMetrics.setVisibility(compact?GONE:VISIBLE);
        updateNavigation();
    }
    private String duration(long seconds) { return seconds >= 3600 ? (seconds / 3600) + "h " + (seconds / 60 % 60) + "m" : (seconds / 60) + " min"; }
    private void primaryAction() {
        if(debugReadOnly())return;
        if ("Navigation".equals(mode)) { if (destination != null) navigate(null); else search(); }
        else actions.toggleTrip();
    }
    private void secondaryAction() {
        if(debugReadOnly())return;
        if ("Navigation".equals(mode)) showDestination();
        else if ("Track".equals(mode)) { if (tracks.isActive()) { tracks.setPaused(!tracks.isPaused()); update(lastPosition, lastSignals); } }
        else events();
    }
    private void events() {
        List<SavedPoint> items = tripEvents;
        String[] labels = new String[items.size()];
        java.text.SimpleDateFormat time = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault());
        for (int i = 0; i < items.size(); i++) { SavedPoint p = items.get(i); labels[i] = time.format(new Date(p.timestamp)) + "   " + (p.title.isEmpty() ? p.type : p.title); }
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("Trip events · " + items.size()).setNegativeButton("Close", null);
        if (items.isEmpty()) dialog.setMessage("Points saved during this trip will appear here.");
        else dialog.setItems(labels, (d,w) -> pointMenu(items.get(w)));
        dialog.show();
    }
    private void search() {
        if(sheet!=null&&"SEARCH".equals(overlayMode)){searchInput.requestFocus();return;}
        if(sheet!=null)closeOverlay();
        LatLng center=map.center();org.maplibre.android.geometry.LatLngBounds b=map.visibleBounds();
        double[] bounds=b==null?null:new double[]{b.getLonWest(),b.getLatSouth(),b.getLonEast(),b.getLatNorth()};
        BoatPlaceSearchPanel panel=new BoatPlaceSearchPanel(activity,filteredPoints,allPoints,center==null?Double.NaN:center.getLatitude(),center==null?Double.NaN:center.getLongitude(),bounds,new BoatPlaceSearchPanel.Actions(){
            public void selectPoint(SavedPoint p){pointMenu(p);}
            public void previewPlace(PlaceResult p){map.setPreview(placePoint(p));double[] b=p.bounds();if(p.areaDestination()&&b!=null)map.showBounds(b[3],b[2],b[1],b[0]);else map.lookAt(p.latitude,p.longitude);}
            public void clearPreview(){map.setPreview(null);}
            public void savePlace(PlaceResult p){closeOverlay();edit(null,p.latitude,p.longitude);}
            public void navigatePlace(PlaceResult p,String profile){closeOverlay();navigate(placePoint(p));}
            public void chooseOnMap(PlaceResult p){closeOverlay();map.lookAt(p.latitude,p.longitude);map.setTapToPlace(true);toast(activity.getString(R.string.map_choose_destination_on_map));}
            public void close(){closeOverlay();}
        },searchInput);showOverlay("SEARCH",panel);
    }
    private SavedPoint placePoint(PlaceResult p){return new SavedPoint(-3,p.latitude,p.longitude,0,"Waypoint","",null,null,p.name);}
    private boolean fresh() { return FiskentraLocationManager.isFresh(location); }
    private boolean guidanceReady() {
        return location != null && LocationQualityPolicy.guidance(location.getElapsedRealtimeNanos(),
                android.os.SystemClock.elapsedRealtimeNanos(), location.hasAccuracy() ? location.getAccuracy() : Double.NaN);
    }
    private String guidanceWaitMessage() { return fresh() ? "Waiting for accurate GPS" : "Waiting for fresh GPS"; }
    private void addMenu() {
        if(debugReadOnly())return;
        cancelHazardPlacement();
        new AlertDialog.Builder(activity).setTitle("Add point").setItems(new String[]{"At my GPS position", "At map center", "Tap anywhere on map", "Enter coordinates",
                activity.getString(R.string.map_hazard_add_point), activity.getString(R.string.map_hazard_add_zone)}, (d, which) -> {
            if (which == 0) { if (fresh()) edit(null, location.getLatitude(), location.getLongitude()); else { actions.requestLocation(); toast("Waiting for a fresh location"); } }
            if (which == 1) { LatLng center = map.center(); if (center != null) place(center.getLatitude(), center.getLongitude()); }
            if (which == 2) { map.setTapToPlace(true); toast("Tap map to place a point · long press also works"); }
            if (which == 3) coordinates();
            if (which == 4 || which == 5) beginHazardPlacement(which == 5);
        }).show();
    }
    private void showHazards() {
        if (debugReadOnly()) return;
        closeOverlay();
        hazardDialogs.showList(this::beginHazardPlacement);
    }
    private void beginHazardPlacement(boolean zone) {
        closeOverlay();
        placeHazardZone = zone;
        map.setTapToPlace(true);
        updateHazardDraft();
        hazardPlacementBanner.setVisibility(VISIBLE);
    }
    private void acceptHazardStroke(List<MapHazard.Vertex> samples) {
        if (!Boolean.TRUE.equals(placeHazardZone) || !hazardBoundary.isEmpty()) return;
        try {
            hazardBoundary = HazardPolygonGeometry.fromFreehandStroke(samples);
        } catch (IllegalArgumentException invalid) {
            hazardPlacementHint.setText(R.string.map_hazard_invalid_boundary);
            return;
        }
        updateHazardDraft();
    }
    private void redrawHazardZone() {
        hazardBoundary = Collections.emptyList();
        updateHazardDraft();
    }
    private void updateHazardDraft() {
        boolean zone = Boolean.TRUE.equals(placeHazardZone);
        boolean ready = !hazardBoundary.isEmpty();
        int hint = R.string.map_hazard_place_point;
        if (zone) hint = ready ? R.string.map_hazard_review_zone : R.string.map_hazard_draw_zone;
        hazardPlacementHint.setText(hint);
        redrawHazardZone.setVisibility(ready ? VISIBLE : GONE);
        finishHazardZone.setVisibility(zone ? VISIBLE : GONE);
        finishHazardZone.setEnabled(ready);
        map.setHazardDrawing(zone && !ready);
        map.setHazardDraft(hazardBoundary);
    }
    private void finishHazardZone() {
        if (!hazardBoundary.isEmpty()) hazardDialogs.addPolygon(hazardBoundary, this::cancelHazardPlacement);
    }
    private void cancelHazardPlacement() {
        if (placeHazardZone == null) return;
        placeHazardZone = null;
        hazardBoundary = Collections.emptyList();
        map.setHazardDrawing(false);
        map.setHazardDraft(hazardBoundary);
        map.setTapToPlace(false);
        hazardPlacementBanner.setVisibility(GONE);
    }
    private void coordinates() {
        LinearLayout form = form(); EditText lat = input(form, "Latitude −90…90", ""), lon = input(form, "Longitude −180…180", "");
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Coordinates · decimal degrees").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Show", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                double latitude = Double.parseDouble(lat.getText().toString().replace(',', '.')), longitude = Double.parseDouble(lon.getText().toString().replace(',', '.'));
                if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) throw new NumberFormatException();
                dialog.dismiss(); map.lookAt(latitude, longitude); place(latitude, longitude);
            } catch (NumberFormatException e) { lat.setError("Enter valid latitude and longitude"); }
        })); dialog.show();
    }
    private void place(double lat, double lon) {
        if(debugReadOnly())return;
        new AlertDialog.Builder(activity).setTitle(String.format(Locale.US, "%.5f, %.5f", lat, lon))
                .setItems(new String[]{"Save point…", "Navigate here", activity.getString(R.string.map_hazard_add_point),
                        activity.getString(R.string.map_hazard_add_zone)}, (d, which) -> {
                    if (which == 0) edit(null, lat, lon);
                    else if (which == 1) navigate(new SavedPoint(-2, lat, lon, 0, "Waypoint", ""));
                    else if (which == 2) hazardDialogs.addPoint(lat, lon);
                    else beginHazardPlacement(true);
                }).setNegativeButton("Cancel", null).show();
    }
    private void pointMenu(SavedPoint point) {
        closeOverlay();selected=point;
        pointSheet=new PointSheet(activity,new PointSheet.Actions(){
            public void navigate(){if(selected==null||debugReadOnly())return;if(selected.hasLocation()){SavedPoint target=selected;closeOverlay();BoatMapPanel.this.navigate(target);}else actions.setLocation(selected);}
            public void edit(){if(!debugReadOnly())editSelected();}
            public void details(){if(selected!=null&&!debugReadOnly())actions.details(selected);}
            public void favorite(){if(selected!=null&&!debugReadOnly()){long id=selected.id;boolean value=!selected.favorite;dataWorker.execute(()->{try{points.setFavorite(id,value);ui.post(()->update(lastPosition,lastSignals));}catch(Exception e){ui.post(()->toast(activity.getString(R.string.map_save_failed)));}});}}
            public void more(){if(!debugReadOnly())pointMore();}
        });showOverlay("POINT",pointSheet);map.selectPoint(point);bindPoint();
    }
    private void editSelected(){if(selected==null)return;SavedPoint p=selected;ArrayList<String> labels=new ArrayList<>();labels.add(activity.getString(R.string.map_point_appearance));labels.add(activity.getString(R.string.map_trip_binding));if("Catch".equals(p.type))labels.add(activity.getString(R.string.map_catch_editor));new AlertDialog.Builder(activity).setTitle(R.string.map_point_editor).setItems(labels.toArray(new String[0]),(d,w)->{if(w==0)edit(p,p.latitude,p.longitude);else if(w==1)assignTrip(p);else actions.editDetails(p);}).show();}
    private void assignTrip(SavedPoint p){List<FishingDay> trips=new FishingDayStore(activity).all();ArrayList<String> labels=new ArrayList<>();labels.add(activity.getString(R.string.map_unassigned));for(FishingDay trip:trips)labels.add(java.text.DateFormat.getDateTimeInstance().format(new Date(trip.startedAt)));new AlertDialog.Builder(activity).setTitle(R.string.map_trip_binding).setItems(labels.toArray(new String[0]),(d,w)->dataWorker.execute(()->{try{points.setTripId(p.id,w==0?0:trips.get(w-1).id);ui.post(()->update(lastPosition,lastSignals));}catch(Exception e){ui.post(()->toast(activity.getString(R.string.map_save_failed)));}})).show();}
    private void pointMore(){
        if(selected==null)return;SavedPoint p=selected;
        boolean localOnly=CloudDataPolicy.isLocalOnlyBeta();
        int deleteLabel=localOnly?R.string.data_delete_from_phone:R.string.map_delete;
        new AlertDialog.Builder(activity).setItems(new String[]{activity.getString(R.string.map_set_location),
                activity.getString(localOnly?R.string.data_storage_info:R.string.map_retry_sync),activity.getString(deleteLabel)},(d,w)->{
            if(w==0)actions.setLocation(p);
            else if(w==1){
                if(localOnly)new AlertDialog.Builder(activity).setTitle(R.string.data_local_storage_title)
                        .setMessage(activity.getString(R.string.data_local_storage_summary)+"\n\n"+activity.getString(R.string.data_previous_cloud_notice))
                        .setPositiveButton(android.R.string.ok,null).show();
                else actions.sync(p);
            }else new AlertDialog.Builder(activity).setTitle(deleteLabel)
                    .setMessage(PointSyncQueue.get(activity).deleteNotice(p))
                    .setNegativeButton(R.string.map_cancel,null).setPositiveButton(deleteLabel,(q,n)->{
                        closeOverlay();if(destination!=null&&destination.id==p.id){navigate(null);toast(activity.getString(R.string.map_target_deleted));}actions.delete(p);
                    }).show();
        }).show();
    }
    private void bindPoint(){if(pointSheet!=null&&selected!=null)pointSheet.bind(selected,location,imperial,debugFixture==null?PointSyncQueue.get(activity).pointStatus(selected):activity.getString(R.string.map_debug_readonly));}
    public void openPointForQa(long id){if(com.fiskentra.app.BuildConfig.DEBUG){SavedPoint p=points.find(id);if(p!=null){pointMenu(p);sheet.setExpanded(true);}}}
    private void edit(SavedPoint existing, double lat, double lon) {
        LinearLayout form = form();
        EditText label = input(form, "Point name / designation", existing == null ? "" : existing.title); label.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        String[] types = {"Waypoint", "Catch", "Tackle change", "Camp", "Hazard", "Sighting"};
        Spinner type = spinner(form, "Point type", types);
        if (existing != null) { for (int i = 0; i < types.length; i++) if (types[i].equals(existing.type)) type.setSelection(i); type.setEnabled(false); }
        String[] icons = {"●", "⚑", "🐟", "⛺", "!", "★", "◆", "✚"};
        Spinner icon = spinner(form, "Marker icon", icons);
        String[] colorNames = {"Gold", "Green", "Blue", "Purple", "Red", "White"};
        int[] colors = {0xffffc455, 0xff53d689, 0xff68c4ff, 0xffc59bff, 0xfff67267, 0xffffffff};
        Spinner color = spinner(form, "Color", colorNames);
        Spinner size = spinner(form, "Size", new String[]{"Small", "Medium", "Large"});
        int[] sizes = {12, 18, 26}; size.setSelection(1);
        if (existing != null) {
            for (int i = 0; i < icons.length; i++) if (icons[i].equals(existing.symbol)) icon.setSelection(i);
            for (int i = 0; i < colors.length; i++) if (colors[i] == existing.color) color.setSelection(i);
            size.setSelection(existing.size <= 12 ? 0 : existing.size >= 24 ? 2 : 1);
        }
        TextView preview = new TextView(activity); preview.setGravity(Gravity.CENTER); preview.setPadding(0, dp(10), 0, dp(10)); form.addView(preview);
        Runnable update = () -> { preview.setText(icons[icon.getSelectedItemPosition()]); preview.setTextColor(colors[color.getSelectedItemPosition()]); preview.setTextSize(sizes[size.getSelectedItemPosition()] * 2); };
        AdapterView.OnItemSelectedListener changed = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) { update.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        icon.setOnItemSelectedListener(changed); color.setOnItemSelectedListener(changed); size.setOnItemSelectedListener(changed); update.run();
        CheckBox defaults = new CheckBox(activity); defaults.setText(R.string.map_ui_default_appearance); form.addView(defaults);
        ScrollView scroll = new ScrollView(activity); scroll.addView(form);
        new AlertDialog.Builder(activity).setTitle(existing == null ? "New point" : "Point appearance").setView(scroll).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    SavedPoint point = existing == null ? points.add(lat, lon, types[type.getSelectedItemPosition()], "") : existing;
                    int chosenColor = colors[color.getSelectedItemPosition()], chosenSize = sizes[size.getSelectedItemPosition()]; String symbol = icons[icon.getSelectedItemPosition()];
                    point = points.style(point.id, label.getText().toString().trim(), symbol, chosenColor, chosenSize);
                    if (point == null) { toast("Point no longer exists"); return; }
                    selected = point;
                    if (defaults.isChecked()) prefs.edit().putInt(point.type + "_color", chosenColor).putInt(point.type + "_size", chosenSize).putString(point.type + "_symbol", symbol).apply();
                    if (destination != null && destination.id == point.id) { destination=point;map.setDestination(point);prefs.edit().putString("nav_label",point.title).apply(); }
                    actions.saved(point);
                    update(lastPosition, lastSignals); toast("Point saved on this device");
                }).show();
    }
    private void navigate(SavedPoint point) {
        if(point!=null&&!point.hasLocation()){actions.setLocation(point);return;}
        if(point!=null&&backtrack!=null&&backtrack.active()){
            new AlertDialog.Builder(activity).setTitle(R.string.backtrack_replace_title).setMessage(R.string.map_replace_backtrack).setNegativeButton(R.string.map_cancel,null).setPositiveButton(R.string.map_navigate,(d,w)->{backtrack.stop();navigate(point);}).show();return;
        }
        voice.reset();
        map.setDirectGuidance(true);
        destination = point; arrived = false;nearDestination=false;arrivalPolicy.reset();map.setDestination(point);
        SharedPreferences.Editor edit = prefs.edit().putString("route_profile","direct");
        if (point == null) edit.remove("nav_lat").remove("nav_lon").remove("nav_label").remove("nav_id");
        else edit.putString("nav_lat", Double.toString(point.latitude)).putString("nav_lon", Double.toString(point.longitude)).putString("nav_label", point.title).putLong("nav_id",point.id);
        if (point != null) { mode = "Navigation"; edit.putString("panel_mode", mode); }
        edit.apply(); actions.navigationChanged(); renderPanel(); updateNavigation();
    }
    private void showDestination() {
        if(destination==null)search();
        else map.lookAt(destination.latitude,destination.longitude);
    }
    private void routeOptions() {
        if(backtrack!=null&&backtrack.active()){
            new AlertDialog.Builder(activity).setTitle("Return guidance").setItems(new String[]{"Full track","Trip & recording","Stop guidance"},(d,w)->{
                if(w==0)map.showBacktrackRoute(backtrack.route());else if(w==1)trips();else backtrack.stop();
            }).show();return;
        }
        if(destination==null){search();return;}
        new AlertDialog.Builder(activity).setTitle("Boat guidance")
                .setItems(new String[]{"Guidance overview","Show destination","Change destination",voiceEnabled?"Mute voice guidance":"Enable voice guidance","Stop guidance","Guidance information"},(d,w)->{
                    if(w==0)map.showRoute();
                    else if(w==1)showDestination();
                    else if(w==2)search();
                    else if(w==3)toggleVoice();
                    else if(w==4)navigate(null);
                    else new AlertDialog.Builder(activity).setTitle("Boat guidance").setMessage("Offline GPS distance and bearing to your selected point. The line shows the direction; it does not calculate a navigable water route. Return along a recorded track is available from Trip tools.").setPositiveButton("Close",null).show();
                }).show();
    }
    private void updateNavigation() {
        boolean returning=backtrack!=null&&backtrack.active();
        if(returning){
            map.setNavigating(true);compass.setVisibility(VISIBLE);guidance.setVisibility(sheet==null?VISIBLE:GONE);hud.status(backtrackTitle);
            lower.setVisibility(GONE);backtrackDock.setVisibility(sheet==null?VISIBLE:GONE);updateMapInsets();return;
        }
        boolean ready=guidanceReady();
        updateNavigationState(ready);
        hud.profile(destination!=null,voiceEnabled);
        hud.status(navigation.getText().toString());
        if(destination!=null&&ready){
            double meters=FieldNavigation.distance(location.getLatitude(),location.getLongitude(),destination.latitude,destination.longitude);
            double bearing=FieldNavigation.bearing(location.getLatitude(),location.getLongitude(),destination.latitude,destination.longitude);
            hud.summary(nearDestination?"Destination reached":BoatArrivalPolicy.distanceLabel(meters,imperial)+" - "+Math.round((bearing+360)%360)+"\u00b0",destination.title);
        }else hud.summary(destination==null?"Choose destination":guidanceWaitMessage(),destination==null?"":destination.title);
        updateMapInsets();
    }
    private void toggleVoice() { voiceEnabled=!voiceEnabled;prefs.edit().putBoolean("navigation_voice",voiceEnabled).apply();voice.setEnabled(voiceEnabled);updateNavigation(); }
    private void updateNavigationState(boolean ready) {
        nearDestination=false;
        map.setNavigating(destination!=null);compass.setVisibility(VISIBLE);guidance.setVisibility(destination!=null&&sheet==null?VISIBLE:GONE);
        if(destination==null){compass.setTarget(Double.NaN);setTextIfChanged(navigation,"Choose a point on the water");return;}
        String name=destination.title.isEmpty()?"Destination":destination.title;
        if(!ready){setTextIfChanged(navigation,guidanceWaitMessage()+"\n"+name);compass.setTarget(Double.NaN);return;}
        double meters=FieldNavigation.distance(location.getLatitude(),location.getLongitude(),destination.latitude,destination.longitude);
        double bearing=(FieldNavigation.bearing(location.getLatitude(),location.getLongitude(),destination.latitude,destination.longitude)+360)%360;
        nearDestination=arrivalPolicy.update(meters,location.hasAccuracy()?location.getAccuracy():Double.NaN);
        if(nearDestination&&!arrived){toast("Destination reached");voice.announce("You have reached the destination area");arrived=true;}
        String direction=!nearDestination&&Double.isFinite(heading)?" - turn "+Math.round(FieldNavigation.turn(bearing,heading))+"\u00b0":"";
        setTextIfChanged(navigation,(nearDestination?"Destination reached":BoatArrivalPolicy.distanceLabel(meters,imperial)+" to destination")+"\n"+name+direction);compass.setTarget(nearDestination?Double.NaN:bearing);
    }
    private void layers() {
        new AlertDialog.Builder(activity).setTitle("Map layers").setMultiChoiceItems(LAYER_NAMES, layers, (d, index, checked) -> {
            layers[index] = checked; map.setLayers(layers[0], layers[1], layers[2]);
            prefs.edit().putBoolean(LAYER_KEYS[index],checked).apply();applyFishingLayers();
        }).setNegativeButton("Tools",(d,w)->mapTools()).setNeutralButton("Map types", (d, w) -> baseMaps()).setPositiveButton("Done", null).show();
    }
    private void baseMaps() {
        String[] styles = {MapTilerMapView.STYLE_OUTDOOR, MapTilerMapView.STYLE_HYBRID};
        String[] names = new String[styles.length];
        for (int i = 0; i < styles.length; i++) names[i] = MapTilerMapView.styleName(styles[i]);
        new AlertDialog.Builder(activity).setTitle("Base map")
                .setSingleChoiceItems(names, Arrays.asList(styles).indexOf(map.getBaseStyle()), (dialog, which) -> {
                    dialog.dismiss();
                    map.setBaseStyle(styles[which]);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void applyFishingLayers(){
        List<SavedPoint> catches=mapPoints;
        if(!heatSpecies.isEmpty()){
            ArrayList<SavedPoint> chosen=new ArrayList<>();
            for(SavedPoint point:mapPoints){String species=point.catchDetails==null?"":point.catchDetails.species;if(heatSpecies.equals(species))chosen.add(point);}catches=chosen;
        }
        map.setFishingLayers(layers[3],layers[4],layers[5],layers[6],layers[7],catches,historyTrips,historyIds,depthOpacity,heatOpacity,imperial);
    }
    private void showMarineCredits() {
        if (disposed || !isShown() || !hasWindowFocus()) {
            return;
        }
        int activeCredits = (layers[4] ? DEPTH_CREDIT : 0) | (layers[5] ? SEA_MARKS_CREDIT : 0);
        int newCredits = activeCredits & ~shownMarineCredits;
        if (newCredits == 0) {
            return;
        }
        List<String> credits = new ArrayList<>(2);
        if ((newCredits & DEPTH_CREDIT) != 0) {
            credits.add(activity.getString(R.string.map_depth_attribution));
        }
        if ((newCredits & SEA_MARKS_CREDIT) != 0) {
            credits.add(activity.getString(R.string.map_sea_marks_attribution));
        }
        TextView notice = text(android.text.TextUtils.join("\n", credits), 12);
        notice.setPaddingRelative(dp(12), dp(8), dp(12), dp(8));
        notice.setBackground(surface(NAVY, 12, LINE));
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (marineCredits != null) {
            marineCredits.dismiss();
        }
        PopupWindow popup = new PopupWindow(notice, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, false);
        popup.setTouchable(false);
        popup.showAsDropDown(searchBar, 0, dp(4), Gravity.END);
        marineCredits = popup;
        shownMarineCredits |= newCredits;
        int timeout = 5000;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.view.accessibility.AccessibilityManager accessibility =
                    activity.getSystemService(android.view.accessibility.AccessibilityManager.class);
            if (accessibility != null) {
                timeout = accessibility.getRecommendedTimeoutMillis(timeout,
                        android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT);
            }
        }
        ui.postDelayed(() -> {
            if (marineCredits == popup) {
                popup.dismiss();
                marineCredits = null;
            }
        }, timeout);
    }
    private void updateLayerDialogStatus() {
        if (disposed || layerDialogStatus == null) {
            return;
        }
        String status = map.fishingLayerStatus();
        setTextIfChanged(layerDialogStatus, status.isEmpty()
                ? activity.getString(R.string.map_additional_layers_off) : status);
    }
    private void layerOptions(){
        if(disposed)return;if(layerOptionsDialog!=null){layerOptionsDialog.dismiss();layerOptionsDialog=null;}
        LinearLayout body=new LinearLayout(activity);body.setOrientation(VERTICAL);body.setPadding(dp(20),dp(4),dp(20),dp(12));
        layerDialogStatus=MapUi.text(activity,"",13,false);body.addView(layerDialogStatus);updateLayerDialogStatus();
        addOpacityControl(body,"Depth opacity",depthOpacity,value->{depthOpacity=value;prefs.edit().putFloat("depth_opacity",value).apply();applyFishingLayers();});
        addOpacityControl(body,"Catch heatmap opacity",heatOpacity,value->{heatOpacity=value;prefs.edit().putFloat("heat_opacity",value).apply();applyFishingLayers();});
        Button species=MapUi.button(activity,R.string.map_filter_types,()->{layerOptionsDialog.dismiss();chooseHeatSpecies();},false);species.setText(activity.getString(R.string.map_ui_catch_species,heatSpecies.isEmpty()?activity.getString(R.string.map_ui_all_species):heatSpecies));body.addView(species);
        Button history=MapUi.button(activity,R.string.map_return_saved,()->{layerOptionsDialog.dismiss();chooseHistoryTrips();},false);history.setText(activity.getString(R.string.map_ui_past_trips,historyIds.isEmpty()?activity.getString(R.string.map_ui_all_recent):historyIds.contains("__none__")?activity.getString(R.string.map_ui_none):getResources().getQuantityString(R.plurals.map_ui_selected_trips,historyIds.size(),historyIds.size())));body.addView(history);
        Button pointFilters=MapUi.button(activity,R.string.map_filter_title,()->{layerOptionsDialog.dismiss();filters();},false);pointFilters.setText(R.string.map_ui_point_filters);body.addView(pointFilters);
        TextView legendTitle=MapUi.text(activity,"Depth colour legend · metres relative to Lowest Astronomical Tide (LAT). The colour scale is non-linear.",13,true);legendTitle.setPadding(0,dp(14),0,dp(8));body.addView(legendTitle);
        ImageView legend=new ImageView(activity);legend.setImageResource(R.drawable.depth_legend);legend.setScaleType(ImageView.ScaleType.FIT_CENTER);legend.setBackgroundColor(Color.WHITE);legend.setContentDescription("EMODnet depth scale: red at zero to ten metres, yellow near fifty, green near one hundred fifty to four hundred fifty, cyan near one thousand, blue at two to three thousand five hundred, dark at six thousand metres");LayoutParams legendLp=new LayoutParams(dp(198),dp(275));legendLp.gravity=Gravity.CENTER_HORIZONTAL;body.addView(legend,legendLp);
        TextView details=MapUi.text(activity,"Wind applies at the selected point, otherwise your fresh GPS location or the map centre. The arrow points downwind.\n\nThe heatmap shows your recorded catches and follows point filters; it does not predict fish. Past trips show up to 12 completed routes, each in a different colour.\n\nDepth contours cover marine areas; inland and near-shore coverage may be missing. Depths and sea marks are reference information and do not check boat-route clearance.",13,false);details.setPadding(0,dp(12),0,dp(12));body.addView(details);
        TextView sources=MapUi.text(activity,"",12,false);sources.setText(android.text.Html.fromHtml("Weather data by <a href=\"https://open-meteo.com/\">Open-Meteo.com</a> · <a href=\"https://creativecommons.org/licenses/by/4.0/\">CC BY 4.0</a><br><br>Depths: <a href=\"https://emodnet.ec.europa.eu/en/bathymetry\">EMODnet Bathymetry</a> · <a href=\"https://creativecommons.org/licenses/by/4.0/\">CC BY 4.0</a><br><br>Sea marks: © <a href=\"https://www.openseamap.org/\">OpenSeaMap</a> and © <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap contributors</a> · underlying data ODbL; rendered tiles <a href=\"https://creativecommons.org/licenses/by-sa/2.0/\">CC BY-SA 2.0</a>",android.text.Html.FROM_HTML_MODE_LEGACY));sources.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());sources.setLinkTextColor(BLUE);body.addView(sources);
        ScrollView scroll=new ScrollView(activity);scroll.addView(body);
        layerOptionsDialog=new AlertDialog.Builder(activity).setTitle("Layer options & sources").setView(scroll).setPositiveButton("Done",null).setNeutralButton("Retry online layers",(d,w)->map.retryFishingLayers()).create();
        layerOptionsDialog.setOnDismissListener(d->{layerDialogStatus=null;layerOptionsDialog=null;});layerOptionsDialog.show();
    }
    private void addOpacityControl(LinearLayout body,String title,float initial,java.util.function.Consumer<Float> changed){
        TextView label=MapUi.text(activity,title+" · "+Math.round(initial*100)+"%",14,true);label.setPadding(0,dp(14),0,0);body.addView(label);
        SeekBar slider=new SeekBar(activity);slider.setMax(80);slider.setProgress(Math.round(initial*100)-20);slider.setContentDescription(title);slider.setMinimumHeight(dp(48));body.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar view,int progress,boolean user){label.setText(activity.getString(R.string.map_ui_opacity,title,progress+20));if(user)changed.accept((progress+20)/100f);}public void onStartTrackingTouch(SeekBar view){}public void onStopTrackingTouch(SeekBar view){}});
    }
    private void chooseHeatSpecies(){
        TreeSet<String> found=new TreeSet<>();for(SavedPoint point:allPoints)if("Catch".equals(point.type)&&point.catchDetails!=null&&!point.catchDetails.species.isEmpty())found.add(point.catchDetails.species);
        ArrayList<String> values=new ArrayList<>();values.add("");values.addAll(found);String[] labels=new String[values.size()];labels[0]="All species";for(int i=1;i<labels.length;i++)labels[i]=values.get(i);
        new AlertDialog.Builder(activity).setTitle("Catch heatmap species").setSingleChoiceItems(labels,Math.max(0,values.indexOf(heatSpecies)),(d,w)->{heatSpecies=values.get(w);prefs.edit().putString("heat_species",heatSpecies).apply();applyFishingLayers();d.dismiss();}).setNegativeButton("Done",null).setOnDismissListener(d->{if(!disposed)layerOptions();}).show();
    }
    private void chooseHistoryTrips(){
        ArrayList<FishingDay> available=new ArrayList<>();for(FishingDay day:historyTrips)if(!day.isActive()&&com.fiskentra.app.model.PastTripGeometry.hasLine(day.route))available.add(day);
        available.sort((a,b)->Long.compare(b.startedAt,a.startedAt));
        if(available.isEmpty()){new AlertDialog.Builder(activity).setTitle("Past trips").setMessage("No completed trips with recorded tracks yet. Completed recorded trips will appear here.").setPositiveButton("Done",null).setOnDismissListener(d->{if(!disposed)layerOptions();}).show();return;}
        String[] labels=new String[available.size()+1];boolean[] checked=new boolean[labels.length];labels[0]="All recent trips (up to 12)";checked[0]=historyIds.isEmpty();
        java.text.DateFormat date=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT);
        for(int i=0;i<available.size();i++){FishingDay day=available.get(i);labels[i+1]=date.format(new Date(day.startedAt));checked[i+1]=historyIds.isEmpty()||historyIds.contains(Long.toString(day.id));}
        new AlertDialog.Builder(activity).setTitle("Choose past trips").setMultiChoiceItems(labels,checked,(dialog,index,on)->{
            AlertDialog chooser=(AlertDialog)dialog;
            if(index==0){historyIds.clear();if(!on)historyIds.add("__none__");for(int i=1;i<checked.length;i++){checked[i]=on;chooser.getListView().setItemChecked(i,on);}}
            else {if(historyIds.isEmpty())for(FishingDay day:available)historyIds.add(Long.toString(day.id));historyIds.remove("__none__");String id=Long.toString(available.get(index-1).id);if(on)historyIds.add(id);else historyIds.remove(id);if(historyIds.isEmpty())historyIds.add("__none__");checked[0]=false;chooser.getListView().setItemChecked(0,false);}
            prefs.edit().putStringSet("history_ids",new HashSet<>(historyIds)).apply();applyFishingLayers();
        }).setPositiveButton("Done",null).setOnDismissListener(d->{if(!disposed)layerOptions();}).show();
    }
    private void trips() {
        if(debugReadOnly())return;
        new AlertDialog.Builder(activity).setTitle(String.format(Locale.US, "Trip · %.2f km", com.fiskentra.app.model.FieldNavigation.distanceMeters(trackSnapshot) / 1000))
            .setItems(new String[]{tracks.isActive() ? "Stop & save trip" : "Start new trip", "Trip history", "Export GPX", tracks.isPaused() ? "Resume recording" : "Pause recording",activity.getString(R.string.map_return),activity.getString(R.string.map_return_saved)}, (d, which) -> {
                if (which == 0) actions.toggleTrip();
                if (which == 1) actions.history();
                if (which == 2) actions.export();
                if (which == 3) { if (tracks.isActive()) { tracks.setPaused(!tracks.isPaused()); update(lastPosition, lastSignals); } else toast("Start a trip first"); }
                if(which==4)startBacktrack(Long.toString(tracks.startedAt()),trackSnapshot);
                if(which==5)savedBacktrack();
            }).show();
    }
    public void resumeSensors() {
        voice.setActive(true);
        if(!listening)invalidateHeading();
        if (!listening && rotation != null) listening = sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI);
        if (rotation == null) compass.setHeading(Double.NaN);
    }
    public void pauseSensors() { sensors.unregisterListener(this); listening = false; voice.setActive(false); invalidateHeading(); }
    private void invalidateHeading(){ui.removeCallbacks(headingExpired);heading=Double.NaN;compass.setHeading(heading);map.setHeading(heading);if(!disposed)updateNavigation();}
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        resumeSensors();
        ui.post(this::showMarineCredits);
    }
    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            ui.post(this::showMarineCredits);
        }
    }
    @Override protected void onDetachedFromWindow() {
        cancelHazardPlacement();
        hazardDialogs.close();
        disposed = true;
        if (marineCredits != null) {
            marineCredits.dismiss();
            marineCredits = null;
        }
        if (layerOptionsDialog != null) {
            layerOptionsDialog.dismiss();
        }
        dataGeneration++;
        dataWorker.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        if (backtrack != null) {
            backtrack.close();
        }
        pauseSensors();
        voice.close();
        super.onDetachedFromWindow();
    }
    @Override public void onSensorChanged(SensorEvent event) {
        long now = android.os.SystemClock.elapsedRealtime();
        if(!listening)return;
        if(event.accuracy==SensorManager.SENSOR_STATUS_UNRELIABLE||!MapCameraPolicy.sensorFresh(event.timestamp/1000000L,now)){invalidateHeading();return;}
        if (now - lastSensorUpdate < 100) return;
        lastSensorUpdate = now;
        float[] matrix = new float[9], angles = new float[3]; SensorManager.getRotationMatrixFromVector(matrix, event.values); SensorManager.getOrientation(matrix, angles);
        double declination = location == null ? 0 : new android.hardware.GeomagneticField((float)location.getLatitude(), (float)location.getLongitude(), (float)location.getAltitude(), System.currentTimeMillis()).getDeclination();
        heading = (Math.toDegrees(angles[0]) + declination + 360) % 360;
        ui.removeCallbacks(headingExpired);ui.postDelayed(headingExpired,Math.max(1,MapCameraPolicy.HEADING_MAX_AGE_MILLIS-(now-event.timestamp/1000000L)));
        compass.setHeading(heading);
        // True bearing navigation requires a location for magnetic declination correction.
        map.setHeading(location == null ? Double.NaN : heading);
        updateNavigation();
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { if(accuracy==SensorManager.SENSOR_STATUS_UNRELIABLE)invalidateHeading(); }

    private void refreshData(){
        final int request=++dataGeneration;final MapFilterPolicy selectedFilter=filter;
        final com.fiskentra.app.debug.MapDebugFixture fixture=debugFixture;
        dataWorker.execute(()->{
            PointLedger.Snapshot snapshot=points.snapshot();List<SavedPoint> source=fixture==null?snapshot.points:fixture.points;String trip=Long.toString(fixture==null?points.currentTripId():fixture.tripId);List<double[]> line=fixture==null?tracks.points():fixture.track;double distance=FieldNavigation.distanceMeters(line);
            List<FishingDay> days=fixture!=null&&"layers".equals(fixture.name)?fixture.pastTrips:new FishingDayStore(activity).all();
            long nextHistoryRevision=historySignature(days);
            List<SavedPoint> events=com.fiskentra.app.model.TripEventPolicy.forTrack(source,days,tracks.startedAt(),tracks.isActive()?Long.MAX_VALUE:tracks.stoppedAt());
            List<SavedPoint> filtered=selectedFilter.apply(source,trip);ArrayList<SavedPoint> placed=new ArrayList<>();for(SavedPoint p:filtered)if(p.hasLocation())placed.add(p);
            ui.post(()->{if(disposed||request!=dataGeneration)return;
                if(dataRevision!=snapshot.revision||appliedFilter!=selectedFilter||!appliedTrip.equals(trip)||allPoints!=source){dataRevision=snapshot.revision;allPoints=source;filteredPoints=filtered;mapPoints=Collections.unmodifiableList(placed);appliedFilter=selectedFilter;appliedTrip=trip;}
                currentTrip=trip;tripEvents=events;if(historyRevision!=nextHistoryRevision){historyTrips=days;historyRevision=nextHistoryRevision;}if(!sameTrack(trackSnapshot,line))trackSnapshot=line;trackDistance=distance;
                if(selected!=null){SavedPoint latest=fixture!=null?selected:points.find(selected.id);if(latest==null){closeOverlay();}else if(!selectedFilter.matches(latest,trip)){closeOverlay();toast(activity.getString(R.string.map_filter_hidden));}else selected=latest;}
                if(destination!=null&&destination.id>0&&points.find(destination.id)==null){navigate(null);toast(activity.getString(R.string.map_target_deleted));}
                if(destination!=null&&destination.id>0){SavedPoint latest=points.find(destination.id);if(latest!=null&&latest.hasLocation()){if(latest.latitude!=destination.latitude||latest.longitude!=destination.longitude)navigate(latest);else{destination=latest;map.setDestination(latest);}}}
                map.setData(lastPosition,mapPoints,trackSnapshot,selected);applyFishingLayers();bindPoint();
                filterBadge.setText(getResources().getQuantityString(R.plurals.map_visible_count,filteredPoints.size()-mapPoints.size(),mapPoints.size(),filteredPoints.size()-mapPoints.size()));filterBadge.setVisibility(filter.active()||filteredPoints.isEmpty()?VISIBLE:GONE);
                renderPanel();updateMapInsets();
            });
        });
    }
    public void setDebugDataset(String name){
        if(!com.fiskentra.app.BuildConfig.DEBUG||disposed)return;
        final int request=++debugDatasetGeneration;++dataGeneration;
        closeOverlay();if("off".equals(name)){debugFixture=null;if(realFilter!=null)filter=realFilter;if(realMode!=null)mode=realMode;realFilter=null;realMode=null;map.endDebugCamera();dataRevision=-1;update(lastPosition,lastSignals);return;}
        if(!"standard".equals(name)&&!"stress".equals(name)&&!"layers".equals(name))return;
        dataWorker.execute(()->{try{com.fiskentra.app.debug.MapDebugFixture fixture=com.fiskentra.app.debug.MapDebugFixture.create(name);ui.post(()->{if(disposed||request!=debugDatasetGeneration)return;if(realFilter==null){realFilter=filter;realMode=mode;map.beginDebugCamera();}debugFixture=fixture;filter=MapFilterPolicy.all();dataRevision=-1;mode="Map";map.lookAt(fixture.centerLatitude,fixture.centerLongitude);if("layers".equals(name))map.showBounds(54.215,12.16,54.145,12.04);update(lastPosition,lastSignals);});}catch(IllegalArgumentException ignored){}});
    }
    public void debugZoomBy(double delta){if(com.fiskentra.app.BuildConfig.DEBUG&&Double.isFinite(delta))map.zoomBy(Math.max(-1,Math.min(1,delta)));}
    private boolean debugReadOnly(){if(debugFixture==null)return false;toast(activity.getString(R.string.map_debug_readonly));return true;}
    private void startBacktrack(String id,List<double[]> line){Runnable start=()->{closeOverlay();updateGps(lastPosition);backtrack.update(location);backtrack.start(id,System.currentTimeMillis(),line);};if(destination!=null||backtrack.active())new AlertDialog.Builder(activity).setTitle(R.string.backtrack_replace_title).setMessage(R.string.backtrack_replace_message).setNegativeButton(R.string.map_cancel,null).setPositiveButton(R.string.map_return,(d,w)->start.run()).show();else start.run();}
    private void savedBacktrack(){List<FishingDay> trips=new FishingDayStore(activity).all();String[] names=new String[trips.size()];for(int i=0;i<trips.size();i++)names[i]=java.text.DateFormat.getDateTimeInstance().format(new Date(trips.get(i).startedAt));new AlertDialog.Builder(activity).setTitle(R.string.map_return_saved).setItems(names,(d,w)->{FishingDay trip=trips.get(w);startBacktrack(Long.toString(trip.id),trip.route);}).setNegativeButton(R.string.map_cancel,null).show();}
    private boolean sameTrack(List<double[]> a,List<double[]> b){if(a==b)return true;if(a.size()!=b.size())return false;return a.isEmpty()||Arrays.equals(a.get(0),b.get(0))&&Arrays.equals(a.get(a.size()-1),b.get(b.size()-1));}
    private static long historySignature(List<FishingDay> trips){long hash=1;for(FishingDay day:trips){hash=31*hash+day.id;hash=31*hash+day.startedAt;hash=31*hash+day.endedAt;for(double[] point:day.route){hash=31*hash+point.length;for(double value:point)hash=31*hash+Double.doubleToLongBits(value);}}return hash;}
    private void updateSpeed(Location fix){
        if(fix==null)speedMeasurement.clear();
        double measured=fix!=null&&fix.hasSpeed()?fix.getSpeed():Double.NaN;
        double uncertainty=fix!=null&&fix.hasSpeedAccuracy()?fix.getSpeedAccuracyMetersPerSecond():Double.NaN;
        String value=speedMeasurement.update(measured,uncertainty,fix==null?0:fix.getElapsedRealtimeNanos(),android.os.SystemClock.elapsedRealtimeNanos(),imperial);
        String description = activity.getString(R.string.map_boat_speed, value);
        if (android.text.TextUtils.equals(speed.getContentDescription(), description)) return;
        SpannableString dialText = new SpannableString(value.replace(' ', '\n'));
        int unitStart = value.indexOf(' ') + 1;
        dialText.setSpan(new StyleSpan(Typeface.BOLD), 0, unitStart - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        dialText.setSpan(new RelativeSizeSpan(0.65f), unitStart, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        speed.setText(dialText);
        speed.setTextColor(value.startsWith("--") ? MUTED : INK);
        speed.setContentDescription(description);
    }
    private void updateGps(Location fix){
        boolean fine=activity.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean permission=fine||activity.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        android.location.LocationManager manager=activity.getSystemService(android.location.LocationManager.class);boolean enabled=manager!=null&&(android.os.Build.VERSION.SDK_INT>=28?manager.isLocationEnabled():manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)||manager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER));
        LocationQualityPolicy.State state=LocationQualityPolicy.state(permission,enabled,fix!=null,fix==null?0:fix.getElapsedRealtimeNanos(),android.os.SystemClock.elapsedRealtimeNanos());String label;
        if(state!=LocationQualityPolicy.State.FRESH)location=null;map.setPositionLive(state==LocationQualityPolicy.State.FRESH);
        switch(state){case PERMISSION_REQUIRED:label=activity.getString(R.string.map_gps_permission);break;case LOCATION_DISABLED:label=activity.getString(R.string.map_gps_disabled);break;case STALE:label=activity.getString(R.string.map_gps_stale,Math.max(0,(android.os.SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos())/1000000000L));break;case FRESH:label=fix.hasAccuracy()?activity.getString(R.string.map_gps_accuracy,MapUi.distance(fix.getAccuracy(),imperial)):activity.getString(R.string.map_gps_unknown);break;default:label=activity.getString(R.string.map_gps_locating);}
        String compactGps=state==LocationQualityPolicy.State.FRESH?(fix.hasAccuracy()?"\u00b1"+MapUi.distance(fix.getAccuracy(),imperial):"GPS"):state==LocationQualityPolicy.State.LOCATION_DISABLED?"GPS off":state==LocationQualityPolicy.State.PERMISSION_REQUIRED?"GPS!":"GPS...";
        setTextIfChanged(gps,compactGps);gps.setTextColor(state==LocationQualityPolicy.State.FRESH?MUTED:0xffffb53e);
        updateSpeed(state==LocationQualityPolicy.State.FRESH?fix:null);
        String signalDescription=lastSignals+". "+label;if(!android.text.TextUtils.equals(statusChip.getContentDescription(),signalDescription))statusChip.setContentDescription(signalDescription);
        String gpsNotice="";if(state!=LocationQualityPolicy.State.FRESH)gpsNotice=label;else if(!fine)gpsNotice=activity.getString(R.string.map_gps_approximate);else if(fix.hasAccuracy()&&fix.getAccuracy()>25)gpsNotice=activity.getString(R.string.map_gps_low,MapUi.distance(fix.getAccuracy(),imperial));
        String notice=map.getMapStatus();
        if(!gpsNotice.isEmpty())notice=notice.isEmpty()?gpsNotice:notice+"\n"+gpsNotice;
        setTextIfChanged(mapNotice,notice);mapNotice.setVisibility(notice.isEmpty()?GONE:VISIBLE);updateOfflineCoverage();
    }
    private void updateLocate(MapCameraPolicy.Mode value,boolean available){if(locateButton==null)return;locateButton.setImageResource(value==MapCameraPolicy.Mode.FOLLOW_HEADING?R.drawable.ic_navigation:R.drawable.ic_current_location);locateButton.setColorFilter(value==MapCameraPolicy.Mode.FREE?INK:BLUE);locateButton.setContentDescription(activity.getString(value==MapCameraPolicy.Mode.FREE?R.string.map_camera_free:value==MapCameraPolicy.Mode.FOLLOW_HEADING?R.string.map_camera_heading:available?R.string.map_camera_position:R.string.map_heading_unavailable));}
    private void showOverlay(String kind,View content){
        cancelHazardPlacement();
        if(sheet!=null)removeSheet(true);
        overlayMode=kind;overlayFocus=activity.getCurrentFocus();sheet=new MapSheet(activity,this::closeOverlay);sheet.content(content);
        if("SEARCH".equals(kind))sheet.setExpanded(true);
        FrameLayout.LayoutParams sheetLp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);sheetLp.topMargin=dp(148);stage.addView(sheet,sheetLp);
        sheet.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateMapInsets());lower.setVisibility(GONE);tools.setVisibility(GONE);backtrackDock.setVisibility(GONE);guidance.setVisibility(GONE);hud.compact(true);
        if("SEARCH".equals(kind)){
            searchInput.requestFocus();searchInput.post(()->{if(!disposed&&"SEARCH".equals(overlayMode))activity.getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(searchInput,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
        }else{hideSearchKeyboard();content.setFocusableInTouchMode(true);content.requestFocus();}
        updateMapInsets();
    }
    private void removeSheet(boolean restore){
        boolean wasSearch="SEARCH".equals(overlayMode);
        if(sheet!=null){stage.removeView(sheet);sheet=null;}pointSheet=null;overlayMode="NONE";hud.compact(false);
        if(wasSearch){searchInput.clearFocus();searchInput.setText("");searchInput.setHint("Search places");hideSearchKeyboard();requestFocus();}
        if(restore){selected=null;map.closeSelection();map.setPreview(null);}
        if(!wasSearch&&overlayFocus!=null&&overlayFocus!=searchInput)overlayFocus.requestFocus();overlayFocus=null;
        renderPanel();updateMapInsets();
    }
    private void closeOverlay(){cancelHazardPlacement();removeSheet(true);map.setTapToPlace(false);updateOfflineCoverage();}
    private void hideSearchKeyboard(){activity.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(searchInput.getWindowToken(),0);}
    public boolean handleBack(){
        if (placeHazardZone != null) { cancelHazardPlacement(); return true; }
        if(sheet==null)return false;
        View focus=activity.getCurrentFocus();
        if(focus instanceof EditText){focus.clearFocus();hideSearchKeyboard();if(focus==searchInput)closeOverlay();return true;}
        if(sheet.collapse())return true;closeOverlay();return true;
    }
    private void updateMapInsets(){
        if(disposed||stage==null||information==null||backtrackDock==null)return;
        int top=Math.max(dp(72),searchBar.getBottom()+dp(8));
        FrameLayout.LayoutParams infoLp=(FrameLayout.LayoutParams)information.getLayoutParams();if(infoLp.topMargin!=top){infoLp.topMargin=top;information.setLayoutParams(infoLp);}
        FrameLayout.LayoutParams compassLp=(FrameLayout.LayoutParams)compass.getLayoutParams();if(compassLp.topMargin!=top){compassLp.topMargin=top;compass.setLayoutParams(compassLp);}
        int bottom=sheet!=null?sheet.getHeight():backtrack!=null&&backtrack.active()?backtrackDock.getHeight():lower.getHeight();
        int gap = dp(8);
        int compassBottom = top + compassLp.height;
        int speedTop = compassBottom + gap;
        FrameLayout.LayoutParams speedLp = (FrameLayout.LayoutParams) speed.getLayoutParams();
        if (speedLp.topMargin != speedTop) {
            speedLp.topMargin = speedTop;
            speed.setLayoutParams(speedLp);
        }
        int speedBottom = speedTop + speedLp.height;
        boolean speedFits = speedBottom + gap <= stage.getHeight() - bottom;
        speed.setVisibility(speedFits ? VISIBLE : INVISIBLE);
        int windTop = speedBottom + gap;
        FrameLayout.LayoutParams windLp=(FrameLayout.LayoutParams)windHud.getLayoutParams();if(windLp.topMargin!=windTop){windLp.topMargin=windTop;windHud.setLayoutParams(windLp);}
        int toolsBottom = bottom + dp(12);
        int rightClearance = toolsBottom + tools.getMeasuredHeight() + gap;
        boolean windFits=com.fiskentra.app.model.WindLayerPolicy.indicatorFits(windTop,windHud.getHeight(),stage.getHeight(),rightClearance,sheet!=null);
        windHud.setVisibility(windFits?VISIBLE:INVISIBLE);
        int dialBottom = speedFits ? speedBottom : compassBottom;
        int safeTop=Math.max(windFits?windTop+windHud.getHeight():dialBottom,information.getBottom())+gap;
        if(sheet!=null){FrameLayout.LayoutParams sheetLp=(FrameLayout.LayoutParams)sheet.getLayoutParams();int minimumTop=top+dp(72);if(sheetLp.topMargin!=minimumTop){sheetLp.topMargin=minimumTop;sheet.setLayoutParams(sheetLp);}}
        // On short maps, leave the dials clear when the bottom buttons reach their height.
        int toolsEnd = stage.getHeight() - rightClearance < dialBottom ? dp(86) : dp(18);
        FrameLayout.LayoutParams toolLp = (FrameLayout.LayoutParams) tools.getLayoutParams();
        if (toolLp.bottomMargin != toolsBottom || toolLp.getMarginEnd() != toolsEnd) {
            toolLp.bottomMargin = toolsBottom;
            toolLp.setMarginEnd(toolsEnd);
            tools.setLayoutParams(toolLp);
        }
        tools.setVisibility(sheet==null?VISIBLE:GONE);map.setControlInsets(safeTop,bottom+dp(8));
    }
    private void mapTools(){
        new AlertDialog.Builder(activity).setTitle("Map tools").setItems(new String[]{"Point filters","Trip & recording","Trip details","Map workspace","Connections & GPS","Offline maps","Layer options & sources"},(d,w)->{
            if(w==0)filters();else if(w==1)trips();else if(w==2){mode=tracks.isActive()?"Track":"Trip";renderPanel();if(card.getParent() instanceof ViewGroup)((ViewGroup)card.getParent()).removeView(card);showOverlay("TRIP",card);}
            else if(w==3)modeMenu();else if(w==4)signalDetails();else if(w==5)offline();else layerOptions();
        }).show();
    }
    private void filters(){MapFilterPanel body=new MapFilterPanel(activity,filter,allPoints,currentTrip,new FishingDayStore(activity).all(),new MapFilterPanel.Actions(){public void apply(MapFilterPolicy value){applyFilter(value);}public void list(){visiblePoints();}});showOverlay("FILTERS",body);}
    private void applyFilter(MapFilterPolicy value){filter=value;if(debugFixture==null)prefs.edit().putString("filter_scope",value.scope).putString("filter_trip",value.tripId).putLong("filter_from",value.fromUtc).putLong("filter_to",value.toUtc).putStringSet("filter_types",value.types).putBoolean("filter_favorite",value.favorite).apply();closeOverlay();update(lastPosition,lastSignals);}
    private void visiblePoints(){
        LinearLayout body=MapUi.column(activity);body.addView(MapUi.text(activity,getResources().getQuantityString(R.plurals.map_visible_count,filteredPoints.size()-mapPoints.size(),mapPoints.size(),filteredPoints.size()-mapPoints.size()),18,true));
        if(filteredPoints.isEmpty())body.addView(MapUi.button(activity,R.string.map_filter_reset,()->{filter=MapFilterPolicy.all();filters();},true));
        List<SavedPoint> listing=filteredPoints;ArrayAdapter<SavedPoint> adapter=new ArrayAdapter<SavedPoint>(activity,android.R.layout.simple_list_item_1,listing){
            @Override public View getView(int position,View convert,android.view.ViewGroup parent){TextView row=(TextView)super.getView(position,convert,parent);SavedPoint p=getItem(position);String name=p.title.isEmpty()?p.type:p.title;row.setText(p.hasLocation()?name:activity.getString(R.string.map_ui_point_without_location,name,activity.getString(R.string.map_no_location)));row.setTextColor(INK);row.setMinHeight(dp(48));return row;}
        };
        ListView list=new ListView(activity);list.setAdapter(adapter);list.setOnItemClickListener((p,v,index,id)->pointMenu(listing.get(index)));body.addView(list,new LayoutParams(-1,dp(240)));showOverlay("FILTERS",body);
    }
    private void offline(){
        new AlertDialog.Builder(activity).setTitle("Offline maps")
                .setMessage("Google Maps area downloads are unavailable in Fiskentra. Saved points, GPS tracks and boat distance/bearing guidance are stored and work locally; the Google basemap may be unavailable without internet.")
                .setPositiveButton("Close",null).show();
    }
    private void updateOfflineCoverage(){map.clearCoverage();}

    private LinearLayout form() { LinearLayout layout = new LinearLayout(activity); layout.setOrientation(VERTICAL); layout.setPadding(dp(20), dp(8), dp(20), dp(8)); return layout; }
    private EditText input(LinearLayout form, String hint, String value) { EditText field = new EditText(activity); field.setHint(hint); field.setSingleLine(true); field.setText(value); form.addView(field); return field; }
    private Spinner spinner(LinearLayout form, String title, String[] items) {
        TextView label = new TextView(activity); label.setText(title); form.addView(label);
        Spinner spinner = new Spinner(activity); ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, items); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter); form.addView(spinner); return spinner;
    }
    private TextView text(String value, int size) { TextView text = new TextView(activity); text.setText(value); text.setTextColor(INK); text.setTextSize(size); text.setGravity(Gravity.CENTER_VERTICAL); text.setFontFeatureSettings("tnum"); return text; }
    private GradientDrawable surface(int color, int radius, int stroke) { GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius)); bg.setStroke(dp(1), stroke); return bg; }
    private LinearLayout row() { LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private View rule() { View v = new View(activity); v.setBackgroundColor(0xff235366); v.setLayoutParams(new LayoutParams(-1, dp(1))); return v; }
    private LinearLayout metric(TextView value, TextView label) { LinearLayout col = new LinearLayout(activity); col.setOrientation(VERTICAL); col.setGravity(Gravity.CENTER); value.setGravity(Gravity.CENTER); value.setTypeface(Typeface.DEFAULT_BOLD); value.setSingleLine(true); value.setAutoSizeTextTypeUniformWithConfiguration(14,23,1,android.util.TypedValue.COMPLEX_UNIT_SP); label.setTextColor(MUTED); label.setGravity(Gravity.CENTER); col.addView(value, new LayoutParams(-1, dp(32))); col.addView(label); return col; }
    private View iconButton(int resource, String label, Runnable action, boolean background) {
        ImageButton v = new ImageButton(activity); v.setImageResource(resource); v.setColorFilter(INK); v.setPadding(dp(10),dp(10),dp(10),dp(10)); v.setContentDescription(label);
        v.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x4400a7ff), background ? surface(NAVY, resource == R.drawable.ic_settings_adjust || resource == R.drawable.ic_current_location ? 30 : 9, LINE) : new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), null));
        v.setOnClickListener(w -> action.run()); return v;
    }
    private Button button(String label, Runnable action) { Button button = new Button(activity); button.setText(label); button.setTextSize(13); button.setTextColor(INK); button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x3300aed5), surface(NAVY,12,LINE), null)); button.setAllCaps(false); button.setMinHeight(0); button.setMinimumHeight(0); button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(dp(6), 0, dp(6), 0); button.setOnClickListener(v -> action.run()); return button; }
    private void setTextIfChanged(TextView view,CharSequence value){if(!android.text.TextUtils.equals(view.getText(),value))view.setText(value);}
    private ImageButton floatingButton(int resource,String label,Runnable action,boolean primary){
        ImageButton button=new ImageButton(activity);button.setImageResource(resource);button.setColorFilter(primary?Color.WHITE:INK);button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(primary?16:12),dp(primary?16:12),dp(primary?16:12),dp(primary?16:12));button.setContentDescription(label);button.setElevation(dp(4));
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x4400a7ff),surface(primary?BUTTON:NAVY,100,primary?BUTTON:LINE),null));button.setOnClickListener(v->action.run());return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
}
