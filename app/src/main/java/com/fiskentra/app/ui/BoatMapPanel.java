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
import com.fiskentra.app.data.*;
import com.fiskentra.app.location.*;
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
import java.util.concurrent.*;
import org.maplibre.android.geometry.LatLng;
import java.util.*;

/** Compact field workspace; dialogs contain secondary controls so the map stays usable. */
public final class BoatMapPanel extends LinearLayout implements SensorEventListener {
    public interface Actions {
        void saved(SavedPoint point);
        void details(SavedPoint point);void editDetails(SavedPoint point);void delete(SavedPoint point);void setLocation(SavedPoint point);
        void requestLocation();
        void toggleTrip(); void history(); void offline(); void export(); void navigationChanged(); void forecast();
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

    private final TextView status, gps, speed, mapNotice, navigation, tripInfo, title, subtitle, state, metricOne, metricTwo, metricThree;
    private final TextView metricOneLabel, metricTwoLabel, metricThreeLabel;
    private final LinearLayout card, thirdMetric, tools, guidance;
    private final Button primary, secondary;
    private final CompassDial compass;
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
    private static final int INK = 0xffeff4ff, MUTED = 0xffb7c9e6, NAVY = 0xff001e2e,
            BLUE = 0xff00a7ff, BUTTON = 0xff0059e8, LINE = 0xff53778f, AQUA = 0xff35dfd1;
    public BoatMapPanel(Activity activity, SavedPoint selected, Actions actions) {
        super(activity); this.activity = activity; this.actions = actions; this.selected = selected;
        points = new PointStore(activity); tracks = new TrackStore(activity);
        prefs = activity.getSharedPreferences("field_map", 0);
        // Previously selected walking/driving destinations resume with offline boat guidance.
        // Road routing code is retained elsewhere for a later release, with no active entrypoint.
        prefs.edit().putString("route_profile", "direct").apply();
        layers = new boolean[]{prefs.getBoolean("points", true), prefs.getBoolean("track", true), prefs.getBoolean("labels", true)};
        mode = prefs.getString("panel_mode", "Trip");
        imperial=new UserPreferences(activity).usesImperialUnits();compact=prefs.getBoolean("compact_hud",true);
        filter=new MapFilterPolicy(prefs.getString("filter_scope","all"),prefs.getString("filter_trip",""),prefs.getLong("filter_from",0),prefs.getLong("filter_to",Long.MAX_VALUE),prefs.getStringSet("filter_types",new HashSet<>(Arrays.asList(MapFilterPolicy.TYPES))),prefs.getBoolean("filter_favorite",false));
        setOrientation(VERTICAL); setBackgroundColor(NAVY); setFocusableInTouchMode(true);
        stage = new FrameLayout(activity); addView(stage, new LayoutParams(-1, 0, 1));
        map = new MapTilerMapView(activity); stage.addView(map, new FrameLayout.LayoutParams(-1, -1));
        map.setDirectGuidance(true);

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
        gps=text("GPS...",12);gps.setSingleLine(true);gps.setEllipsize(android.text.TextUtils.TruncateAt.END);gps.setTextColor(MUTED);signals.addView(gps,new LayoutParams(0,-2,1));
        View speedRule=rule();LayoutParams speedRuleLp=new LayoutParams(dp(1),dp(16));speedRuleLp.setMargins(dp(6),0,dp(6),0);signals.addView(speedRule,speedRuleLp);
        ImageView speedIcon=new ImageView(activity);speedIcon.setImageResource(R.drawable.ic_gauge);speedIcon.setColorFilter(BLUE);speedIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        signals.addView(speedIcon,new LayoutParams(dp(14),dp(14)));
        speed=text("-- "+BoatSpeedPolicy.unit(imperial),12);speed.setSingleLine(true);speed.setTypeface(Typeface.DEFAULT_BOLD);speed.setPadding(dp(4),0,0,0);signals.addView(speed,new LayoutParams(-2,-2));
        statusChip.addView(signals,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_VERTICAL|Gravity.START));information.addView(statusChip,new LayoutParams(-1,-2));
        mapNotice=text("Loading map.",12);mapNotice.setPadding(dp(10),dp(6),dp(10),dp(6));mapNotice.setMaxLines(2);mapNotice.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mapNotice.setBackground(surface(NAVY,12,LINE));mapNotice.setOnClickListener(v->signalDetails());information.addView(mapNotice,new LayoutParams(-1,-2));
        filterBadge=MapUi.text(activity,"",12,false);filterBadge.setBackground(surface(NAVY,12,LINE));filterBadge.setPadding(dp(8),dp(6),dp(8),dp(6));filterBadge.setOnClickListener(v->filters());filterBadge.setMinHeight(dp(40));filterBadge.setMaxLines(1);filterBadge.setEllipsize(android.text.TextUtils.TruncateAt.END);filterBadge.setVisibility(GONE);
        information.addView(filterBadge,new LayoutParams(-2,-2));
        tripInfo=text("",12);tripInfo.setBackground(surface(NAVY,12,LINE));tripInfo.setPadding(dp(8),dp(6),dp(8),dp(6));tripInfo.setMinHeight(dp(40));tripInfo.setOnClickListener(v->trips());tripInfo.setVisibility(GONE);information.addView(tripInfo,new LayoutParams(-2,-2));

        compass=new CompassDial(activity);compass.setOnClickListener(v->map.north());compass.setContentDescription("Compass heading. Tap for north-up map");
        FrameLayout.LayoutParams compassLp=new FrameLayout.LayoutParams(dp(64),dp(64),Gravity.TOP|Gravity.END);compassLp.setMargins(0,dp(76),dp(14),0);stage.addView(compass,compassLp);
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
        information.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateMapInsets());
        searchBar.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateMapInsets());
        backtrackDock.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateMapInsets());

        map.setLayers(layers[0],layers[1],layers[2]);
        map.setListener(new MapTilerMapView.Listener(){
            public void onMapStatusChanged(){post(()->{if(!disposed)updateGps(lastPosition);});}
            public void onPlace(double latitude,double longitude){map.setTapToPlace(false);place(latitude,longitude);}
            public void onPoint(SavedPoint point){pointMenu(point);}
            public void onCameraMode(MapCameraPolicy.Mode value,boolean available){updateLocate(value,available);}
            public void onCameraIdle(){updateOfflineCoverage();}
            public void onCluster(List<SavedPoint> group){
                String[] labels=new String[group.size()];java.text.SimpleDateFormat time=new java.text.SimpleDateFormat("dd MMM - HH:mm",Locale.getDefault());
                for(int i=0;i<group.size();i++){SavedPoint p=group.get(i);labels[i]=(p.title.isEmpty()?p.type:p.title)+" - "+time.format(new Date(p.timestamp));}
                new AlertDialog.Builder(activity).setTitle(group.size()+" points here").setItems(labels,(d,w)->pointMenu(group.get(w))).setNegativeButton("Close",null).show();
            }
        });
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
        subtitle.setText(navMode ? "Boat guidance - direct distance and bearing" : compact&&start>0?duration(seconds)+" · "+(trackMode?distance:activity.getString(R.string.map_catch_count,catches)):trackMode ? "Saved locally · " + (paused ? "recording paused" : "GPS track") : start > 0 ? new java.text.SimpleDateFormat("EEE, d MMM · HH:mm", Locale.getDefault()).format(new Date(start)) : "Your next fishing trip");
        metricOne.setText(trackMode ? distance : duration(seconds)); metricOneLabel.setText(trackMode ? "Distance" : "Duration");
        metricTwo.setText(trackMode ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) : Integer.toString(catches));
        metricTwoLabel.setText(trackMode ? "Trip elapsed" : "Catches");
        metricThree.setText(Integer.toString(count)); thirdMetric.setVisibility(trackMode || navMode ? GONE : VISIBLE);
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
    private void addMenu() {
        if(debugReadOnly())return;
        new AlertDialog.Builder(activity).setTitle("Add point").setItems(new String[]{"At my GPS position", "At map center", "Tap anywhere on map", "Enter coordinates"}, (d, which) -> {
            if (which == 0) { if (fresh()) edit(null, location.getLatitude(), location.getLongitude()); else { actions.requestLocation(); toast("Waiting for a fresh location"); } }
            if (which == 1) { LatLng center = map.center(); if (center != null) place(center.getLatitude(), center.getLongitude()); }
            if (which == 2) { map.setTapToPlace(true); toast("Tap map to place a point · long press also works"); }
            if (which == 3) coordinates();
        }).show();
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
                .setItems(new String[]{"Save point…", "Navigate here"}, (d, which) -> {
                    if (which == 0) edit(null, lat, lon); else navigate(new SavedPoint(-2, lat, lon, 0, "Waypoint", ""));
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
    private void pointMore(){if(selected==null)return;SavedPoint p=selected;new AlertDialog.Builder(activity).setItems(new String[]{activity.getString(R.string.map_set_location),activity.getString(R.string.map_retry_sync),activity.getString(R.string.map_delete)},(d,w)->{if(w==0)actions.setLocation(p);else if(w==1)actions.saved(p);else new AlertDialog.Builder(activity).setTitle(R.string.map_delete).setMessage(R.string.map_delete_confirm).setNegativeButton(R.string.map_cancel,null).setPositiveButton(R.string.map_delete,(q,n)->{closeOverlay();if(destination!=null&&destination.id==p.id){navigate(null);toast(activity.getString(R.string.map_target_deleted));}actions.delete(p);}).show();}).show();}
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
        CheckBox defaults = new CheckBox(activity); defaults.setText("Use this appearance for new points of this type (including Flic)"); form.addView(defaults);
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
        updateNavigationState();
        hud.profile(destination!=null,voiceEnabled);
        hud.status(navigation.getText().toString());
        if(destination!=null&&fresh()){
            double meters=FieldNavigation.distance(location.getLatitude(),location.getLongitude(),destination.latitude,destination.longitude);
            double bearing=FieldNavigation.bearing(location.getLatitude(),location.getLongitude(),destination.latitude,destination.longitude);
            hud.summary(nearDestination?"Destination reached":BoatArrivalPolicy.distanceLabel(meters,imperial)+" - "+Math.round((bearing+360)%360)+"\u00b0",destination.title);
        }else hud.summary(destination==null?"Choose destination":"Waiting for GPS",destination==null?"":destination.title);
        updateMapInsets();
    }
    private void toggleVoice() { voiceEnabled=!voiceEnabled;prefs.edit().putBoolean("navigation_voice",voiceEnabled).apply();voice.setEnabled(voiceEnabled);updateNavigation(); }
    private void updateNavigationState() {
        nearDestination=false;
        map.setNavigating(destination!=null);compass.setVisibility(VISIBLE);guidance.setVisibility(destination!=null&&sheet==null?VISIBLE:GONE);
        if(destination==null){compass.setTarget(Double.NaN);setTextIfChanged(navigation,"Choose a point on the water");return;}
        String name=destination.title.isEmpty()?"Destination":destination.title;
        if(!fresh()){setTextIfChanged(navigation,"Waiting for fresh GPS\n"+name);compass.setTarget(Double.NaN);return;}
        double meters=FieldNavigation.distance(location.getLatitude(),location.getLongitude(),destination.latitude,destination.longitude);
        double bearing=(FieldNavigation.bearing(location.getLatitude(),location.getLongitude(),destination.latitude,destination.longitude)+360)%360;
        nearDestination=arrivalPolicy.update(meters,location.hasAccuracy()?location.getAccuracy():Double.NaN);
        if(nearDestination&&!arrived){toast("Destination reached");voice.announce("You have reached the destination area");arrived=true;}
        String direction=!nearDestination&&Double.isFinite(heading)?" - turn "+Math.round(FieldNavigation.turn(bearing,heading))+"\u00b0":"";
        setTextIfChanged(navigation,(nearDestination?"Destination reached":BoatArrivalPolicy.distanceLabel(meters,imperial)+" to destination")+"\n"+name+direction);compass.setTarget(nearDestination?Double.NaN:bearing);
    }
    private void layers() {
        new AlertDialog.Builder(activity).setTitle("Map layers").setMultiChoiceItems(new String[]{"Saved points", "Trip track", "Point labels"}, layers, (d, index, checked) -> {
            layers[index] = checked; map.setLayers(layers[0], layers[1], layers[2]);
            prefs.edit().putBoolean("points", layers[0]).putBoolean("track", layers[1]).putBoolean("labels", layers[2]).apply();
        }).setNegativeButton("Tools",(d,w)->mapTools()).setNeutralButton("Base map", (d, w) -> new AlertDialog.Builder(activity).setTitle("Base map")
                .setItems(new String[]{"Normal", "Satellite", "Terrain", "Hybrid"}, (d2, which) -> map.setBaseStyle(new String[]{MapTilerMapView.STYLE_OUTDOOR, MapTilerMapView.STYLE_HYBRID, MapTilerMapView.STYLE_TOPO, MapTilerMapView.STYLE_OCEAN}[which])).show()).setPositiveButton("Done", null).show();
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
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); resumeSensors(); }
    @Override protected void onDetachedFromWindow() { disposed=true;dataGeneration++;dataWorker.shutdownNow();ui.removeCallbacksAndMessages(null);if(backtrack!=null)backtrack.close();pauseSensors(); voice.close(); super.onDetachedFromWindow(); }
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
            List<SavedPoint> events=com.fiskentra.app.model.TripEventPolicy.forTrack(source,new FishingDayStore(activity).all(),tracks.startedAt(),tracks.isActive()?Long.MAX_VALUE:tracks.stoppedAt());
            List<SavedPoint> filtered=selectedFilter.apply(source,trip);ArrayList<SavedPoint> placed=new ArrayList<>();for(SavedPoint p:filtered)if(p.hasLocation())placed.add(p);
            ui.post(()->{if(disposed||request!=dataGeneration)return;
                if(dataRevision!=snapshot.revision||appliedFilter!=selectedFilter||!appliedTrip.equals(trip)||allPoints!=source){dataRevision=snapshot.revision;allPoints=source;filteredPoints=filtered;mapPoints=Collections.unmodifiableList(placed);appliedFilter=selectedFilter;appliedTrip=trip;}
                currentTrip=trip;tripEvents=events;if(!sameTrack(trackSnapshot,line))trackSnapshot=line;trackDistance=distance;
                if(selected!=null){SavedPoint latest=fixture!=null?selected:points.find(selected.id);if(latest==null){closeOverlay();}else if(!selectedFilter.matches(latest,trip)){closeOverlay();toast(activity.getString(R.string.map_filter_hidden));}else selected=latest;}
                if(destination!=null&&destination.id>0&&points.find(destination.id)==null){navigate(null);toast(activity.getString(R.string.map_target_deleted));}
                if(destination!=null&&destination.id>0){SavedPoint latest=points.find(destination.id);if(latest!=null&&latest.hasLocation()){if(latest.latitude!=destination.latitude||latest.longitude!=destination.longitude)navigate(latest);else{destination=latest;map.setDestination(latest);}}}
                map.setData(lastPosition,mapPoints,trackSnapshot,selected);bindPoint();
                filterBadge.setText(activity.getString(R.string.map_visible_count,mapPoints.size(),filteredPoints.size()-mapPoints.size()));filterBadge.setVisibility(filter.active()||filteredPoints.isEmpty()?VISIBLE:GONE);
                renderPanel();updateMapInsets();
            });
        });
    }
    public void setDebugDataset(String name){
        if(!com.fiskentra.app.BuildConfig.DEBUG||disposed)return;
        final int request=++debugDatasetGeneration;++dataGeneration;
        closeOverlay();if("off".equals(name)){debugFixture=null;if(realFilter!=null)filter=realFilter;if(realMode!=null)mode=realMode;realFilter=null;realMode=null;map.endDebugCamera();dataRevision=-1;update(lastPosition,lastSignals);return;}
        if(!"standard".equals(name)&&!"stress".equals(name))return;
        dataWorker.execute(()->{try{com.fiskentra.app.debug.MapDebugFixture fixture=com.fiskentra.app.debug.MapDebugFixture.create(name);ui.post(()->{if(disposed||request!=debugDatasetGeneration)return;if(realFilter==null){realFilter=filter;realMode=mode;map.beginDebugCamera();}debugFixture=fixture;filter=MapFilterPolicy.all();dataRevision=-1;mode="Map";map.lookAt(fixture.centerLatitude,fixture.centerLongitude);update(lastPosition,lastSignals);});}catch(IllegalArgumentException ignored){}});
    }
    public void debugZoomBy(double delta){if(com.fiskentra.app.BuildConfig.DEBUG&&Double.isFinite(delta))map.zoomBy(Math.max(-1,Math.min(1,delta)));}
    private boolean debugReadOnly(){if(debugFixture==null)return false;toast(activity.getString(R.string.map_debug_readonly));return true;}
    private void startBacktrack(String id,List<double[]> line){Runnable start=()->{closeOverlay();updateGps(lastPosition);backtrack.update(location);backtrack.start(id,System.currentTimeMillis(),line);};if(destination!=null||backtrack.active())new AlertDialog.Builder(activity).setTitle(R.string.backtrack_replace_title).setMessage(R.string.backtrack_replace_message).setNegativeButton(R.string.map_cancel,null).setPositiveButton(R.string.map_return,(d,w)->start.run()).show();else start.run();}
    private void savedBacktrack(){List<FishingDay> trips=new FishingDayStore(activity).all();String[] names=new String[trips.size()];for(int i=0;i<trips.size();i++)names[i]=java.text.DateFormat.getDateTimeInstance().format(new Date(trips.get(i).startedAt));new AlertDialog.Builder(activity).setTitle(R.string.map_return_saved).setItems(names,(d,w)->{FishingDay trip=trips.get(w);startBacktrack(Long.toString(trip.id),trip.route);}).setNegativeButton(R.string.map_cancel,null).show();}
    private boolean sameTrack(List<double[]> a,List<double[]> b){if(a==b)return true;if(a.size()!=b.size())return false;return a.isEmpty()||Arrays.equals(a.get(0),b.get(0))&&Arrays.equals(a.get(a.size()-1),b.get(b.size()-1));}
    private void updateSpeed(Location fix){
        if(fix==null)speedMeasurement.clear();
        double measured=fix!=null&&fix.hasSpeed()?fix.getSpeed():Double.NaN;
        double uncertainty=fix!=null&&fix.hasSpeedAccuracy()?fix.getSpeedAccuracyMetersPerSecond():Double.NaN;
        String value=speedMeasurement.update(measured,uncertainty,fix==null?0:fix.getElapsedRealtimeNanos(),android.os.SystemClock.elapsedRealtimeNanos(),imperial);
        setTextIfChanged(speed,value);speed.setTextColor(value.startsWith("--")?MUTED:INK);
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
        String signalDescription=lastSignals+". "+label+". Speed "+speed.getText();if(!android.text.TextUtils.equals(statusChip.getContentDescription(),signalDescription))statusChip.setContentDescription(signalDescription);
        String notice=map.getMapStatus();if(state!=LocationQualityPolicy.State.FRESH)notice=label;else if(!fine)notice=activity.getString(R.string.map_gps_approximate);else if(fix.hasAccuracy()&&fix.getAccuracy()>25)notice=activity.getString(R.string.map_gps_low,MapUi.distance(fix.getAccuracy(),imperial));
        setTextIfChanged(mapNotice,notice);mapNotice.setVisibility(notice.isEmpty()?GONE:VISIBLE);updateOfflineCoverage();
    }
    private void updateLocate(MapCameraPolicy.Mode value,boolean available){if(locateButton==null)return;locateButton.setImageResource(value==MapCameraPolicy.Mode.FOLLOW_HEADING?R.drawable.ic_navigation:R.drawable.ic_current_location);locateButton.setColorFilter(value==MapCameraPolicy.Mode.FREE?INK:BLUE);locateButton.setContentDescription(activity.getString(value==MapCameraPolicy.Mode.FREE?R.string.map_camera_free:value==MapCameraPolicy.Mode.FOLLOW_HEADING?R.string.map_camera_heading:available?R.string.map_camera_position:R.string.map_heading_unavailable));}
    private void showOverlay(String kind,View content){
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
    private void closeOverlay(){removeSheet(true);map.setTapToPlace(false);updateOfflineCoverage();}
    private void hideSearchKeyboard(){activity.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(searchInput.getWindowToken(),0);}
    public boolean handleBack(){
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
        int safeTop=Math.max(top+dp(64),information.getBottom())+dp(8);
        int bottom=sheet!=null?sheet.getHeight():backtrack!=null&&backtrack.active()?backtrackDock.getHeight():lower.getHeight();
        if(sheet!=null){FrameLayout.LayoutParams sheetLp=(FrameLayout.LayoutParams)sheet.getLayoutParams();int minimumTop=top+dp(72);if(sheetLp.topMargin!=minimumTop){sheetLp.topMargin=minimumTop;sheet.setLayoutParams(sheetLp);}}
        FrameLayout.LayoutParams toolLp=(FrameLayout.LayoutParams)tools.getLayoutParams();int toolsBottom=bottom+dp(12);if(toolLp.bottomMargin!=toolsBottom){toolLp.bottomMargin=toolsBottom;tools.setLayoutParams(toolLp);}
        tools.setVisibility(sheet==null?VISIBLE:GONE);map.setControlInsets(safeTop,bottom+dp(8));
    }
    private void mapTools(){
        new AlertDialog.Builder(activity).setTitle("Map tools").setItems(new String[]{"Point filters","Trip & recording","Trip details","Trip events","Map workspace","Weather & forecast","Connections & GPS","Offline maps"},(d,w)->{
            if(w==0)filters();else if(w==1)trips();else if(w==2){mode=tracks.isActive()?"Track":"Trip";renderPanel();if(card.getParent() instanceof ViewGroup)((ViewGroup)card.getParent()).removeView(card);showOverlay("TRIP",card);}
            else if(w==3)events();else if(w==4)modeMenu();else if(w==5)actions.forecast();else if(w==6)signalDetails();else offline();
        }).show();
    }
    private void filters(){MapFilterPanel body=new MapFilterPanel(activity,filter,allPoints,currentTrip,new FishingDayStore(activity).all(),new MapFilterPanel.Actions(){public void apply(MapFilterPolicy value){applyFilter(value);}public void list(){visiblePoints();}});showOverlay("FILTERS",body);}
    private void applyFilter(MapFilterPolicy value){filter=value;if(debugFixture==null)prefs.edit().putString("filter_scope",value.scope).putString("filter_trip",value.tripId).putLong("filter_from",value.fromUtc).putLong("filter_to",value.toUtc).putStringSet("filter_types",value.types).putBoolean("filter_favorite",value.favorite).apply();closeOverlay();update(lastPosition,lastSignals);}
    private void visiblePoints(){
        LinearLayout body=MapUi.column(activity);body.addView(MapUi.text(activity,activity.getString(R.string.map_visible_count,mapPoints.size(),filteredPoints.size()-mapPoints.size()),18,true));
        if(filteredPoints.isEmpty())body.addView(MapUi.button(activity,R.string.map_filter_reset,()->{filter=MapFilterPolicy.all();filters();},true));
        List<SavedPoint> listing=filteredPoints;ArrayAdapter<SavedPoint> adapter=new ArrayAdapter<SavedPoint>(activity,android.R.layout.simple_list_item_1,listing){
            @Override public View getView(int position,View convert,android.view.ViewGroup parent){TextView row=(TextView)super.getView(position,convert,parent);SavedPoint p=getItem(position);row.setText((p.title.isEmpty()?p.type:p.title)+(p.hasLocation()?"":" · "+activity.getString(R.string.map_no_location)));row.setTextColor(INK);row.setMinHeight(dp(48));return row;}
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
