package com.fiskentra.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.fiskentra.app.backend.SupabaseConnection;
import com.fiskentra.app.backend.SupabaseAuthManager;
import com.fiskentra.app.backend.SupabaseConfig;
import com.fiskentra.app.backend.PointSyncQueue;
import com.fiskentra.app.data.FishingDayStore;
import com.fiskentra.app.data.PointStore;
import com.fiskentra.app.data.TrackStore;
import com.fiskentra.app.data.UserPreferences;
import com.fiskentra.app.flic.FiskentraFlic2Manager;
import com.fiskentra.app.location.FiskentraLocationManager;
import com.fiskentra.app.model.FishingDay;
import com.fiskentra.app.model.CatchDetails;
import com.fiskentra.app.model.ForecastDay;
import com.fiskentra.app.model.SavedPoint;
import com.fiskentra.app.model.WeatherForecast;
import com.fiskentra.app.model.WeatherSnapshot;
import com.fiskentra.app.service.FiskentraFlicService;
import com.fiskentra.app.ui.MapTilerMapView;
import com.fiskentra.app.ui.DesignScreens;
import com.fiskentra.app.ui.SwipeSwitchLayout;
import com.fiskentra.app.weather.FishingAdvisor;
import com.fiskentra.app.weather.WeatherClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity implements
        FiskentraLocationManager.Listener, FiskentraFlic2Manager.Listener {

    private static final int REQUEST_PERMISSIONS = 1001;
    private static final int REQUEST_CATCH_PHOTO = 1002;
    private static final int BG = Color.rgb(0, 21, 34);
    private static final int SURFACE = Color.rgb(6, 27, 43);
    private static final int SURFACE_2 = Color.rgb(10, 34, 53);
    private static final int BORDER = Color.rgb(52, 73, 88);
    private static final int TEXT = Color.rgb(244, 238, 232);
    private static final int MUTED = Color.rgb(184, 171, 166);
    private static final int ACCENT = Color.rgb(13, 124, 255);
    private static final int SUCCESS = Color.rgb(165, 214, 50);
    private static final int WARNING = Color.rgb(255, 176, 0);
    private static final int DANGER = Color.rgb(255, 69, 58);
    private static final String SYNC_PREFS = PointSyncQueue.PREFS;
    private static final String SYNC_SYNCING = PointSyncQueue.STATE_SYNCING;
    private static final String SYNC_SYNCED = PointSyncQueue.STATE_SYNCED;
    private static final String SYNC_FAILED = PointSyncQueue.STATE_FAILED;
    private static final String SYNC_DELETING = PointSyncQueue.STATE_DELETING;
    private static final String SYNC_DELETE_FAILED = PointSyncQueue.STATE_DELETE_FAILED;
    private static final String POINT_TYPE_CATCH = "Catch";
    private static final String POINT_TYPE_WAYPOINT = "Waypoint";
    private static final String POINT_TYPE_TACKLE_CHANGE = "Tackle change";
    private static final String WEATHER_PREFS = "fiskentra_weather_preferences";
    private static final String WEATHER_SPECIES = "selected_species";
    private static final String MAP_PREFS = "fiskentra_map_preferences";
    private static final String MAP_STYLE = "selected_map_style";

    private FrameLayout content;
    private DesignScreens design;
    private LinearLayout nav;
    private PointStore pointStore;
    private FishingDayStore fishingDayStore;
    private TrackStore trackStore;
    private FiskentraLocationManager locationManager;
    private FiskentraFlic2Manager flicManager;
    private SupabaseConnection supabaseConnection;
    private SupabaseAuthManager authManager;
    private PointSyncQueue syncQueue;
    private WeatherClient weatherClient;
    private SharedPreferences syncPrefs;
    private SharedPreferences weatherPrefs;
    private SharedPreferences mapPrefs;
    private UserPreferences userPreferences;
    private Location lastLocation;
    private String screen = "home";
    private String bleStatus = "No device connected";
    private String connectedDevice = "";
    private boolean flicConnected = false;
    private boolean cloudConnected = false;
    private String cloudStatus = "Checking Fiskentra cloud…";
    private String cloudSyncStatus = "Saved points sync after each new moment";
    private TextView deviceStatusText;
    private TextView buttonEventText;
    private String lastButtonEvent = "No button event yet";
    private long selectedMapPointId = -1L;
    private long selectedLogDateMillis;
    private FishingDay designSummaryDay;
    private long calendarMonthMillis;
    private MapTilerMapView activeMapView;
    private boolean showingWeatherPage;
    private boolean forecastLoading;
    private boolean forecastAttempted;
    private WeatherForecast weatherForecast;
    private String forecastStatus = "Open Weather to load the forecast";
    private String selectedSpecies = FishingAdvisor.SPECIES[0];
    private String selectedMapStyle = MapTilerMapView.STYLE_OUTDOOR;
    private boolean authBusy;
    private String authStatus = "Local mode · sign in is optional";
    private boolean authStatusError;
    private String authFormMode = "landing";
    private String authEmailDraft = "";
    private String authNameDraft = "";
    private int onboardingStep;
    private boolean onboardingReplay;
    private int flicSetupStep;
    private boolean flicSingleTested;
    private boolean flicDoubleTested;
    private boolean flicHoldTested;
    private boolean resumedForTests;
    private long pendingCatchPhotoPointId = -1L;
    private volatile boolean destroyed;
    private final PointSyncQueue.Observer syncObserver = (pointId, state, message, pending) -> {
        if (destroyed) return;
        runOnUiThread(() -> {
            if (destroyed) return;
            if (PointSyncQueue.STATE_SYNCED.equals(state)) {
                cloudSyncStatus = pending == 0
                        ? "All local points are synced to cloud"
                        : "Point synced · " + pending + " still queued";
            } else if (PointSyncQueue.STATE_SYNCING.equals(state)) {
                cloudSyncStatus = "Automatic sync in progress…";
            } else if (PointSyncQueue.STATE_FAILED.equals(state)) {
                cloudSyncStatus = "Saved locally · automatic retry queued";
            } else if (pointId < 0L && pending == 0) {
                cloudSyncStatus = "All local points are synced to cloud";
            }
            if ("home".equals(screen) || "saved".equals(screen) || "device".equals(screen)
                    || "beta".equals(screen)) {
                render(screen);
            }
        });
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);

        pointStore = new PointStore(this);
        fishingDayStore = new FishingDayStore(this);
        trackStore = new TrackStore(this);
        locationManager = new FiskentraLocationManager(this, this);
        supabaseConnection = new SupabaseConnection();
        authManager = new SupabaseAuthManager(this);
        syncQueue = PointSyncQueue.get(this);
        weatherClient = new WeatherClient(this);
        syncPrefs = getSharedPreferences(SYNC_PREFS, MODE_PRIVATE);
        weatherPrefs = getSharedPreferences(WEATHER_PREFS, MODE_PRIVATE);
        mapPrefs = getSharedPreferences(MAP_PREFS, MODE_PRIVATE);
        userPreferences = new UserPreferences(this);
        flicManager = ((FiskentraApplication) getApplication()).getFlicManager();
        selectedSpecies = weatherPrefs.getString(WEATHER_SPECIES, FishingAdvisor.SPECIES[0]);
        selectedMapStyle = MapTilerMapView.normalizeStyleId(
                mapPrefs.getString(MAP_STYLE, MapTilerMapView.STYLE_OUTDOOR));
        showingWeatherPage = userPreferences.defaultWeatherPage();
        selectedLogDateMillis = System.currentTimeMillis();
        calendarMonthMillis = firstDayOfMonth(selectedLogDateMillis);
        design = new DesignScreens(this, new DesignHost());

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        content = new FrameLayout(this);
        page.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(0, 0, 0, 0);
        nav.setBackground(roundRect(Color.rgb(3, 24, 38), BORDER, 1, 0));
        page.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        setContentView(page);

        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(),
                    0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        page.requestApplyInsets();

        boolean authRedirect = isAuthRedirect(getIntent());
        boolean showOnboarding = !authRedirect && userPreferences.shouldShowOnboarding(
                hasExistingLocalState());
        render(showOnboarding ? "onboarding" : "home");
        applyDebugUiOptions(getIntent());
        if (authRedirect) {
            userPreferences.completeOnboarding();
            handleAuthRedirect(getIntent());
        } else {
            authManager.restore((success, message) -> runOnUiThread(() -> {
                if (destroyed) return;
                authStatus = message;
                authStatusError = !success;
                if ("home".equals(screen) || "profile".equals(screen)) render(screen);
            }));
        }
        supabaseConnection.check((connected, message) -> runOnUiThread(() -> {
            cloudConnected = connected;
            cloudStatus = message;
            if ("home".equals(screen) || "beta".equals(screen)) render(screen);
        }));
        if (!showOnboarding) requestNeededPermissions();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyDebugUiOptions(intent);
        if (isAuthRedirect(intent)) handleAuthRedirect(intent);
    }
    private void applyDebugUiOptions(Intent intent) {
        if(!BuildConfig.DEBUG||intent==null)return;
        if(intent.hasExtra("qa_keep_awake")){
            if(intent.getBooleanExtra("qa_keep_awake",false))getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        String target=intent.getStringExtra("qa_route");
        if(target!=null&&java.util.Arrays.asList("home","map","mapTools","saved","log","device","tripSetup","forecastDetail","species").contains(target))render(target);
    }

    @Override protected void onStart() {
        super.onStart();
        flicManager.addListener(this);
        syncQueue.addObserver(syncObserver);
        syncQueue.retryPending();
        if (activeMapView != null) activeMapView.start();
    }

    @Override protected void onResume() {
        super.onResume();
        resumedForTests=true;
        updateFlicTestMode();
        if (activeMapView != null) activeMapView.resume();
        if (locationManager.hasPermission()) locationManager.start();
        ensureBackgroundService();
        if ("saved".equals(screen) || "log".equals(screen) || "beta".equals(screen)) {
            render(screen);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        resumedForTests=false;
        flicManager.setTestListener(null);
        if (activeMapView != null) activeMapView.pause();
        locationManager.stop();
    }

    @Override protected void onStop() {
        super.onStop();
        if (activeMapView != null) activeMapView.stop();
        flicManager.removeListener(this);
        syncQueue.removeObserver(syncObserver);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
        if (activeMapView != null) activeMapView.destroy();
        supabaseConnection.close();
        authManager.close();
        weatherClient.close();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (activeMapView != null) activeMapView.onLowMemory();
    }

    private void requestNeededPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }
    @Override public void onBackPressed() {
        switch(screen){
            case "home":super.onBackPressed();break;
            case "mapTools":case "tripActive":render("map");break;
            case "pointEdit":case "catchEdit":render("saved");break;
            case "tripSummary":render("log");break;
            case "profile":if(!authManager.isSignedIn()&&!"landing".equals(authFormMode)){openAuthForm("landing");break;}
            case "flicSetup":case "settings":case "beta":case "profileSetup":case "flicRequired":render("device");break;
            default:render("home");break;
        }
    }

    private boolean hasExistingLocalState() {
        if (!pointStore.all().isEmpty() || !fishingDayStore.all().isEmpty()
                || trackStore.isActive() || !trackStore.points().isEmpty()) return true;
        if (authManager.isSignedIn() || flicManager.pairedButtonCount() > 0) return true;
        if (mapPrefs.contains(MAP_STYLE) || weatherPrefs.contains(WEATHER_SPECIES)) return true;
        return !getSharedPreferences("fiskentra_cloud", MODE_PRIVATE).getAll().isEmpty();
    }

    private void ensureBackgroundService() {
        boolean trackNeeded = trackStore != null && trackStore.isActive();
        boolean buttonNeeded = flicManager != null && flicManager.pairedButtonCount() > 0;
        if (!trackNeeded && !buttonNeeded) return;
        if (!locationManager.hasPermission()) return;
        if (!trackNeeded && !flicManager.hasPermissions()) return;
        if (FiskentraFlicService.isRunning()) return;
        try {
            FiskentraFlicService.start(this);
        } catch (RuntimeException error) {
            bleStatus = "Background service could not start · reopen Fiskentra";
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && locationManager.hasPermission()) locationManager.start();
        if (requestCode == REQUEST_PERMISSIONS) {
            flicManager.attachAndConnectPairedButtons();
            ensureBackgroundService();
            render(screen);
        }
    }

    private void render(String next) {
        screen = next;
        updateFlicTestMode();
        content.removeAllViews();
        deviceStatusText = null;
        buttonEventText = null;
        activeMapView = null;
        content.addView(design.create(screen));
        renderNav();
    }

    private void renderNav() {
        nav.removeAllViews();
        if ("onboarding".equals(screen) || "flicSetup".equals(screen) || "profileSetup".equals(screen) || "flicRequired".equals(screen)
                || ("profile".equals(screen) && !authManager.isSignedIn())) {
            nav.setVisibility(View.GONE);
            return;
        }
        nav.setVisibility(View.VISIBLE);
        nav.addView(design.navigation(screen), new LinearLayout.LayoutParams(-1, -1));
    }

    private void updateFlicTestMode() {
        if (flicManager != null) flicManager.setTestListener(resumedForTests && "flicSetup".equals(screen) && flicSetupStep == 2 ? this : null);
    }
    @Override public void onTestAction(FiskentraFlic2Manager.Action action) {
        runOnUiThread(() -> {
            if (action == FiskentraFlic2Manager.Action.CATCH) flicSingleTested = true;
            if (action == FiskentraFlic2Manager.Action.WAYPOINT) flicDoubleTested = true;
            if (action == FiskentraFlic2Manager.Action.TACKLE_CHANGE) flicHoldTested = true;
            if ("flicSetup".equals(screen)) render(screen);
        });
    }
    private final class DesignHost implements DesignScreens.Host {
        public DesignScreens.State read() {
            DesignScreens.State s = new DesignScreens.State();
            s.points=pointStore.all();s.days=fishingDayStore.all();s.forecast=weatherForecast;
            s.location=lastLocation;s.selected=selectedMapPoint();s.species=selectedSpecies;s.device=connectedDevice;
            s.status=bleStatus;s.authMode=authFormMode;s.authStatus=authStatus;s.authError=authStatusError;s.busy=authBusy;
            s.email=authManager.session()==null?authEmailDraft:authManager.session().email;
            s.name=authManager.session()==null?authNameDraft:authManager.session().displayName;
            s.signedIn=authManager.isSignedIn();s.connected=flicConnected;s.bluetooth=flicManager.hasPermissions();
            s.gps=locationManager.hasPermission();s.notifications=Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
            android.location.LocationManager gpsManager=(android.location.LocationManager)getSystemService(LOCATION_SERVICE);
            s.gpsEnabled=gpsManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)||gpsManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
            s.active=trackStore.isActive();s.tripStarted=trackStore.startedAt();s.tripStopped=trackStore.stoppedAt();
            if("tripSummary".equals(screen)&&designSummaryDay!=null){s.tripStarted=designSummaryDay.startedAt;s.tripStopped=designSummaryDay.effectiveEnd(System.currentTimeMillis());s.active=designSummaryDay.isActive();}
            List<double[]> track=trackStore.points();float[] distance=new float[1];for(int i=1;i<track.size();i++){double[] p=track.get(i-1),q=track.get(i);if("tripSummary".equals(screen)&&(p[2]<s.tripStarted||(!s.active&&s.tripStopped>0&&q[2]>s.tripStopped)))continue;Location.distanceBetween(p[0],p[1],q[0],q[1],distance);s.distanceKm+=distance[0]/1000d;}
            s.pending=syncQueue.pendingCount();s.mapStyle=selectedMapStyle;s.imperial=userPreferences.usesImperialUnits();
            s.setupStep=flicSetupStep;s.singleTest=flicSingleTested;s.doubleTest=flicDoubleTested;s.holdTest=flicHoldTested;return s;
        }
        public void go(String target) {if(target.startsWith("auth:"))openAuthForm(target.substring(5));else render(target);}
        public void species(String value){selectedSpecies=value;weatherPrefs.edit().putString(WEATHER_SPECIES,value).apply();render(screen);}
        public void trip(FishingDay day){designSummaryDay=day;selectedLogDateMillis=day.startedAt;render("tripSummary");}
        public MapTilerMapView map(){MapTilerMapView map=new MapTilerMapView(MainActivity.this,selectedMapStyle,null);activeMapView=map;List<SavedPoint> points=new ArrayList<>(pointStore.all());List<double[]> track=new ArrayList<>(trackStore.points());if("tripSummary".equals(screen)){DesignScreens.State s=read();points.removeIf(p->p.timestamp<s.tripStarted||(!s.active&&s.tripStopped>0&&p.timestamp>s.tripStopped));track.removeIf(p->p[2]<s.tripStarted||(!s.active&&s.tripStopped>0&&p[2]>s.tripStopped));map.setData(null,points,track,null);}else map.setData(lastLocation,points,track,selectedMapPoint());return map;}
        public void command(String action){
            if(action.startsWith("save:")){saveCurrentMoment(action.substring(5));return;}
            if(action.startsWith("style:")){selectedMapStyle=MapTilerMapView.normalizeStyleId(action.substring(6));mapPrefs.edit().putString(MAP_STYLE,selectedMapStyle).apply();render(screen);return;}
            switch(action){
                case "refresh":loadForecast(true);break;
                case "sync":syncPendingPoints();break;
                case "startTrip":startPlannedTrip();break;
                case "finishTrip":finishActiveTrip();break;
                case "startDay":startFishingDay();break;
                case "pair":requestNeededPermissions();flicManager.pairNewButton();break;
                case "permissions":requestNeededPermissions();break;
                case "recenter":selectedMapPointId=-1;if(lastLocation!=null&&activeMapView!=null)activeMapView.recenter();else Toast.makeText(MainActivity.this,"Turn on Location and wait for a GPS fix",Toast.LENGTH_LONG).show();break;
                case "testFlic":flicSetupStep=2;flicSingleTested=false;flicDoubleTested=false;flicHoldTested=false;render("flicSetup");break;
                case "setupNext":if(flicSetupStep<3){flicSetupStep++;render("flicSetup");}else{userPreferences.completeOnboarding();render("home");}break;
                case "setupBack":flicSetupStep=Math.max(0,flicSetupStep-1);render("flicSetup");break;
                case "continueLocal":userPreferences.completeOnboarding();render("home");break;
                case "metric":case "imperial":userPreferences.setUsesImperialUnits(action.equals("imperial"));render(screen);break;
                case "editName":showDisplayNameDialog(read().name);break;
                case "signout":confirmSignOut();break;
                case "resend":EditText email=new EditText(MainActivity.this);email.setText(authEmailDraft);resendConfirmation(email);break;
                case "resendReset":authFormMode="recover";startAuthAction("Sending reset email…");authManager.requestPasswordReset(authEmailDraft,MainActivity.this::finishAuthAction);break;
                case "openEmail":try{startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,Intent.CATEGORY_APP_EMAIL));}catch(Exception e){Toast.makeText(MainActivity.this,"Open your email app to continue",Toast.LENGTH_LONG).show();}break;
            }
        }
        public void point(SavedPoint p,String action){switch(action){case "open":case "map":openPointOnMap(p);break;case "edit":design.edit(p);render("pointEdit");break;case "catchEdit":design.edit(p);render("catchEdit");break;case "photo":if(POINT_TYPE_CATCH.equals(p.type))chooseCatchPhoto(p);else comingSoon("Photos for this point type");break;case "weather":refreshPointWeather(p);break;case "delete":new AlertDialog.Builder(MainActivity.this).setTitle("Delete saved moment?").setMessage("This removes the point from this phone and queues its cloud deletion.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->deletePoint(p)).show();break;}}
        public void savePoint(SavedPoint p,String title,String type,String note){try{SavedPoint updated=pointStore.updateMetadata(p.id,title,type,note);if(updated!=null)syncPoint(updated);render("saved");}catch(IllegalArgumentException error){new AlertDialog.Builder(MainActivity.this).setTitle("Check point details").setMessage(error.getMessage()).setPositiveButton("OK",null).show();}}
        public void saveCatch(SavedPoint p,CatchDetails details){SavedPoint updated=pointStore.updateCatchDetails(p.id,details);if(updated!=null)syncPoint(updated);render("saved");}
        public String syncLabel(SavedPoint p){return MainActivity.this.syncLabel(p.id);}
        public int syncColor(SavedPoint p){return MainActivity.this.syncColor(p.id);}
        public void auth(boolean signup,boolean recover,EditText name,EditText email,EditText password,EditText confirm){submitAuthForm(signup,recover,name,email,password,confirm);}
        public View legacy(String target){if(target.equals("reset"))return passwordResetCard();if(target.equals("beta"))return betaScreen();return settingsScreen();}
    }

    private void addNav(int icon, String label, String target) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(4), 0, dp(4), 0);
        boolean selected = ("map".equals(target) && ("map".equals(screen) || "home".equals(screen)
                || "forecastDetail".equals(screen) || "species".equals(screen)
                || "tripSetup".equals(screen) || "tripActive".equals(screen)
                || "tripSummary".equals(screen) || "mapTools".equals(screen)))
                || screen.equals(target)
                || ("device".equals(target) && ("profile".equals(screen)
                || "settings".equals(screen) || "beta".equals(screen)));
        ImageView i = new ImageView(this);
        i.setImageResource(icon);
        i.setColorFilter(selected ? ACCENT : MUTED);
        i.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        i.setContentDescription(label);
        TextView l = text(label, 9, selected ? ACCENT : MUTED, Typeface.BOLD);
        l.setGravity(Gravity.CENTER);
        item.addView(i, new LinearLayout.LayoutParams(dp(25), dp(25)));
        item.addView(spacer(3));
        item.addView(l);
        item.setOnClickListener(v -> {
            if ("map".equals(target) && !"map".equals(screen)) {
                showingWeatherPage = userPreferences.defaultWeatherPage();
            }
            render(target);
        });
        nav.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private View todayForecastScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = vertical();
        body.setPadding(dp(16), dp(12), dp(16), dp(26));
        scroll.addView(body);

        body.addView(pageTitle("Today", "FISHING FORECAST · CURRENT LOCATION"));
        body.addView(spacer(12));

        LinearLayout tabs = row();
        TextView forecast = weatherTab("FORECAST", true);
        tabs.addView(forecast, new LinearLayout.LayoutParams(0, dp(40), 1f));
        TextView map = weatherTab("MAP", false);
        map.setOnClickListener(v -> { showingWeatherPage = false; render("map"); });
        tabs.addView(map, new LinearLayout.LayoutParams(0, dp(40), 1f));
        TextView toMap = weatherTab("TO MAP", false);
        toMap.setOnClickListener(v -> { showingWeatherPage = false; render("map"); });
        tabs.addView(toMap, new LinearLayout.LayoutParams(0, dp(40), 1f));
        body.addView(tabs, cardMargins());

        WeatherSnapshot current = weatherForecast == null ? null : weatherForecast.current;
        LinearLayout hero = card();
        LinearLayout heroRow = row();
        heroRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView pike = imageCard(R.drawable.pike_forecast_badge, "Pike forecast");
        heroRow.addView(pike, new LinearLayout.LayoutParams(dp(68), dp(68)));
        LinearLayout heroCopy = vertical();
        heroCopy.setPadding(dp(12), 0, 0, 0);
        heroCopy.addView(text(selectedSpecies, 22, TEXT, Typeface.BOLD));
        heroCopy.addView(text("Bite 4 of 5", 13, SUCCESS, Typeface.BOLD));
        heroCopy.addView(text("Excellent chance of a catch", 12, MUTED, Typeface.NORMAL));
        heroRow.addView(heroCopy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        heroRow.addView(text(current == null ? "12°C" : formatTemperature(current.temperatureC),
                26, TEXT, Typeface.BOLD));
        hero.addView(heroRow);
        hero.addView(spacer(12));
        LinearLayout metrics = row();
        metrics.addView(miniMetric("PRESSURE", current == null ? "758 mmHg"
                : Math.round(current.pressureHpa) + " hPa"), weighted());
        metrics.addView(miniMetric("WIND", current == null ? "SW 3 m/s"
                : current.windDirection() + " " + formatWind(current.windSpeedKmh)), weighted());
        metrics.addView(miniMetric("HUMIDITY", current == null ? "62%"
                : current.humidityPercent + "%"), weighted());
        metrics.addView(miniMetric("MOON", "63% waxing"), weighted());
        hero.addView(metrics);
        body.addView(hero, cardMargins());

        LinearLayout bite = card();
        bite.addView(text("BITE FORECAST", 11, MUTED, Typeface.BOLD));
        bite.addView(spacer(10));
        LinearLayout windows = row();
        windows.addView(biteWindow("09:00", "12°", "4 / 5", SUCCESS), weighted());
        windows.addView(biteWindow("12:00", "14°", "5 / 5", SUCCESS), weighted());
        windows.addView(biteWindow("15:00", "15°", "3 / 5", WARNING), weighted());
        windows.addView(biteWindow("18:00", "13°", "2 / 5", DANGER), weighted());
        bite.addView(windows);
        body.addView(bite, cardMargins());

        LinearLayout status = card();
        LinearLayout statusRow = row();
        statusRow.addView(statusPill(flicConnected ? "FLIC 2 CONNECTED" : "FLIC 2 OFFLINE",
                flicConnected ? SUCCESS : MUTED), weighted());
        statusRow.addView(statusPill(lastLocation == null ? "GPS WAITING" : "GPS ±"
                + Math.round(lastLocation.getAccuracy()) + " m", lastLocation == null ? WARNING : TEXT), weighted());
        statusRow.addView(statusPill("OFFLINE READY", ACCENT), weighted());
        status.addView(statusRow);
        body.addView(status, cardMargins());

        LinearLayout activity = card();
        LinearLayout activityHead = row();
        activityHead.addView(text("BITE ACTIVITY", 11, MUTED, Typeface.BOLD), weighted());
        activityHead.addView(text("NEXT 24 HOURS", 10, ACCENT, Typeface.BOLD));
        activity.addView(activityHead);
        activity.addView(spacer(12));
        LinearLayout activityBars = row();
        int[] values = {18, 26, 38, 62, 88, 72, 54, 76, 84, 60, 36, 24};
        for (int value : values) {
            LinearLayout column = vertical();
            column.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            View bar = new View(this);
            bar.setBackground(roundRect(value > 70 ? SUCCESS : ACCENT, 2));
            column.addView(bar, new LinearLayout.LayoutParams(dp(8), dp(value / 2)));
            activityBars.addView(column, new LinearLayout.LayoutParams(0, dp(50), 1f));
        }
        activity.addView(activityBars);
        activity.addView(text("NOW         06:00         12:00         18:00         00:00",
                9, MUTED, Typeface.NORMAL));
        body.addView(activity, cardMargins());

        LinearLayout forecastActions = row();
        Button details = smallButton("DETAILED FORECAST");
        details.setOnClickListener(v -> render("forecastDetail"));
        forecastActions.addView(details, new LinearLayout.LayoutParams(0, dp(46), 1f));
        forecastActions.addView(spaceWide());
        Button species = smallButton("SPECIES FORECAST");
        species.setOnClickListener(v -> render("species"));
        forecastActions.addView(species, new LinearLayout.LayoutParams(0, dp(46), 1f));
        body.addView(forecastActions, cardMargins());

        Button start = primaryButton(trackStore.isActive() ? "OPEN ACTIVE TRIP" : "START TRIP");
        start.setOnClickListener(v -> render(trackStore.isActive() ? "tripActive" : "tripSetup"));
        body.addView(start, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        if (weatherForecast == null && !forecastLoading && lastLocation != null) {
            content.post(() -> loadForecast(false));
        }
        return scroll;
    }

    private View miniMetric(String label, String value) {
        LinearLayout item = vertical();
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(4), dp(8), dp(4), dp(8));
        item.addView(centerText(label, 8, MUTED, Typeface.BOLD));
        item.addView(centerText(value, 10, TEXT, Typeface.BOLD));
        return item;
    }

    private View biteWindow(String time, String temperature, String score, int color) {
        LinearLayout item = vertical();
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(3), dp(8), dp(3), dp(8));
        item.addView(centerText(time, 11, ACCENT, Typeface.BOLD));
        item.addView(centerText(temperature, 13, TEXT, Typeface.BOLD));
        item.addView(centerText("Bite " + score, 10, color, Typeface.BOLD));
        return item;
    }

    private View statusPill(String label, int color) {
        TextView pill = centerText(label, 8, color, Typeface.BOLD);
        pill.setPadding(dp(3), dp(5), dp(3), dp(5));
        return pill;
    }

    private View forecastDetailScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(16), dp(12), dp(16), dp(26));
        scroll.addView(body);
        body.addView(pageTitle("Detailed forecast", "24 HOURS · CURRENT LOCATION"));
        body.addView(spacer(12));

        LinearLayout species = card();
        LinearLayout speciesRow = row();
        speciesRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView pike = imageCard(R.drawable.pike_forecast_badge, "Pike");
        speciesRow.addView(pike, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout copy = vertical();
        copy.setPadding(dp(10), 0, 0, 0);
        copy.addView(text(selectedSpecies, 19, TEXT, Typeface.BOLD));
        copy.addView(text("Bite activity forecast", 11, MUTED, Typeface.NORMAL));
        speciesRow.addView(copy, weighted());
        speciesRow.addView(text("24 HOURS", 10, ACCENT, Typeface.BOLD));
        species.addView(speciesRow);
        body.addView(species, cardMargins());

        LinearLayout chart = card();
        chart.addView(text("BITE ACTIVITY · 24 HOURS", 11, MUTED, Typeface.BOLD));
        chart.addView(spacer(12));
        int[] hours = {22, 34, 48, 42, 70, 92, 86, 62, 50, 76, 88, 54};
        LinearLayout bars = row();
        for (int value : hours) {
            LinearLayout column = vertical();
            column.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            View bar = new View(this);
            bar.setBackground(roundRect(value > 75 ? SUCCESS : ACCENT, 2));
            column.addView(bar, new LinearLayout.LayoutParams(dp(10), dp(value)));
            bars.addView(column, new LinearLayout.LayoutParams(0, dp(100), 1f));
        }
        chart.addView(bars);
        chart.addView(text("00:00      06:00      12:00      18:00      00:00",
                9, MUTED, Typeface.NORMAL));
        body.addView(chart, cardMargins());

        body.addView(sectionTitle("WEATHER BY HOUR"));
        LinearLayout hourly = row();
        String[] times = {"09:00", "12:00", "15:00", "18:00"};
        String[] temps = {"12°C", "14°C", "15°C", "13°C"};
        for (int i = 0; i < times.length; i++) {
            LinearLayout cell = vertical();
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4), dp(10), dp(4), dp(10));
            cell.setBackground(roundRect(SURFACE, BORDER, 1, 7));
            cell.addView(centerText(times[i], 10, ACCENT, Typeface.BOLD));
            cell.addView(centerText(temps[i], 15, TEXT, Typeface.BOLD));
            cell.addView(centerText("SW 3 m/s", 9, MUTED, Typeface.NORMAL));
            if (i > 0) hourly.addView(spaceWide());
            hourly.addView(cell, new LinearLayout.LayoutParams(0, dp(78), 1f));
        }
        body.addView(hourly, cardMargins());

        body.addView(sectionTitle("5-DAY FORECAST"));
        if (weatherForecast != null) {
            for (ForecastDay day : weatherForecast.days) body.addView(forecastDayCard(day), cardMargins());
        } else {
            body.addView(infoCard("FORECAST PENDING", "Connect to the internet and refresh the Today screen."), cardMargins());
        }
        LinearLayout actions = row();
        Button view = smallButton("SPECIES VIEW");
        view.setOnClickListener(v -> render("species"));
        actions.addView(view, new LinearLayout.LayoutParams(0, dp(48), 1f));
        actions.addView(spaceWide());
        Button back = primaryButton("BACK TO TODAY");
        back.setOnClickListener(v -> render("home"));
        actions.addView(back, new LinearLayout.LayoutParams(0, dp(48), 1f));
        body.addView(actions);
        return scroll;
    }

    private View speciesForecastScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(16), dp(12), dp(16), dp(26));
        scroll.addView(body);
        body.addView(pageTitle("Species forecast", "FIND THE BEST BITE WINDOW"));
        body.addView(spacer(12));
        EditText search = authField("Search species", InputType.TYPE_CLASS_TEXT);
        body.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        body.addView(spacer(12));

        LinearLayout selected = card();
        LinearLayout selectedRow = row();
        selectedRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView fish = imageCard(R.drawable.pike_forecast_badge, "Selected species Pike");
        selectedRow.addView(fish, new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout copy = vertical();
        copy.setPadding(dp(12), 0, 0, 0);
        copy.addView(text(selectedSpecies, 22, SUCCESS, Typeface.BOLD));
        copy.addView(text("Bite 4 of 5", 14, SUCCESS, Typeface.BOLD));
        copy.addView(text("Excellent chance of a catch", 12, MUTED, Typeface.NORMAL));
        selectedRow.addView(copy, weighted());
        selected.addView(selectedRow);
        body.addView(selected, cardMargins());

        body.addView(sectionTitle("OTHER SPECIES"));
        LinearLayout choices = row();
        String[] names = {"Perch", "Zander", "Carp"};
        int[] colors = {SUCCESS, WARNING, DANGER};
        for (int i = 0; i < names.length; i++) {
            TextView choice = centerText(names[i] + "\nBite " + (3 - i / 2) + " of 5",
                    11, colors[i], Typeface.BOLD);
            choice.setBackground(roundRect(SURFACE, BORDER, 1, 7));
            final String name = names[i];
            choice.setOnClickListener(v -> {
                selectedSpecies = name;
                weatherPrefs.edit().putString(WEATHER_SPECIES, name).apply();
                render("species");
            });
            if (i > 0) choices.addView(spaceWide());
            choices.addView(choice, new LinearLayout.LayoutParams(0, dp(62), 1f));
        }
        body.addView(choices, cardMargins());

        LinearLayout score = card();
        score.addView(text("BITE SCORE 4 OF 5", 11, SUCCESS, Typeface.BOLD));
        score.addView(spacer(8));
        score.addView(text("High activity", 21, TEXT, Typeface.BOLD));
        score.addView(text("Good conditions and stable weather create strong chances for a successful fishing trip.",
                13, MUTED, Typeface.NORMAL));
        score.addView(spacer(12));
        score.addView(text("BEST BITE WINDOWS", 10, MUTED, Typeface.BOLD));
        score.addView(text("11:30–14:00     ·     22:40–00:20", 15, TEXT, Typeface.BOLD));
        body.addView(score, cardMargins());

        LinearLayout recommendations = card();
        recommendations.addView(text("RECOMMENDATIONS", 11, MUTED, Typeface.BOLD));
        recommendations.addView(settingLine("Depth", "2–4 m"));
        recommendations.addView(settingLine("Lures", "Wobblers 10–14 cm, spoons"));
        recommendations.addView(settingLine("Water", "Light chop · medium clarity"));
        recommendations.addView(settingLine("Location", "Weed edges and depth changes"));
        body.addView(recommendations, cardMargins());

        Button add = primaryButton("ADD TO TRIP PLAN");
        add.setOnClickListener(v -> render("tripSetup"));
        body.addView(add, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        body.addView(spacer(10));
        Button compare = smallButton("COMPARE SPECIES · COMING SOON");
        compare.setOnClickListener(v -> comingSoon("Species comparison"));
        body.addView(compare, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return scroll;
    }

    private View homeScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(16), dp(20), dp(28));
        scroll.addView(body);

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView wordmark = new ImageView(this);
        wordmark.setImageResource(R.drawable.fiskentra_wordmark);
        wordmark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        wordmark.setContentDescription("Fiskentra");
        brand.addView(wordmark, new LinearLayout.LayoutParams(dp(188), dp(60)));
        TextView companion = text("OUTDOOR COMPANION", 10, MUTED, Typeface.BOLD);
        LinearLayout.LayoutParams companionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        companionLp.setMargins(dp(10), 0, 0, 0);
        brand.addView(companion, companionLp);
        body.addView(brand);
        body.addView(text("v" + BuildConfig.VERSION_NAME + " · CLOSED BETA", 10, ACCENT, Typeface.BOLD));

        TextView hello = text("Plan the water.\nRemember the moment.", 31, TEXT, Typeface.BOLD);
        hello.setLineSpacing(0f, 1.05f);
        LinearLayout.LayoutParams helloLp = matchWrap(); helloLp.setMargins(0, dp(22), 0, dp(18));
        body.addView(hello, helloLp);

        LinearLayout locationCard = card();
        LinearLayout top = row();
        TextView live = text(lastLocation == null ? "○  FINDING LOCATION" : "●  LOCATION READY", 11,
                lastLocation == null ? MUTED : ACCENT, Typeface.BOLD);
        top.addView(live, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(text(nowTime(), 12, MUTED, Typeface.NORMAL));
        locationCard.addView(top);
        if (lastLocation == null) {
            locationCard.addView(spacer(12));
            locationCard.addView(text(locationManager.hasPermission()
                    ? "Waiting for a GPS fix…" : "Location permission is needed to save moments.", 15, TEXT, Typeface.NORMAL));
        } else {
            locationCard.addView(spacer(10));
            locationCard.addView(text(formatCoords(lastLocation.getLatitude(), lastLocation.getLongitude()), 19, TEXT, Typeface.BOLD));
            locationCard.addView(text("Accuracy ±" + Math.round(lastLocation.getAccuracy()) + " m", 12, MUTED, Typeface.NORMAL));
        }
        body.addView(locationCard, cardMargins());

        FishingDay activeDay = fishingDayStore.active();
        LinearLayout dayCard = card();
        LinearLayout dayRow = row();
        dayRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout dayCopy = vertical();
        dayCopy.addView(text(activeDay == null ? "FISHING DAY" : "●  FISHING DAY ACTIVE", 11,
                activeDay == null ? MUTED : SUCCESS, Typeface.BOLD));
        if (activeDay == null) {
            dayCopy.addView(text("Start a log for catches and field events", 13, TEXT, Typeface.NORMAL));
        } else {
            DayStats activeStats = buildDayStats(fishingDayStore.sessionsOnDate(activeDay.startedAt));
            dayCopy.addView(text(formatDuration(System.currentTimeMillis() - activeDay.startedAt)
                    + " · " + activeStats.points.size() + " events", 13, TEXT, Typeface.NORMAL));
            if (activeStats.lastWeather != null) {
                dayCopy.addView(text(weatherSummary(activeStats.lastWeather),
                        11, MUTED, Typeface.NORMAL));
            }
        }
        dayRow.addView(dayCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button dayButton = smallButton(activeDay == null ? "START" : "OPEN");
        dayButton.setOnClickListener(v -> {
            if (activeDay == null) startFishingDay();
            else {
                selectedLogDateMillis = activeDay.startedAt;
                calendarMonthMillis = firstDayOfMonth(activeDay.startedAt);
                render("log");
            }
        });
        dayRow.addView(dayButton, new LinearLayout.LayoutParams(dp(84), dp(42)));
        dayCard.addView(dayRow);
        if (activeDay != null) {
            dayCard.addView(spacer(12));
            Button finish = smallButton("FINISH FISHING DAY");
            finish.setOnClickListener(v -> confirmFinishFishingDay());
            dayCard.addView(finish, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        }
        body.addView(dayCard, cardMargins());

        LinearLayout trip = card();
        LinearLayout tripRow = row();
        tripRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tripCopy = vertical();
        boolean tracking = trackStore.isActive();
        tripCopy.addView(text(tracking ? "●  TRIP RECORDING" : "TRIP TRACK", 11, tracking ? ACCENT : MUTED, Typeface.BOLD));
        tripCopy.addView(text(tracking ? trackStore.points().size() + " track points · screen-off active" : "Record your route with the screen locked", 13, TEXT, Typeface.NORMAL));
        WeatherSnapshot tripWeather = tracking ? trackStore.startWeather() : trackStore.endWeather();
        if (tripWeather != null) {
            tripCopy.addView(text((tracking ? "Start · " : "Finish · ") + weatherSummary(tripWeather),
                    11, MUTED, Typeface.NORMAL));
        }
        tripRow.addView(tripCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button tripButton = smallButton(tracking ? "STOP" : "START");
        tripButton.setOnClickListener(v -> {
            if (tracking) {
                long tripId = trackStore.startedAt();
                trackStore.stop();
                stopBackgroundServiceIfIdle();
                captureTripWeather(false, tripId);
            }
            else {
                trackStore.start();
                if (lastLocation != null) trackStore.add(lastLocation);
                ensureBackgroundService();
                captureTripWeather(true, trackStore.startedAt());
            }
            render("home");
        });
        tripRow.addView(tripButton, new LinearLayout.LayoutParams(dp(84), dp(42)));
        trip.addView(tripRow);
        body.addView(trip, cardMargins());

        LinearLayout logCard = card();
        TextView over = text("ONE-TAP MEMORY", 11, ACCENT, Typeface.BOLD);
        logCard.addView(over);
        logCard.addView(spacer(8));
        logCard.addView(text("Log this place", 25, TEXT, Typeface.BOLD));
        logCard.addView(text("Save your exact position now. Add the detail later.", 14, MUTED, Typeface.NORMAL));
        logCard.addView(spacer(18));
        Button save = primaryButton("＋  SAVE MOMENT");
        save.setOnClickListener(v -> saveCurrentMoment("Phone"));
        logCard.addView(save, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        body.addView(logCard, cardMargins());

        LinearLayout deviceCard = card();
        LinearLayout deviceHead = row();
        LinearLayout deviceCopy = vertical();
        deviceCopy.addView(text("FIELD BUTTON", 11, MUTED, Typeface.BOLD));
        deviceCopy.addView(text(connectedDevice.isEmpty() ? "Flic 2" : connectedDevice, 18, TEXT, Typeface.BOLD));
        deviceCopy.addView(text(bleStatus, 12, flicConnected ? ACCENT : MUTED, Typeface.NORMAL));
        deviceHead.addView(deviceCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button connect = smallButton(connectedDevice.isEmpty() ? "CONNECT" : "OPEN");
        connect.setOnClickListener(v -> render("device"));
        deviceHead.addView(connect, new LinearLayout.LayoutParams(dp(94), dp(42)));
        deviceCard.addView(deviceHead);
        body.addView(deviceCard, cardMargins());

        LinearLayout cloudCard = card();
        cloudCard.addView(text(cloudConnected ? "●  CLOUD CONNECTED" : "○  CLOUD", 11,
                cloudConnected ? ACCENT : MUTED, Typeface.BOLD));
        cloudCard.addView(spacer(6));
        cloudCard.addView(text(cloudStatus, 13, TEXT, Typeface.NORMAL));
        cloudCard.addView(text(cloudSyncStatus, 12, cloudSyncColor(), Typeface.NORMAL));
        cloudCard.addView(spacer(12));
        LinearLayout accountRow = row();
        accountRow.setGravity(Gravity.CENTER_VERTICAL);
        SupabaseAuthManager.Session account = authManager.session();
        LinearLayout accountCopy = vertical();
        accountCopy.addView(text(account == null ? "LOCAL MODE" : "FISKENTRA ACCOUNT", 10,
                account == null ? MUTED : SUCCESS, Typeface.BOLD));
        accountCopy.addView(text(account == null ? "Optional sign in" : account.displayName,
                12, TEXT, Typeface.NORMAL));
        accountRow.addView(accountCopy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button profile = smallButton(account == null ? "SIGN IN" : "PROFILE");
        profile.setOnClickListener(v -> render("profile"));
        accountRow.addView(profile, new LinearLayout.LayoutParams(dp(88), dp(40)));
        cloudCard.addView(accountRow);
        body.addView(cloudCard, cardMargins());

        body.addView(sectionTitle("QUICK LOG"));
        LinearLayout quick1 = row();
        quick1.addView(quickAction("◌", "Catch", "Fishing"), weighted());
        quick1.addView(spaceWide());
        quick1.addView(quickAction("◇", "Sighting", "Wildlife"), weighted());
        body.addView(quick1);
        body.addView(spacer(10));
        LinearLayout quick2 = row();
        quick2.addView(quickAction("△", "Camp", "Overnight"), weighted());
        quick2.addView(spaceWide());
        quick2.addView(quickAction("!", "Hazard", "Trail note"), weighted());
        body.addView(quick2);

        return scroll;
    }

    private View quickAction(String icon, String label, String subtitle) {
        LinearLayout item = vertical();
        item.setPadding(dp(15), dp(14), dp(12), dp(14));
        item.setBackground(roundRect(SURFACE, 16));
        item.addView(text(icon, 20, ACCENT, Typeface.BOLD));
        item.addView(spacer(6));
        item.addView(text(label, 15, TEXT, Typeface.BOLD));
        item.addView(text(subtitle, 11, MUTED, Typeface.NORMAL));
        item.setOnClickListener(v -> saveCurrentMoment(label));
        return item;
    }

    private View tripSetupScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(16), dp(12), dp(16), dp(26));
        scroll.addView(body);
        body.addView(pageTitle("Trip setup", "PLAN · CHECK · GO OFFLINE"));
        body.addView(spacer(14));

        LinearLayout plan = card();
        plan.addView(settingLine("Selected spot", "Current map area"));
        plan.addView(settingLine("Planned time", "Today · 06:30–15:30"));
        plan.addView(settingLine("Route", "32 km · 45 min"));
        plan.addView(settingLine("Offline map", "Ready · 120 MB"));
        plan.addView(settingLine("Forecast & risks", "Low risk · safe fishing conditions"));
        plan.addView(settingLine("Gear checklist", "7 of 9 items ready"));
        body.addView(plan, cardMargins());

        LinearLayout ready = card();
        ready.addView(text("FIELD READINESS", 11, MUTED, Typeface.BOLD));
        ready.addView(checkRow(flicConnected, "Flic 2 connected"));
        ready.addView(checkRow(lastLocation != null, "GPS and precise location"));
        ready.addView(checkRow(true, "Offline local storage ready"));
        ready.addView(checkRow(cloudConnected, "Cloud sync available"));
        body.addView(ready, cardMargins());

        LinearLayout options = card();
        options.addView(toggleLine("Voice guidance", false, "Coming soon"));
        options.addView(toggleLine("Automatic track recording", true, "Enabled"));
        options.addView(toggleLine("Hide exact location when sharing", true, "Private"));
        body.addView(options, cardMargins());

        Button start = primaryButton("START TRIP");
        start.setOnClickListener(v -> startPlannedTrip());
        body.addView(start, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        body.addView(spacer(10));
        Button route = smallButton("CHANGE ROUTE · COMING SOON");
        route.setOnClickListener(v -> comingSoon("Route planning"));
        body.addView(route, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return scroll;
    }

    private void startPlannedTrip() {
        if (!trackStore.isActive()) {
            trackStore.start();
            if (lastLocation != null) trackStore.add(lastLocation);
            ensureBackgroundService();
            captureTripWeather(true, trackStore.startedAt());
        }
        if (fishingDayStore.active() == null) {
            FishingDay day = fishingDayStore.start();
            selectedLogDateMillis = day.startedAt;
            calendarMonthMillis = firstDayOfMonth(day.startedAt);
            captureFishingDayWeather(day, true);
        }
        Toast.makeText(this, "Trip started · offline capture is ready", Toast.LENGTH_SHORT).show();
        render("tripActive");
    }

    private View tripActiveScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(12), dp(10), dp(12), dp(22));
        scroll.addView(body);
        body.addView(pageTitle("Trip active", "LIVE FIELD MODE"));
        body.addView(spacer(10));

        LinearLayout stats = card();
        LinearLayout statsRow = row();
        long started = trackStore.startedAt() > 0 ? trackStore.startedAt() : System.currentTimeMillis();
        statsRow.addView(miniMetric("TRIP", formatDuration(System.currentTimeMillis() - started)), weighted());
        statsRow.addView(miniMetric("DISTANCE", trackStore.points().size() + " points"), weighted());
        statsRow.addView(miniMetric("FLIC 2", flicConnected ? "Connected" : "Offline"), weighted());
        stats.addView(statsRow);
        stats.addView(checkRow(lastLocation != null, lastLocation == null
                ? "Waiting for GPS" : "GPS accuracy ±" + Math.round(lastLocation.getAccuracy()) + " m"));
        body.addView(stats, cardMargins());

        TextView mapStatus = text("Loading field map…", 10, MUTED, Typeface.NORMAL);
        body.addView(mapStatus);
        MapTilerMapView map = new MapTilerMapView(this, selectedMapStyle,
                new MapTilerMapView.StyleListener() {
                    @Override public void onStyleLoading(String styleId) { }
                    @Override public void onStyleLoaded(String styleId) {
                        mapStatus.post(() -> mapStatus.setText(layerReadyMessage(styleId)));
                    }
                    @Override public void onStyleError(String styleId) {
                        mapStatus.post(() -> {
                            mapStatus.setText("Map unavailable · field capture still works offline");
                            mapStatus.setTextColor(WARNING);
                        });
                    }
                });
        activeMapView = map;
        map.setData(lastLocation, pointStore.all(), trackStore.points(), null);
        body.addView(map, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(290)));
        body.addView(spacer(12));

        Button catchButton = fieldAction("CATCH", "1 PRESS", SUCCESS);
        catchButton.setOnClickListener(v -> saveCurrentMoment(POINT_TYPE_CATCH));
        body.addView(catchButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));
        body.addView(spacer(8));
        Button waypoint = fieldAction("WAYPOINT", "2 PRESSES", WARNING);
        waypoint.setOnClickListener(v -> saveCurrentMoment(POINT_TYPE_WAYPOINT));
        body.addView(waypoint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));
        body.addView(spacer(8));
        Button tackle = fieldAction("TACKLE CHANGE", "HOLD", Color.rgb(197, 155, 255));
        tackle.setOnClickListener(v -> saveCurrentMoment(POINT_TYPE_TACKLE_CHANGE));
        body.addView(tackle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));
        body.addView(spacer(10));

        LinearLayout controls = row();
        Button pause = smallButton("PAUSE");
        pause.setOnClickListener(v -> comingSoon("Trip pause"));
        controls.addView(pause, new LinearLayout.LayoutParams(0, dp(50), 1f));
        controls.addView(spaceWide());
        Button sos = dangerButton("SOS / SHARE");
        sos.setOnClickListener(v -> comingSoon("Safety and SOS sharing"));
        controls.addView(sos, new LinearLayout.LayoutParams(0, dp(50), 1.2f));
        controls.addView(spaceWide());
        Button finish = dangerButton("FINISH");
        finish.setOnClickListener(v -> finishActiveTrip());
        controls.addView(finish, new LinearLayout.LayoutParams(0, dp(50), 1f));
        body.addView(controls);
        return scroll;
    }

    private void finishActiveTrip() {
        designSummaryDay=null;
        long tripId = trackStore.startedAt();
        if (trackStore.isActive()) {
            trackStore.stop();
            stopBackgroundServiceIfIdle();
            captureTripWeather(false, tripId);
        }
        FishingDay day = fishingDayStore.active();
        if (day != null) {
            FishingDay finished = fishingDayStore.stop();
            if (finished != null) {
                selectedLogDateMillis = finished.startedAt;
                calendarMonthMillis = firstDayOfMonth(finished.startedAt);
                captureFishingDayWeather(finished, false);
            }
        }
        Toast.makeText(this, "Trip saved to your journal", Toast.LENGTH_SHORT).show();
        render("tripSummary");
    }

    private View tripSummaryScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(16), dp(12), dp(16), dp(26));
        scroll.addView(body);
        body.addView(pageTitle("Trip summary", formatLogDay(selectedLogDateMillis).toUpperCase(Locale.ROOT)));
        body.addView(spacer(12));

        List<FishingDay> sessions = fishingDayStore.sessionsOnDate(selectedLogDateMillis);
        DayStats day = buildDayStats(sessions);
        LinearLayout metrics = row();
        metrics.addView(miniMetric("DURATION", formatDuration(day.durationMs)), weighted());
        metrics.addView(miniMetric("EVENTS", String.valueOf(day.points.size())), weighted());
        metrics.addView(miniMetric("CATCHES", String.valueOf(day.catches)), weighted());
        metrics.addView(miniMetric("BEST BITE", "12–14"), weighted());
        body.addView(metrics, cardMargins());

        if (!trackStore.points().isEmpty()) {
            MapTilerMapView map = new MapTilerMapView(this, selectedMapStyle, null);
            map.setData(lastLocation, day.points, trackStore.points(), null);
            body.addView(map, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
            body.addView(spacer(12));
        }

        LinearLayout catchCard = card();
        catchCard.addView(text("CATCHES (" + day.catches + ")", 11, MUTED, Typeface.BOLD));
        if (day.catches == 0) {
            catchCard.addView(text("No catches were logged during this trip.",
                    13, TEXT, Typeface.NORMAL));
        } else {
            for (SavedPoint point : day.points) {
                if (!POINT_TYPE_CATCH.equals(point.type)) continue;
                catchCard.addView(settingLine(point.catchDetails == null
                        ? "Catch" : catchSummary(point.catchDetails), formatTime(point.timestamp)));
            }
        }
        body.addView(catchCard, cardMargins());

        LinearLayout charts = card();
        charts.addView(text("TRIP INSIGHTS", 11, MUTED, Typeface.BOLD));
        charts.addView(settingLine("Most active window", "12:00–14:00"));
        charts.addView(settingLine("Primary lure", "Not enough data"));
        charts.addView(settingLine("Weather", day.lastWeather == null ? "Not captured"
                : weatherSummary(day.lastWeather)));
        body.addView(charts, cardMargins());

        Button edit = primaryButton("OPEN JOURNAL");
        edit.setOnClickListener(v -> render("log"));
        body.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        body.addView(spacer(9));
        Button share = smallButton("SHARE REPORT · COMING SOON");
        share.setOnClickListener(v -> comingSoon("Trip report sharing"));
        body.addView(share, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        body.addView(spacer(9));
        Button export = smallButton("EXPORT GPX · COMING SOON");
        export.setOnClickListener(v -> comingSoon("GPX export"));
        body.addView(export, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return scroll;
    }

    private View mapToolsScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(16), dp(12), dp(16), dp(26));
        scroll.addView(body);
        body.addView(pageTitle("Map & layers", "OFFLINE MAP · FIELD TOOLS"));
        body.addView(spacer(12));
        body.addView(mapLayerSelector(), cardMargins());

        LinearLayout layers = card();
        layers.addView(toggleLine("Offline map", true, "Ready"));
        layers.addView(toggleLine("Depth overlay", false, "Coming soon"));
        layers.addView(toggleLine("Fishing zones", false, "Coming soon"));
        layers.addView(toggleLine("My saved points", true, pointStore.all().size() + " visible"));
        layers.addView(toggleLine("Recorded track", true, trackStore.points().size() + " points"));
        body.addView(layers, cardMargins());

        body.addView(sectionTitle("TOOLS"));
        LinearLayout tools1 = row();
        tools1.addView(toolButton("RULER", "Distance measure"), weighted());
        tools1.addView(spaceWide());
        tools1.addView(toolButton("COORDINATES", lastLocation == null ? "Waiting for GPS"
                : formatCoords(lastLocation.getLatitude(), lastLocation.getLongitude())), weighted());
        tools1.addView(spaceWide());
        tools1.addView(toolButton("IMPORT GPX", "Coming soon"), weighted());
        body.addView(tools1);
        body.addView(spacer(10));
        LinearLayout tools2 = row();
        tools2.addView(toolButton("EXPORT GPX", "Coming soon"), weighted());
        tools2.addView(spaceWide());
        tools2.addView(toolButton("DOWNLOAD AREA", "Coming soon"), weighted());
        tools2.addView(spaceWide());
        tools2.addView(toolButton("VOICE", "Coming soon"), weighted());
        body.addView(tools2, cardMargins());

        LinearLayout pack = card();
        pack.addView(text("OFFLINE PACK · 120 MB", 11, ACCENT, Typeface.BOLD));
        pack.addView(text("The app remains local-first. Downloadable MapTiler packs will be added later.",
                12, MUTED, Typeface.NORMAL));
        body.addView(pack, cardMargins());
        Button done = primaryButton("DONE");
        done.setOnClickListener(v -> { showingWeatherPage = false; render("map"); });
        body.addView(done, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return scroll;
    }

    private View flicSetupScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(18), dp(16), dp(18), dp(26));
        scroll.addView(body);

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("BACK", 11, MUTED, Typeface.BOLD);
        back.setPadding(0, dp(10), dp(14), dp(10));
        back.setOnClickListener(v -> render("device"));
        header.addView(back);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.fiskentra_wordmark);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(logo, new LinearLayout.LayoutParams(dp(150), dp(48)));
        body.addView(header);
        body.addView(text("Flic 2 setup  ·  Step " + (flicSetupStep + 1) + " of 4",
                12, TEXT, Typeface.BOLD));
        body.addView(spacer(8));
        body.addView(progressSegments(flicSetupStep, 4));
        body.addView(spacer(24));

        if (flicSetupStep == 0) {
            body.addView(text("Allow device access", 28, TEXT, Typeface.BOLD));
            body.addView(text("Fiskentra needs these permissions to save button presses with an accurate location.",
                    14, MUTED, Typeface.NORMAL));
            body.addView(spacer(18));
            body.addView(permissionCard("Nearby devices", "Required for Flic 2", flicManager.hasPermissions()), cardMargins());
            body.addView(permissionCard("Precise location", "Required for field events", locationManager.hasPermission()), cardMargins());
            body.addView(permissionCard("Notifications", "Required for background status", notificationPermissionReady()), cardMargins());
            Button allow = primaryButton("ALLOW REQUIRED ACCESS");
            allow.setOnClickListener(v -> requestNeededPermissions());
            body.addView(allow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        } else if (flicSetupStep == 1) {
            ImageView hero = imageCard(R.drawable.flic2_setup_hero, "Flic 2 button");
            body.addView(hero, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));
            body.addView(spacer(18));
            body.addView(text("Pair your Flic 2", 28, TEXT, Typeface.BOLD));
            body.addView(text("1. Keep Bluetooth on\n2. Hold the button for 6 seconds until it glows\n3. Keep it close to your phone",
                    14, MUTED, Typeface.NORMAL));
            body.addView(spacer(16));
            body.addView(infoCard(flicConnected ? "FLIC 2 CONNECTED" : "READY TO PAIR",
                    flicConnected ? bleStatus : "Fiskentra will open Android's Pair & connect dialog."), cardMargins());
            Button pair = primaryButton(flicConnected ? "CONTINUE" : "PAIR & CONNECT");
            pair.setOnClickListener(v -> {
                if (flicConnected) { flicSetupStep = 2; render("flicSetup"); }
                else if (!flicManager.hasPermissions()) requestNeededPermissions();
                else flicManager.pairNewButton();
            });
            body.addView(pair, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        } else if (flicSetupStep == 2) {
            body.addView(text(flicConnected ? "Flic 2 connected" : "Use the on-screen test",
                    13, flicConnected ? SUCCESS : WARNING, Typeface.BOLD));
            body.addView(text("Test all three actions", 28, TEXT, Typeface.BOLD));
            body.addView(text("Complete each action once. These on-screen tests do not create journal entries.",
                    14, MUTED, Typeface.NORMAL));
            body.addView(spacer(16));
            body.addView(flicTestAction("PRESS ONCE", "Log a catch", SUCCESS, flicSingleTested,
                    () -> { flicSingleTested = true; render("flicSetup"); }), cardMargins());
            body.addView(flicTestAction("PRESS TWICE", "Save a waypoint", WARNING, flicDoubleTested,
                    () -> { flicDoubleTested = true; render("flicSetup"); }), cardMargins());
            body.addView(flicTestAction("PRESS AND HOLD", "Record a tackle change",
                    Color.rgb(197, 155, 255), flicHoldTested,
                    () -> { flicHoldTested = true; render("flicSetup"); }), cardMargins());
            int passed = (flicSingleTested ? 1 : 0) + (flicDoubleTested ? 1 : 0) + (flicHoldTested ? 1 : 0);
            body.addView(text(passed + " of 3 tests passed", 13, TEXT, Typeface.BOLD));
        } else {
            ImageView hero = imageCard(R.drawable.flic2_setup_hero, "Flic 2 ready");
            body.addView(hero, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
            body.addView(spacer(16));
            body.addView(text("Your field button is ready", 28, TEXT, Typeface.BOLD));
            body.addView(text("Flic 2 is paired and every action is mapped for local-first capture.",
                    14, MUTED, Typeface.NORMAL));
            body.addView(spacer(12));
            body.addView(checkRow(flicConnected, "Flic 2 connected"));
            body.addView(checkRow(true, "Single press · Catch"));
            body.addView(checkRow(true, "Double press · Waypoint"));
            body.addView(checkRow(true, "Hold · Tackle change"));
            body.addView(checkRow(lastLocation != null, "GPS accuracy ready"));
            body.addView(checkRow(true, "Offline storage ready"));
        }

        body.addView(spacer(22));
        LinearLayout actions = row();
        if (flicSetupStep > 0) {
            Button previous = smallButton("BACK");
            previous.setOnClickListener(v -> { flicSetupStep--; render("flicSetup"); });
            actions.addView(previous, new LinearLayout.LayoutParams(0, dp(50), 1f));
            actions.addView(spaceWide());
        }
        boolean testsReady = flicSingleTested && flicDoubleTested && flicHoldTested;
        Button next = primaryButton(flicSetupStep == 3 ? "ENTER FISKENTRA"
                : (flicSetupStep == 2 ? (testsReady ? "CONTINUE" : "COMPLETE TESTS") : "CONTINUE"));
        next.setEnabled(flicSetupStep != 2 || testsReady);
        next.setOnClickListener(v -> {
            if (flicSetupStep < 3) { flicSetupStep++; render("flicSetup"); }
            else render("home");
        });
        actions.addView(next, new LinearLayout.LayoutParams(0, dp(50), flicSetupStep > 0 ? 1f : 2f));
        body.addView(actions);
        return scroll;
    }

    private View fishingLogScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(body);

        body.addView(pageTitle("Fishing log", "DAY JOURNAL · CALENDAR"));
        body.addView(spacer(18));

        FishingDay active = fishingDayStore.active();
        LinearLayout activeCard = card();
        activeCard.addView(text(active == null ? "○  NO ACTIVE DAY" : "●  FISHING DAY ACTIVE", 11,
                active == null ? MUTED : SUCCESS, Typeface.BOLD));
        activeCard.addView(spacer(7));
        if (active == null) {
            activeCard.addView(text("Start a fishing day to group Flic presses and saved places into one journal.",
                    13, TEXT, Typeface.NORMAL));
            activeCard.addView(spacer(14));
            Button start = primaryButton("START FISHING DAY");
            start.setOnClickListener(v -> startFishingDay());
            activeCard.addView(start, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        } else {
            DayStats activeStats = buildDayStats(fishingDayStore.sessionsOnDate(active.startedAt));
            activeCard.addView(text("Started " + formatTime(active.startedAt), 19, TEXT, Typeface.BOLD));
            activeCard.addView(text(formatDuration(System.currentTimeMillis() - active.startedAt)
                    + " · " + activeStats.points.size() + " logged events", 13, MUTED, Typeface.NORMAL));
            activeCard.addView(spacer(14));
            Button finish = smallButton("FINISH FISHING DAY");
            finish.setOnClickListener(v -> confirmFinishFishingDay());
            activeCard.addView(finish, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        }
        body.addView(activeCard, cardMargins());

        body.addView(sectionTitle("CALENDAR"));
        body.addView(calendarCard(), cardMargins());

        List<FishingDay> sessions = fishingDayStore.sessionsOnDate(selectedLogDateMillis);
        DayStats stats = buildDayStats(sessions);
        LinearLayout selectedHeader = row();
        selectedHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout selectedCopy = vertical();
        selectedCopy.addView(text(formatLogDay(selectedLogDateMillis), 20, TEXT, Typeface.BOLD));
        selectedCopy.addView(text(sessions.isEmpty()
                ? "No fishing day recorded" : sessions.size() + (sessions.size() == 1 ? " session" : " sessions"),
                12, MUTED, Typeface.NORMAL));
        selectedHeader.addView(selectedCopy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!isSameDay(selectedLogDateMillis, System.currentTimeMillis())) {
            Button today = smallButton("TODAY");
            today.setOnClickListener(v -> {
                selectedLogDateMillis = System.currentTimeMillis();
                calendarMonthMillis = firstDayOfMonth(selectedLogDateMillis);
                render("log");
            });
            selectedHeader.addView(today, new LinearLayout.LayoutParams(dp(76), dp(40)));
        }
        LinearLayout.LayoutParams selectedHeaderLp = matchWrap();
        selectedHeaderLp.setMargins(0, dp(16), 0, dp(12));
        body.addView(selectedHeader, selectedHeaderLp);

        if (sessions.isEmpty()) {
            LinearLayout empty = card();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(22), dp(28), dp(22), dp(28));
            TextView icon = text("▦", 35, ACCENT, Typeface.NORMAL);
            icon.setGravity(Gravity.CENTER);
            empty.addView(icon);
            empty.addView(spacer(8));
            TextView title = text("No journal for this day", 18, TEXT, Typeface.BOLD);
            title.setGravity(Gravity.CENTER);
            empty.addView(title);
            TextView copy = text("Choose a marked calendar day or start today's fishing day.",
                    12, MUTED, Typeface.NORMAL);
            copy.setGravity(Gravity.CENTER);
            empty.addView(copy);
            if (isSameDay(selectedLogDateMillis, System.currentTimeMillis()) && active == null) {
                empty.addView(spacer(14));
                Button start = primaryButton("START TODAY");
                start.setOnClickListener(v -> startFishingDay());
                empty.addView(start, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
            }
            body.addView(empty, cardMargins());
            return scroll;
        }

        LinearLayout metricsTop = row();
        metricsTop.addView(metricCard(String.valueOf(stats.catches), "Catches", SUCCESS), weighted());
        metricsTop.addView(spaceWide());
        metricsTop.addView(metricCard(String.valueOf(stats.waypoints), "Waypoints", WARNING), weighted());
        metricsTop.addView(spaceWide());
        metricsTop.addView(metricCard(String.valueOf(stats.tackleChanges), "Tackle", Color.rgb(197, 155, 255)), weighted());
        body.addView(metricsTop);
        body.addView(spacer(10));
        LinearLayout metricsBottom = row();
        metricsBottom.addView(metricCard(String.valueOf(stats.points.size()), "All events", ACCENT), weighted());
        metricsBottom.addView(spaceWide());
        metricsBottom.addView(metricCard(formatDuration(stats.durationMs), "Time fishing", TEXT), weighted());
        body.addView(metricsBottom);

        body.addView(sectionTitle("WEATHER"));
        LinearLayout weatherCard = card();
        if (stats.firstWeather == null) {
            weatherCard.addView(text("Weather was not captured for this fishing day.",
                    13, MUTED, Typeface.NORMAL));
            FishingDay latestSession = sessions.get(0);
            if (isSameDay(selectedLogDateMillis, System.currentTimeMillis())) {
                weatherCard.addView(spacer(12));
                Button addWeather = smallButton("ADD WEATHER NOW");
                addWeather.setOnClickListener(v -> captureFishingDayWeather(
                        latestSession, latestSession.startWeather == null));
                weatherCard.addView(addWeather, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
            }
        } else {
            weatherCard.addView(text("START · " + weatherSummary(stats.firstWeather),
                    14, TEXT, Typeface.BOLD));
            weatherCard.addView(text(weatherDetails(stats.firstWeather),
                    11, MUTED, Typeface.NORMAL));
            if (stats.lastWeather != null
                    && stats.lastWeather.observedAt != stats.firstWeather.observedAt) {
                weatherCard.addView(spacer(10));
                weatherCard.addView(text("LATEST · " + weatherSummary(stats.lastWeather),
                        14, TEXT, Typeface.BOLD));
                weatherCard.addView(text(weatherDetails(stats.lastWeather),
                        11, MUTED, Typeface.NORMAL));
            }
            weatherCard.addView(spacer(8));
            weatherCard.addView(text("Source: Open-Meteo · conditions are model data",
                    10, MUTED, Typeface.NORMAL));
        }
        body.addView(weatherCard, cardMargins());

        body.addView(sectionTitle("SESSIONS"));
        for (FishingDay session : sessions) {
            LinearLayout sessionCard = card();
            sessionCard.addView(text(session.isActive() ? "●  ACTIVE" : "FINISHED", 10,
                    session.isActive() ? SUCCESS : MUTED, Typeface.BOLD));
            sessionCard.addView(spacer(6));
            String range = formatTime(session.startedAt) + " — "
                    + (session.isActive() ? "now" : formatTime(session.endedAt));
            sessionCard.addView(text(range, 17, TEXT, Typeface.BOLD));
            sessionCard.addView(text(formatDuration(
                    session.effectiveEnd(System.currentTimeMillis()) - session.startedAt),
                    12, MUTED, Typeface.NORMAL));
            if (session.startWeather != null) {
                sessionCard.addView(text("Start · " + weatherSummary(session.startWeather),
                        11, MUTED, Typeface.NORMAL));
            }
            if (session.endWeather != null) {
                sessionCard.addView(text("Finish · " + weatherSummary(session.endWeather),
                        11, MUTED, Typeface.NORMAL));
            }
            sessionCard.addView(spacer(10));
            Button summary = smallButton(session.isActive() ? "OPEN ACTIVE TRIP" : "VIEW TRIP SUMMARY");
            summary.setOnClickListener(v -> {
                selectedLogDateMillis = session.startedAt;
                render(session.isActive() ? "tripActive" : "tripSummary");
            });
            sessionCard.addView(summary, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
            body.addView(sessionCard, cardMargins());
        }

        body.addView(sectionTitle("EVENTS"));
        if (stats.points.isEmpty()) {
            LinearLayout noEvents = card();
            noEvents.addView(text("No events recorded during this fishing day.",
                    13, MUTED, Typeface.NORMAL));
            body.addView(noEvents);
        } else {
            for (SavedPoint point : stats.points) {
                LinearLayout event = card();
                LinearLayout eventRow = row();
                eventRow.setGravity(Gravity.CENTER_VERTICAL);
                TextView marker = text(markerLetter(point.type), 11,
                        Color.rgb(7, 22, 12), Typeface.BOLD);
                marker.setGravity(Gravity.CENTER);
                GradientDrawable markerBg = new GradientDrawable();
                markerBg.setShape(GradientDrawable.OVAL);
                markerBg.setColor(markerColor(point.type));
                marker.setBackground(markerBg);
                eventRow.addView(marker, new LinearLayout.LayoutParams(dp(34), dp(34)));
                LinearLayout eventCopy = vertical();
                eventCopy.addView(text(point.type, 15, TEXT, Typeface.BOLD));
                eventCopy.addView(text(formatTime(point.timestamp) + " · "
                        + formatCoords(point.latitude, point.longitude), 11, MUTED, Typeface.NORMAL));
                if (point.catchDetails != null) {
                    eventCopy.addView(text(catchSummary(point.catchDetails),
                            11, SUCCESS, Typeface.BOLD));
                }
                if (point.weather != null) {
                    eventCopy.addView(text(weatherSummary(point.weather),
                            11, MUTED, Typeface.NORMAL));
                }
                LinearLayout.LayoutParams eventCopyLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                eventCopyLp.setMargins(dp(10), 0, 0, 0);
                eventRow.addView(eventCopy, eventCopyLp);
                event.setOnClickListener(v -> openPointOnMap(point));
                event.addView(eventRow);
                body.addView(event, cardMargins());
            }
        }
        return scroll;
    }

    private View calendarCard() {
        LinearLayout calendarCard = card();
        Calendar month = Calendar.getInstance();
        month.setTimeInMillis(calendarMonthMillis);

        LinearLayout monthHeader = row();
        monthHeader.setGravity(Gravity.CENTER_VERTICAL);
        Button previous = smallButton("‹");
        previous.setOnClickListener(v -> shiftCalendarMonth(-1));
        monthHeader.addView(previous, new LinearLayout.LayoutParams(dp(42), dp(38)));
        TextView monthTitle = text(new SimpleDateFormat("MMMM yyyy", Locale.US)
                .format(month.getTime()), 17, TEXT, Typeface.BOLD);
        monthTitle.setGravity(Gravity.CENTER);
        monthHeader.addView(monthTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button next = smallButton("›");
        next.setOnClickListener(v -> shiftCalendarMonth(1));
        monthHeader.addView(next, new LinearLayout.LayoutParams(dp(42), dp(38)));
        calendarCard.addView(monthHeader);
        calendarCard.addView(spacer(12));

        LinearLayout weekdayRow = row();
        String[] weekdays = {"M", "T", "W", "T", "F", "S", "S"};
        for (String weekday : weekdays) {
            TextView label = text(weekday, 10, MUTED, Typeface.BOLD);
            label.setGravity(Gravity.CENTER);
            weekdayRow.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        calendarCard.addView(weekdayRow);
        calendarCard.addView(spacer(6));

        int firstWeekday = (month.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int maxDay = month.getActualMaximum(Calendar.DAY_OF_MONTH);
        List<FishingDay> allSessions = fishingDayStore.all();
        int day = 1;
        for (int week = 0; week < 6; week++) {
            LinearLayout weekRow = row();
            for (int weekday = 0; weekday < 7; weekday++) {
                int slot = week * 7 + weekday;
                if (slot < firstWeekday || day > maxDay) {
                    weekRow.addView(new View(this), new LinearLayout.LayoutParams(0, dp(54), 1f));
                    continue;
                }

                Calendar date = (Calendar) month.clone();
                date.set(Calendar.DAY_OF_MONTH, day);
                long dateMillis = date.getTimeInMillis();
                boolean selected = isSameDay(dateMillis, selectedLogDateMillis);
                boolean hasLog = hasSessionOnDate(allSessions, dateMillis);
                WeatherSnapshot dayWeather = weatherForDate(allSessions, dateMillis);
                String cellLabel = String.valueOf(day);
                if (dayWeather != null) {
                    cellLabel += "\n" + dayWeather.symbol() + " "
                            + formatTemperature(dayWeather.temperatureC);
                } else if (hasLog) {
                    cellLabel += " •";
                }
                TextView cell = text(cellLabel, dayWeather == null ? 12 : 10,
                        selected ? Color.WHITE : (hasLog ? TEXT : MUTED),
                        hasLog || selected ? Typeface.BOLD : Typeface.NORMAL);
                cell.setGravity(Gravity.CENTER);
                cell.setBackground(roundRect(selected ? ACCENT : (hasLog ? SURFACE_2 : SURFACE), 10));
                cell.setOnClickListener(v -> {
                    selectedLogDateMillis = dateMillis;
                    render("log");
                });
                weekRow.addView(cell, new LinearLayout.LayoutParams(0, dp(54), 1f));
                day++;
            }
            calendarCard.addView(weekRow);
            if (day > maxDay) break;
        }
        calendarCard.addView(spacer(8));
        calendarCard.addView(text("• Journal day · weather icon and temperature are saved observations",
                10, MUTED, Typeface.NORMAL));
        return calendarCard;
    }

    private View metricCard(String value, String label, int color) {
        LinearLayout metric = vertical();
        metric.setPadding(dp(12), dp(13), dp(10), dp(13));
        metric.setBackground(roundRect(SURFACE, 14));
        metric.addView(text(value, 20, color, Typeface.BOLD));
        metric.addView(text(label, 10, MUTED, Typeface.NORMAL));
        return metric;
    }

    private void startFishingDay() {
        FishingDay day = fishingDayStore.start();
        selectedLogDateMillis = day.startedAt;
        calendarMonthMillis = firstDayOfMonth(day.startedAt);
        Toast.makeText(this, "Fishing day started", Toast.LENGTH_SHORT).show();
        captureFishingDayWeather(day, true);
        render("log");
    }

    private void confirmFinishFishingDay() {
        FishingDay active = fishingDayStore.active();
        if (active == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Finish fishing day?")
                .setMessage("The session summary and all events will remain in your calendar.")
                .setNegativeButton("Keep fishing", null)
                .setPositiveButton("Finish", (dialog, which) -> {
                    FishingDay finished = fishingDayStore.stop();
                    if (finished != null) {
                        selectedLogDateMillis = finished.startedAt;
                        calendarMonthMillis = firstDayOfMonth(finished.startedAt);
                        captureFishingDayWeather(finished, false);
                    }
                    Toast.makeText(this, "Fishing day finished", Toast.LENGTH_SHORT).show();
                    render("log");
                })
                .show();
    }

    private void shiftCalendarMonth(int amount) {
        Calendar month = Calendar.getInstance();
        month.setTimeInMillis(calendarMonthMillis);
        month.add(Calendar.MONTH, amount);
        calendarMonthMillis = firstDayOfMonth(month.getTimeInMillis());
        selectedLogDateMillis = calendarMonthMillis;
        render("log");
    }

    private DayStats buildDayStats(List<FishingDay> sessions) {
        DayStats stats = new DayStats();
        long now = System.currentTimeMillis();
        for (FishingDay session : sessions) {
            stats.durationMs += Math.max(0L, session.effectiveEnd(now) - session.startedAt);
            includeWeather(stats, session.startWeather);
            includeWeather(stats, session.endWeather);
        }

        Set<Long> included = new HashSet<>();
        for (SavedPoint point : pointStore.all()) {
            for (FishingDay session : sessions) {
                if (point.timestamp >= session.startedAt
                        && point.timestamp <= session.effectiveEnd(now)
                        && included.add(point.id)) {
                    stats.points.add(point);
                    if (POINT_TYPE_CATCH.equals(point.type)) stats.catches++;
                    else if (POINT_TYPE_WAYPOINT.equals(point.type)) stats.waypoints++;
                    else if (POINT_TYPE_TACKLE_CHANGE.equals(point.type)) stats.tackleChanges++;
                    includeWeather(stats, point.weather);
                    break;
                }
            }
        }
        return stats;
    }

    private static void includeWeather(DayStats stats, WeatherSnapshot weather) {
        if (weather == null) return;
        if (stats.firstWeather == null || weather.observedAt < stats.firstWeather.observedAt) {
            stats.firstWeather = weather;
        }
        if (stats.lastWeather == null || weather.observedAt > stats.lastWeather.observedAt) {
            stats.lastWeather = weather;
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CATCH_PHOTO) return;
        long pointId = pendingCatchPhotoPointId;
        pendingCatchPhotoPointId = -1L;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || pointId < 0L) return;
        saveSelectedCatchPhoto(pointId, data.getData());
    }

    private static boolean hasSessionOnDate(List<FishingDay> sessions, long date) {
        for (FishingDay session : sessions) {
            if (isSameDay(session.startedAt, date)) return true;
        }
        return false;
    }

    private static WeatherSnapshot weatherForDate(List<FishingDay> sessions, long date) {
        WeatherSnapshot result = null;
        for (FishingDay session : sessions) {
            if (!isSameDay(session.startedAt, date)) continue;
            if (session.startWeather != null
                    && (result == null || session.startWeather.observedAt < result.observedAt)) {
                result = session.startWeather;
            }
            if (result == null && session.endWeather != null) result = session.endWeather;
        }
        return result;
    }

    private static boolean isSameDay(long first, long second) {
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        a.setTimeInMillis(first);
        b.setTimeInMillis(second);
        return a.get(Calendar.ERA) == b.get(Calendar.ERA)
                && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static long firstDayOfMonth(long time) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 12);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static final class DayStats {
        final List<SavedPoint> points = new ArrayList<>();
        long durationMs;
        int catches;
        int waypoints;
        int tackleChanges;
        WeatherSnapshot firstWeather;
        WeatherSnapshot lastWeather;
    }

    private View mapScreen() {
        SwipeSwitchLayout swipe = new SwipeSwitchLayout(this);
        swipe.configure(!showingWeatherPage, new SwipeSwitchLayout.Listener() {
            @Override public void onSwipeLeft() {
                if (!showingWeatherPage) switchMapWeather(true);
            }

            @Override public void onSwipeRight() {
                if (showingWeatherPage) switchMapWeather(false);
            }
        });

        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(20), dp(20), dp(20));
        SavedPoint selected = selectedMapPoint();
        String eyebrow = showingWeatherPage ? "WEATHER · FISHING OUTLOOK"
                : selected == null ? "YOUR FIELD MAP" : "SELECTED SAVED POINT";
        body.addView(pageTitle("Explore", eyebrow));
        body.addView(mapWeatherTabs(), cardMargins());
        View page = showingWeatherPage ? weatherPage() : fieldMapPage();
        body.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        swipe.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (showingWeatherPage && weatherForecast == null && !forecastLoading && !forecastAttempted
                && lastLocation != null) {
            content.post(() -> loadForecast(false));
        }
        return swipe;
    }

    private View mapWeatherTabs() {
        LinearLayout tabs = row();
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(roundRect(SURFACE, 14));
        TextView mapTab = weatherTab("MAP", !showingWeatherPage);
        mapTab.setOnClickListener(v -> switchMapWeather(false));
        tabs.addView(mapTab, new LinearLayout.LayoutParams(0, dp(42), 1f));
        TextView weatherTab = weatherTab("WEATHER", showingWeatherPage);
        weatherTab.setOnClickListener(v -> switchMapWeather(true));
        tabs.addView(weatherTab, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams tabsLp = matchWrap();
        tabsLp.setMargins(0, dp(14), 0, dp(12));
        tabs.setLayoutParams(tabsLp);
        return tabs;
    }

    private TextView weatherTab(String label, boolean selected) {
        TextView tab = text(label, 11, selected ? Color.WHITE : MUTED, Typeface.BOLD);
        tab.setGravity(Gravity.CENTER);
        tab.setBackground(roundRect(selected ? ACCENT : SURFACE, BORDER, 1, 7));
        return tab;
    }

    private void switchMapWeather(boolean weather) {
        if (showingWeatherPage == weather) return;
        showingWeatherPage = weather;
        render("map");
        if (weather && lastLocation != null) content.post(() -> loadForecast(false));
    }

    private View fieldMapPage() {
        LinearLayout body = vertical();
        SavedPoint selected = selectedMapPoint();
        List<SavedPoint> points = pointStore.all();
        body.addView(mapLayerSelector(), cardMargins());

        TextView layerStatus = text(
                "Loading " + MapTilerMapView.styleName(selectedMapStyle) + " layer…",
                10, MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams statusLp = matchWrap();
        statusLp.setMargins(dp(2), 0, dp(2), dp(8));
        body.addView(layerStatus, statusLp);

        MapTilerMapView map = new MapTilerMapView(this, selectedMapStyle,
                new MapTilerMapView.StyleListener() {
                    @Override public void onStyleLoading(String styleId) {
                        layerStatus.post(() -> {
                            layerStatus.setTextColor(MUTED);
                            layerStatus.setText(
                                    "Loading " + MapTilerMapView.styleName(styleId) + " layer…");
                        });
                    }

                    @Override public void onStyleLoaded(String styleId) {
                        layerStatus.post(() -> {
                            layerStatus.setTextColor(MUTED);
                            layerStatus.setText(layerReadyMessage(styleId));
                        });
                    }

                    @Override public void onStyleError(String styleId) {
                        layerStatus.post(() -> {
                            layerStatus.setText("Could not load " + MapTilerMapView.styleName(styleId)
                                    + " · check internet or choose another layer");
                            layerStatus.setTextColor(DANGER);
                        });
                    }
                });
        activeMapView = map;
        map.setBackground(roundRect(SURFACE, 20));
        map.setData(lastLocation, points, trackStore.points(), selected);
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        mapLp.setMargins(0, 0, 0, dp(14));
        body.addView(map, mapLp);
        body.addView(mapLegend(points), cardMargins());

        if (selected != null) {
            LinearLayout selectedCard = card();
            LinearLayout selectedHeader = row();
            selectedHeader.setGravity(Gravity.CENTER_VERTICAL);
            selectedHeader.addView(text(selected.type.toUpperCase(Locale.ROOT), 11, ACCENT, Typeface.BOLD),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView clear = text("CLEAR", 10, MUTED, Typeface.BOLD);
            clear.setPadding(dp(8), dp(4), 0, dp(4));
            clear.setOnClickListener(v -> {
                selectedMapPointId = -1L;
                render("map");
            });
            selectedHeader.addView(clear);
            selectedCard.addView(selectedHeader);
            selectedCard.addView(spacer(8));
            selectedCard.addView(text(formatCoords(selected.latitude, selected.longitude), 17, TEXT, Typeface.BOLD));
            selectedCard.addView(text(formatDate(selected.timestamp), 12, MUTED, Typeface.NORMAL));
            if (selected.catchDetails != null) {
                selectedCard.addView(spacer(8));
                selectedCard.addView(text(catchSummary(selected.catchDetails), 13, SUCCESS, Typeface.BOLD));
                if (!selected.catchDetails.secondarySummary().isEmpty()) {
                    selectedCard.addView(text(selected.catchDetails.secondarySummary(),
                            11, MUTED, Typeface.NORMAL));
                }
            }
            if (selected.weather != null) {
                selectedCard.addView(spacer(8));
                selectedCard.addView(text(weatherSummary(selected.weather), 13, TEXT, Typeface.BOLD));
                selectedCard.addView(text(weatherDetails(selected.weather), 11, MUTED, Typeface.NORMAL));
            }
            body.addView(selectedCard, cardMargins());
        }

        LinearLayout notice = row();
        notice.setGravity(Gravity.CENTER_VERTICAL);
        TextView note = text(selected == null
                ? "Showing all saved points on the "
                        + MapTilerMapView.styleName(selectedMapStyle).toLowerCase(Locale.ROOT) + " map"
                : "Map centered on selected saved point", 12, MUTED, Typeface.NORMAL);
        notice.addView(note, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView count = text(points.size() + " saved", 12, ACCENT, Typeface.BOLD);
        notice.addView(count);
        body.addView(notice);
        body.addView(spacer(12));
        LinearLayout mapActions = row();
        Button tools = smallButton("MAP & LAYERS");
        tools.setOnClickListener(v -> render("mapTools"));
        mapActions.addView(tools, new LinearLayout.LayoutParams(0, dp(46), 1f));
        mapActions.addView(spaceWide());
        Button trip = primaryButton(trackStore.isActive() ? "ACTIVE TRIP" : "START TRIP");
        trip.setOnClickListener(v -> render(trackStore.isActive() ? "tripActive" : "tripSetup"));
        mapActions.addView(trip, new LinearLayout.LayoutParams(0, dp(46), 1f));
        body.addView(mapActions);
        body.addView(spacer(9));
        Button button = smallButton("SAVE POINT HERE");
        button.setOnClickListener(v -> saveCurrentMoment(POINT_TYPE_WAYPOINT));
        body.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        TextView hint = text("Swipe left from the right edge for Weather  →", 10, MUTED, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = matchWrap();
        hintLp.setMargins(0, dp(8), 0, 0);
        body.addView(hint, hintLp);
        return body;
    }

    private View mapLayerSelector() {
        LinearLayout card = vertical();
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        card.setBackground(roundRect(SURFACE, 14));

        LinearLayout tabs = row();
        addMapLayerTab(tabs, "OUTDOOR", MapTilerMapView.STYLE_OUTDOOR);
        addMapLayerTab(tabs, "SATELLITE", MapTilerMapView.STYLE_HYBRID);
        addMapLayerTab(tabs, "TOPO", MapTilerMapView.STYLE_TOPO);
        addMapLayerTab(tabs, "OCEAN", MapTilerMapView.STYLE_OCEAN);
        card.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        return card;
    }

    private void addMapLayerTab(LinearLayout tabs, String label, String styleId) {
        boolean selected = styleId.equals(selectedMapStyle);
        TextView tab = text(label, 9, selected ? Color.WHITE : MUTED, Typeface.BOLD);
        tab.setGravity(Gravity.CENTER);
        tab.setBackground(roundRect(selected ? ACCENT : SURFACE, BORDER, 1, 7));
        tab.setOnClickListener(v -> {
            if (styleId.equals(selectedMapStyle)) return;
            selectedMapStyle = styleId;
            mapPrefs.edit().putString(MAP_STYLE, styleId).apply();
            render("map");
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.setMargins(dp(1), 0, dp(1), 0);
        tabs.addView(tab, lp);
    }

    private String layerReadyMessage(String styleId) {
        String normalized = MapTilerMapView.normalizeStyleId(styleId);
        if (MapTilerMapView.STYLE_HYBRID.equals(normalized)) {
            return "Satellite layer ready · aerial imagery with labels";
        }
        if (MapTilerMapView.STYLE_TOPO.equals(normalized)) {
            return "Topographic layer ready · terrain and contour detail";
        }
        if (MapTilerMapView.STYLE_OCEAN.equals(normalized)) {
            return "Ocean layer ready · marine bathymetry; inland depth is not guaranteed";
        }
        return "Outdoor layer ready · trails, terrain and contours";
    }

    private View weatherPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = vertical();
        body.setPadding(0, 0, 0, dp(16));
        scroll.addView(body);

        if (weatherForecast != null && weatherForecast.current != null) {
            WeatherSnapshot current = weatherForecast.current;
            LinearLayout now = card();
            LinearLayout header = row();
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.addView(text("NOW · " + current.condition().toUpperCase(Locale.ROOT),
                    11, ACCENT, Typeface.BOLD), weighted());
            header.addView(text(current.symbol() + "  " + formatTemperature(current.temperatureC),
                    24, TEXT, Typeface.BOLD));
            now.addView(header);
            now.addView(spacer(8));
            now.addView(text(weatherSummary(current), 13, TEXT, Typeface.BOLD));
            now.addView(text(weatherDetails(current), 11, MUTED, Typeface.NORMAL));
            now.addView(text("Updated " + formatTime(weatherForecast.fetchedAt)
                    + " · " + weatherForecast.timezone, 10, MUTED, Typeface.NORMAL));
            body.addView(now, cardMargins());
        }

        LinearLayout fishCard = card();
        fishCard.addView(text("TARGET FISH", 10, MUTED, Typeface.BOLD));
        fishCard.addView(text("Choose a fish to adjust the weather outlook", 13, TEXT, Typeface.BOLD));
        fishCard.addView(spacer(10));
        LinearLayout first = row();
        LinearLayout second = row();
        for (int i = 0; i < FishingAdvisor.SPECIES.length; i++) {
            String species = FishingAdvisor.SPECIES[i];
            TextView choice = weatherTab(species.toUpperCase(Locale.ROOT), species.equals(selectedSpecies));
            choice.setOnClickListener(v -> {
                selectedSpecies = species;
                weatherPrefs.edit().putString(WEATHER_SPECIES, species).apply();
                render("map");
            });
            (i < 3 ? first : second).addView(choice,
                    new LinearLayout.LayoutParams(0, dp(40), 1f));
        }
        fishCard.addView(first);
        fishCard.addView(spacer(6));
        fishCard.addView(second);
        fishCard.addView(spacer(10));
        fishCard.addView(text("Weather-based estimate using air conditions; not a catch guarantee.",
                10, MUTED, Typeface.NORMAL));
        body.addView(fishCard, cardMargins());

        LinearLayout status = row();
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.addView(text(forecastStatus, 10, forecastLoading ? WARNING : MUTED, Typeface.NORMAL),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = smallButton(forecastLoading ? "LOADING…" : "REFRESH");
        refresh.setEnabled(!forecastLoading && lastLocation != null);
        refresh.setOnClickListener(v -> loadForecast(true));
        status.addView(refresh, new LinearLayout.LayoutParams(dp(96), dp(38)));
        body.addView(status, cardMargins());

        if (lastLocation == null) {
            LinearLayout missing = card();
            missing.addView(text("Waiting for GPS", 17, TEXT, Typeface.BOLD));
            missing.addView(text("Allow Location so Fiskentra can load the forecast for this place.",
                    12, MUTED, Typeface.NORMAL));
            body.addView(missing);
        } else if (weatherForecast == null && !forecastLoading) {
            LinearLayout missing = card();
            missing.addView(text("Forecast is not available yet", 17, TEXT, Typeface.BOLD));
            missing.addView(text("Check your internet connection, then tap Refresh.",
                    12, MUTED, Typeface.NORMAL));
            body.addView(missing);
        } else if (weatherForecast != null) {
            for (ForecastDay day : weatherForecast.days) {
                body.addView(forecastDayCard(day), cardMargins());
            }
        }

        TextView hint = text("←  Swipe right to return to Map", 10, MUTED, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        body.addView(hint);
        return scroll;
    }

    private View forecastDayCard(ForecastDay day) {
        FishingAdvisor.Assessment assessment = FishingAdvisor.assess(day, selectedSpecies);
        LinearLayout card = card();
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text(day.symbol() + "  " + formatForecastDate(day.date),
                15, TEXT, Typeface.BOLD), weighted());
        TextView score = text(assessment.label.toUpperCase(Locale.ROOT) + "  " + assessment.score,
                10, fishingScoreColor(assessment.score), Typeface.BOLD);
        header.addView(score);
        card.addView(header);
        card.addView(text(day.condition() + " · " + formatTemperatureRange(
                day.minTemperatureC, day.maxTemperatureC), 12, TEXT, Typeface.BOLD));
        card.addView(text("Rain " + day.precipitationProbabilityPercent + "% · "
                + formatPrecipitation(day.precipitationMm)
                + " · Wind " + formatWind(day.maxWindSpeedKmh),
                11, MUTED, Typeface.NORMAL));
        if (!day.sunrise.isEmpty() && !day.sunset.isEmpty()) {
            card.addView(text("Sunrise " + forecastClock(day.sunrise) + " · Sunset "
                    + forecastClock(day.sunset), 10, MUTED, Typeface.NORMAL));
        }
        card.addView(spacer(8));
        card.addView(text(selectedSpecies + " outlook · " + assessment.reason,
                11, fishingScoreColor(assessment.score), Typeface.BOLD));
        return card;
    }

    private void loadForecast(boolean forceRefresh) {
        if (forecastLoading) return;
        Location location = lastLocation;
        if (location == null) {
            forecastStatus = "Waiting for GPS";
            if (("map".equals(screen) && showingWeatherPage) || "home".equals(screen)
                    || "forecastDetail".equals(screen) || "species".equals(screen)) render(screen);
            return;
        }
        forecastLoading = true;
        forecastAttempted = true;
        forecastStatus = forceRefresh ? "Refreshing forecast…" : "Loading forecast…";
        if (("map".equals(screen) && showingWeatherPage) || "home".equals(screen)
                || "forecastDetail".equals(screen) || "species".equals(screen)) render(screen);
        weatherClient.fetchForecast(location.getLatitude(), location.getLongitude(), forceRefresh,
                (forecast, message) -> runOnUiThread(() -> {
                    if (destroyed) return;
                    forecastLoading = false;
                    if (forecast != null) weatherForecast = forecast;
                    forecastStatus = message;
                    if (("map".equals(screen) && showingWeatherPage) || "home".equals(screen)
                            || "forecastDetail".equals(screen) || "species".equals(screen)) render(screen);
                }));
    }

    private int fishingScoreColor(int score) {
        if (score >= 80) return SUCCESS;
        if (score >= 65) return ACCENT;
        if (score >= 45) return WARNING;
        return DANGER;
    }

    private View mapLegend(List<SavedPoint> points) {
        LinearLayout legend = card();
        legend.setPadding(dp(14), dp(12), dp(14), dp(12));
        legend.addView(text("MARKER COLORS", 10, MUTED, Typeface.BOLD));
        legend.addView(spacer(8));
        LinearLayout first = row();
        LinearLayout second = row();
        List<String> types = legendTypes(points);
        for (int i = 0; i < types.size(); i++) {
            LinearLayout target = i < 3 ? first : second;
            target.addView(legendItem(types.get(i)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        legend.addView(first);
        if (types.size() > 3) {
            legend.addView(spacer(8));
            legend.addView(second);
        }
        return legend;
    }

    private List<String> legendTypes(List<SavedPoint> points) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        String[] preferred = {
                POINT_TYPE_CATCH,
                POINT_TYPE_WAYPOINT,
                POINT_TYPE_TACKLE_CHANGE,
                "Sighting",
                "Camp",
                "Hazard",
                "Map"
        };
        for (String type : preferred) {
            for (SavedPoint point : points) {
                if (type.equals(point.type)) found.add(type);
            }
        }
        for (SavedPoint point : points) found.add(point.type);
        if (found.isEmpty()) {
            found.add(POINT_TYPE_CATCH);
            found.add(POINT_TYPE_WAYPOINT);
            found.add(POINT_TYPE_TACKLE_CHANGE);
        }
        return new ArrayList<>(found);
    }

    private View legendItem(String type) {
        LinearLayout item = row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        TextView marker = text(markerLetter(type), 10, Color.rgb(7, 22, 12), Typeface.BOLD);
        marker.setGravity(Gravity.CENTER);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(markerColor(type));
        dot.setStroke(dp(1), Color.WHITE);
        marker.setBackground(dot);
        item.addView(marker, new LinearLayout.LayoutParams(dp(23), dp(23)));
        TextView label = text(shortTypeLabel(type), 11, TEXT, Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.setMargins(dp(7), 0, dp(4), 0);
        item.addView(label, labelLp);
        return item;
    }

    private View savedScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(body);
        List<SavedPoint> points = pointStore.all();
        recoverStaleSyncStates(points);
        body.addView(pageTitle("Saved", points.size() + (points.size() == 1 ? " MOMENT" : " MOMENTS")));
        body.addView(spacer(12));
        EditText savedSearch = authField("Search saved items", InputType.TYPE_CLASS_TEXT);
        body.addView(savedSearch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        body.addView(spacer(8));
        LinearLayout filters = row();
        String[] filterLabels = {"ALL", "CATCH", "WAYPOINT", "TACKLE"};
        for (int index = 0; index < filterLabels.length; index++) {
            String label = filterLabels[index];
            TextView chip = weatherTab(label, index == 0);
            chip.setOnClickListener(v -> comingSoon(label + " saved filter"));
            if (index > 0) filters.addView(spaceWide());
            filters.addView(chip, new LinearLayout.LayoutParams(0, dp(38), 1f));
        }
        body.addView(filters, cardMargins());
        if (shouldShowDeleteStatus()) {
            LinearLayout statusCard = card();
            statusCard.addView(text("DELETE STATUS", 11, cloudSyncColor(), Typeface.BOLD));
            statusCard.addView(spacer(6));
            statusCard.addView(text(cloudSyncStatus, 13, TEXT, Typeface.NORMAL));
            body.addView(statusCard, cardMargins());
        }
        if (points.isEmpty()) {
            LinearLayout empty = card();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(36), dp(24), dp(36));
            TextView icon = text("◇", 40, ACCENT, Typeface.NORMAL); icon.setGravity(Gravity.CENTER);
            empty.addView(icon);
            empty.addView(spacer(10));
            TextView t = text("Nothing saved yet", 20, TEXT, Typeface.BOLD); t.setGravity(Gravity.CENTER); empty.addView(t);
            TextView s = text("Your catches, sightings, camps and places will live here.", 13, MUTED, Typeface.NORMAL);
            s.setGravity(Gravity.CENTER); empty.addView(s);
            body.addView(empty);
        } else {
            int pending = pendingSyncCount(points);
            if (pending > 0) {
                LinearLayout syncCard = card();
                syncCard.addView(text(pending + (pending == 1 ? " local point needs cloud sync" : " local points need cloud sync"), 16, TEXT, Typeface.BOLD));
                syncCard.addView(text("Older points can show Saved locally because they were created before per-point sync status existed.", 12, MUTED, Typeface.NORMAL));
                syncCard.addView(spacer(12));
                Button sync = primaryButton("SYNC LOCAL POINTS");
                sync.setOnClickListener(v -> syncPendingPoints());
                syncCard.addView(sync, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
                body.addView(syncCard, cardMargins());
            }
            for (SavedPoint p : points) body.addView(savedPointCard(p), cardMargins());
        }
        return scroll;
    }

    private View savedPointCard(SavedPoint point) {
        LinearLayout card = card();
        LinearLayout header = row();
        TextView type = text(point.type.toUpperCase(Locale.ROOT), 11, ACCENT, Typeface.BOLD);
        header.addView(type, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boolean deleting = SYNC_DELETING.equals(syncPrefs.getString(syncKey(point.id, "state"), ""));
        TextView delete = text(deleting ? "DELETING" : "DELETE", 10, deleting ? WARNING : DANGER, Typeface.BOLD);
        delete.setPadding(dp(8), dp(4), 0, dp(4));
        if (!deleting) {
            delete.setOnClickListener(v -> {
                if (!userPreferences.confirmDelete()) {
                    deletePoint(point);
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Delete this moment?")
                        .setMessage("Fiskentra will remove this point from Supabase, then from this device.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (dialog, which) -> deletePoint(point))
                        .show();
            });
        }
        header.addView(delete);
        card.addView(header);
        card.addView(spacer(8));
        card.addView(text(formatCoords(point.latitude, point.longitude), 17, TEXT, Typeface.BOLD));
        card.addView(text(formatDate(point.timestamp), 12, MUTED, Typeface.NORMAL));
        card.addView(spacer(8));
        if (POINT_TYPE_CATCH.equals(point.type)) {
            if (point.catchDetails == null) {
                card.addView(text("○  Catch details not added", 12, MUTED, Typeface.BOLD));
            } else {
                card.addView(text(catchSummary(point.catchDetails), 14, SUCCESS, Typeface.BOLD));
                if (!point.catchDetails.secondarySummary().isEmpty()) {
                    card.addView(text(point.catchDetails.secondarySummary(),
                            11, MUTED, Typeface.NORMAL));
                }
                View photo = catchPhotoView(point.catchDetails.localPhotoPath);
                if (photo != null) {
                    LinearLayout.LayoutParams photoLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(160));
                    photoLp.setMargins(0, dp(10), 0, 0);
                    card.addView(photo, photoLp);
                }
            }
            card.addView(spacer(10));
        }
        if (point.weather == null) {
            card.addView(text("○  Weather not captured", 12, MUTED, Typeface.BOLD));
        } else {
            card.addView(text(weatherSummary(point.weather), 13, TEXT, Typeface.BOLD));
            card.addView(text(weatherDetails(point.weather), 11, MUTED, Typeface.NORMAL));
            card.addView(text("Observed " + formatTime(point.weather.observedAt)
                    + " · " + point.weather.provider, 10, MUTED, Typeface.NORMAL));
        }
        card.addView(spacer(8));
        card.addView(text(syncLabel(point.id), 12, syncColor(point.id), Typeface.BOLD));
        card.addView(spacer(12));
        if (POINT_TYPE_CATCH.equals(point.type)) {
            LinearLayout catchActions = row();
            Button editCatch = smallButton(point.catchDetails == null ? "ADD CATCH DETAILS" : "EDIT CATCH");
            editCatch.setOnClickListener(v -> showCatchDetailsDialog(point));
            catchActions.addView(editCatch, new LinearLayout.LayoutParams(0, dp(44), 1f));
            catchActions.addView(spaceWide());
            Button photo = smallButton(point.catchDetails != null
                    && !point.catchDetails.localPhotoPath.isEmpty() ? "CHANGE PHOTO" : "ADD PHOTO");
            photo.setOnClickListener(v -> chooseCatchPhoto(point));
            catchActions.addView(photo, new LinearLayout.LayoutParams(0, dp(44), 1f));
            card.addView(catchActions);
            if (point.catchDetails != null && !point.catchDetails.localPhotoPath.isEmpty()) {
                TextView removePhoto = text("REMOVE LOCAL PHOTO", 10, DANGER, Typeface.BOLD);
                removePhoto.setGravity(Gravity.CENTER);
                removePhoto.setPadding(0, dp(10), 0, dp(6));
                removePhoto.setOnClickListener(v -> removeCatchPhoto(point));
                card.addView(removePhoto);
            }
            card.addView(spacer(8));
        }
        LinearLayout actions = row();
        Button openMap = smallButton("OPEN MAP");
        openMap.setOnClickListener(v -> openPointOnMap(point));
        actions.addView(openMap, new LinearLayout.LayoutParams(0, dp(44), 1f));
        actions.addView(spaceWide());
        Button weather = smallButton(point.weather == null ? "ADD WEATHER" : "REFRESH WEATHER");
        weather.setOnClickListener(v -> refreshPointWeather(point));
        actions.addView(weather, new LinearLayout.LayoutParams(0, dp(44), 1f));
        card.addView(actions);
        card.setOnClickListener(v -> openPointOnMap(point));
        return card;
    }

    private void showCatchDetailsDialog(SavedPoint point) {
        if (!POINT_TYPE_CATCH.equals(point.type)) return;
        CatchDetails existing = point.catchDetails;
        LinearLayout form = vertical();
        form.setPadding(dp(18), dp(8), dp(18), dp(4));

        EditText species = catchField("Fish species", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        boolean imperial = userPreferences.usesImperialUnits();
        EditText length = catchField(imperial ? "Length (in)" : "Length (cm)", InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText weight = catchField(imperial ? "Weight (lb)" : "Weight (kg)", InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText lure = catchField("Lure or bait", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText notes = catchField("Notes", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        notes.setMinLines(2);
        CheckBox released = new CheckBox(this);
        released.setText(R.string.catch_released_label);
        released.setTextColor(TEXT);

        if (existing != null) {
            species.setText(existing.species);
            if (existing.lengthCm > 0d) length.setText(decimalInput(displayLength(existing.lengthCm)));
            if (existing.weightKg > 0d) weight.setText(decimalInput(displayWeight(existing.weightKg)));
            lure.setText(existing.lure);
            notes.setText(existing.notes);
            released.setChecked(existing.released);
        }
        form.addView(species, matchWrap());
        form.addView(length, matchWrap());
        form.addView(weight, matchWrap());
        form.addView(lure, matchWrap());
        form.addView(notes, matchWrap());
        form.addView(released, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add catch details" : "Edit catch details")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String photoPath = existing == null ? "" : existing.localPhotoPath;
                    CatchDetails details = new CatchDetails(
                            species.getText().toString(),
                            storedLength(positiveNumber(length.getText().toString())),
                            storedWeight(positiveNumber(weight.getText().toString())),
                            lure.getText().toString(),
                            notes.getText().toString(),
                            released.isChecked(),
                            photoPath);
                    SavedPoint updated = pointStore.updateCatchDetails(point.id, details);
                    if (updated == null) return;
                    Toast.makeText(this, "Catch details saved", Toast.LENGTH_SHORT).show();
                    syncPoint(updated);
                    render("saved");
                })
                .show();
    }

    private EditText catchField(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(MUTED);
        field.setTextColor(TEXT);
        field.setInputType(inputType);
        field.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        field.setPadding(dp(4), dp(10), dp(4), dp(10));
        return field;
    }

    private void chooseCatchPhoto(SavedPoint point) {
        if (point.catchDetails == null) {
            SavedPoint initialized=pointStore.updateCatchDetails(point.id,new CatchDetails("",0,0,"","",false,""));
            if(initialized==null)return;
        }
        pendingCatchPhotoPointId = point.id;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("image/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(picker, REQUEST_CATCH_PHOTO);
    }

    private void saveSelectedCatchPhoto(long pointId, Uri source) {
        Toast.makeText(this, "Saving photo locally…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File target = null;
            try {
                File directory = new File(getFilesDir(), "catch_photos");
                if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("photo directory");
                target = new File(directory, "catch-" + pointId + "-" + System.currentTimeMillis() + ".img");
                try (InputStream input = getContentResolver().openInputStream(source);
                     FileOutputStream output = new FileOutputStream(target)) {
                    if (input == null) throw new IllegalStateException("photo input");
                    byte[] buffer = new byte[8_192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                SavedPoint latest = pointStore.find(pointId);
                if (latest == null || latest.catchDetails == null) throw new IllegalStateException("catch missing");
                String previous = latest.catchDetails.localPhotoPath;
                SavedPoint updated = pointStore.updateCatchDetails(
                        pointId, latest.catchDetails.withLocalPhotoPath(target.getAbsolutePath()));
                if (updated == null) throw new IllegalStateException("catch update");
                if (!previous.isEmpty() && !previous.equals(target.getAbsolutePath())) {
                    deleteLocalCatchPhoto(previous);
                }
                runOnUiThread(() -> {
                    if (destroyed) return;
                    Toast.makeText(this, "Catch photo saved on this device", Toast.LENGTH_SHORT).show();
                    if ("saved".equals(screen)) render("saved");
                });
            } catch (Exception error) {
                if (target != null) deleteLocalCatchPhoto(target.getAbsolutePath());
                runOnUiThread(() -> {
                    if (!destroyed) Toast.makeText(this, "Could not save this photo", Toast.LENGTH_SHORT).show();
                });
            }
        }, "fiskentra-catch-photo").start();
    }

    private View catchPhotoView(String path) {
        if (path == null || path.isEmpty()) return null;
        File file = new File(path);
        if (!file.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        int targetWidth = Math.max(1, dp(320));
        int targetHeight = Math.max(1, dp(160));
        while (bounds.outWidth / sample > targetWidth * 2
                || bounds.outHeight / sample > targetHeight * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        if (bitmap == null) return null;
        ImageView photo = new ImageView(this);
        photo.setImageBitmap(bitmap);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setContentDescription("Catch photo");
        photo.setBackground(roundRect(SURFACE_2, 12));
        return photo;
    }

    private void removeCatchPhoto(SavedPoint point) {
        if (point.catchDetails == null || point.catchDetails.localPhotoPath.isEmpty()) return;
        deleteLocalCatchPhoto(point.catchDetails.localPhotoPath);
        pointStore.updateCatchDetails(point.id, point.catchDetails.withLocalPhotoPath(""));
        Toast.makeText(this, "Local catch photo removed", Toast.LENGTH_SHORT).show();
        render("saved");
    }

    private void deleteLocalCatchPhoto(String path) {
        if (path == null || path.isEmpty()) return;
        try {
            File root = new File(getFilesDir(), "catch_photos").getCanonicalFile();
            File target = new File(path).getCanonicalFile();
            String prefix = root.getPath() + File.separator;
            if (target.getPath().startsWith(prefix) && target.isFile()) target.delete();
        } catch (Exception ignored) { }
    }

    private static double positiveNumber(String value) {
        try {
            return Math.max(0d, Double.parseDouble(value.trim().replace(',', '.')));
        } catch (Exception ignored) {
            return 0d;
        }
    }

    private static String decimalInput(double value) {
        return String.format(Locale.US, "%s", value);
    }

    private View onboardingScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(22), dp(24), dp(22), dp(28));
        scroll.addView(body);

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("FISKENTRA · QUICK START", 11, ACCENT, Typeface.BOLD);
        top.addView(brand, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView skip = text(onboardingReplay ? "CLOSE" : "SKIP", 11, MUTED, Typeface.BOLD);
        skip.setPadding(dp(12), dp(8), 0, dp(8));
        skip.setOnClickListener(v -> finishOnboarding("home"));
        top.addView(skip);
        body.addView(top);
        body.addView(spacer(18));

        LinearLayout progress = row();
        for (int index = 0; index < 4; index++) {
            View segment = new View(this);
            segment.setBackground(roundRect(index <= onboardingStep ? ACCENT : SURFACE_2, 3));
            LinearLayout.LayoutParams segmentParams = new LinearLayout.LayoutParams(0, dp(5), 1f);
            if (index > 0) segmentParams.setMargins(dp(6), 0, 0, 0);
            progress.addView(segment, segmentParams);
        }
        body.addView(progress);
        body.addView(spacer(28));

        if (onboardingStep == 0) addOnboardingWelcome(body);
        else if (onboardingStep == 1) addOnboardingPermissions(body);
        else if (onboardingStep == 2) addOnboardingFlic(body);
        else addOnboardingReady(body);

        body.addView(spacer(22));
        LinearLayout actions = row();
        if (onboardingStep > 0) {
            Button back = smallButton("BACK");
            back.setOnClickListener(v -> {
                onboardingStep--;
                render("onboarding");
            });
            actions.addView(back, new LinearLayout.LayoutParams(0, dp(52), 1f));
            actions.addView(spaceWide());
        }
        Button next = primaryButton(onboardingStep < 3 ? "CONTINUE" : "START FISKENTRA");
        next.setOnClickListener(v -> {
            if (onboardingStep < 3) {
                onboardingStep++;
                render("onboarding");
            } else {
                finishOnboarding("home");
            }
        });
        actions.addView(next, new LinearLayout.LayoutParams(0, dp(52),
                onboardingStep > 0 ? 1f : 2f));
        body.addView(actions);

        if (onboardingStep == 3) {
            body.addView(spacer(10));
            Button device = smallButton("START AND SET UP FLIC 2");
            device.setOnClickListener(v -> finishOnboarding("device"));
            body.addView(device, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        }
        return scroll;
    }

    private void addOnboardingWelcome(LinearLayout body) {
        ImageView brand = new ImageView(this);
        brand.setImageResource(R.drawable.fiskentra_wordmark);
        brand.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        brand.setContentDescription("Fiskentra");
        body.addView(brand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));
        body.addView(text("Remember every place and catch", 30, TEXT, Typeface.BOLD));
        body.addView(spacer(10));
        body.addView(text("Fiskentra combines your phone’s GPS, fishing journal, weather and Flic 2 button so a moment is never lost in the field.",
                15, MUTED, Typeface.NORMAL));
        body.addView(spacer(22));
        body.addView(onboardingInfoCard("LOCAL FIRST",
                "Every point is saved on this phone before cloud sync. Fishing still works without mobile data."), cardMargins());
        body.addView(onboardingInfoCard("BUILT FOR THE WATER",
                "Large actions, quick map access and button capture keep phone handling to a minimum."), cardMargins());
    }

    private void addOnboardingPermissions(LinearLayout body) {
        body.addView(text("Prepare your phone for the field", 30, TEXT, Typeface.BOLD));
        body.addView(spacer(10));
        body.addView(text("Fiskentra asks only for access used by its field features. You can continue if you prefer to enable it later.",
                15, MUTED, Typeface.NORMAL));
        body.addView(spacer(22));
        LinearLayout permissions = card();
        permissions.addView(text("REQUIRED FOR CAPTURE", 11, ACCENT, Typeface.BOLD));
        permissions.addView(spacer(10));
        permissions.addView(checkRow(locationManager.hasPermission(),
                "Location · coordinates and current position"));
        permissions.addView(checkRow(flicManager.hasPermissions(),
                "Nearby devices · Flic 2 pairing and reconnect"));
        boolean notificationsReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        permissions.addView(checkRow(notificationsReady,
                "Notifications · screen-off button capture status"));
        permissions.addView(spacer(14));
        Button allow = smallButton("ALLOW FIELD ACCESS");
        allow.setOnClickListener(v -> requestNeededPermissions());
        permissions.addView(allow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        body.addView(permissions, cardMargins());
        body.addView(text("Location and Bluetooth stay under Android permission control. Fiskentra does not require an account to use them.",
                12, MUTED, Typeface.NORMAL));
    }

    private void addOnboardingFlic(LinearLayout body) {
        body.addView(text("One button, three moments", 30, TEXT, Typeface.BOLD));
        body.addView(spacer(10));
        body.addView(text("Pair your Flic 2 once. Fiskentra then reconnects it and can capture with the screen off.",
                15, MUTED, Typeface.NORMAL));
        body.addView(spacer(22));
        ImageView flicHero = imageCard(R.drawable.flic2_setup_hero, "Flic 2 button");
        body.addView(flicHero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));
        body.addView(spacer(14));
        LinearLayout mapping = card();
        mapping.addView(text("FLIC 2 ACTIONS", 11, ACCENT, Typeface.BOLD));
        mapping.addView(spacer(12));
        mapping.addView(onboardingMapping("1×", "Single press", "Register a catch", SUCCESS));
        mapping.addView(spacer(12));
        mapping.addView(onboardingMapping("2×", "Double press", "Save a waypoint", WARNING));
        mapping.addView(spacer(12));
        mapping.addView(onboardingMapping("—", "Hold", "Record a tackle change",
                Color.rgb(197, 155, 255)));
        body.addView(mapping, cardMargins());
        body.addView(onboardingInfoCard("OFFLINE IS OK",
                "The press is saved locally. Pending points sync automatically when Android validates internet access."), cardMargins());
    }

    private void addOnboardingReady(LinearLayout body) {
        body.addView(text("Ready for your next fishing day", 30, TEXT, Typeface.BOLD));
        body.addView(spacer(10));
        body.addView(text("Start in local mode now. You can pair Flic 2, change units and map layers, or sign in later from Profile.",
                15, MUTED, Typeface.NORMAL));
        body.addView(spacer(22));
        LinearLayout ready = card();
        ready.addView(text("YOUR QUICK CHECKLIST", 11, ACCENT, Typeface.BOLD));
        ready.addView(spacer(10));
        ready.addView(checkRow(locationManager.hasPermission(), "GPS access"));
        ready.addView(checkRow(flicManager.pairedButtonCount() > 0, "Flic 2 paired (optional)"));
        ready.addView(checkRow(true, "Offline local storage ready"));
        ready.addView(checkRow(SupabaseConfig.isConfigured(), "Cloud connection configured"));
        body.addView(ready, cardMargins());
        body.addView(text("Account registration is optional. Existing local points, journal entries and Flic pairing remain on this phone when you sign in or out.",
                12, MUTED, Typeface.NORMAL));
    }

    private View onboardingInfoCard(String title, String description) {
        LinearLayout info = card();
        info.addView(text(title, 11, ACCENT, Typeface.BOLD));
        info.addView(spacer(7));
        info.addView(text(description, 13, TEXT, Typeface.NORMAL));
        return info;
    }

    private View onboardingMapping(String badge, String action, String result, int color) {
        LinearLayout mapping = row();
        mapping.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text(badge, 14, Color.rgb(7, 22, 12), Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundRect(color, 20));
        mapping.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout copy = vertical();
        copy.setPadding(dp(12), 0, 0, 0);
        copy.addView(text(action, 13, TEXT, Typeface.BOLD));
        copy.addView(text(result, 12, MUTED, Typeface.NORMAL));
        mapping.addView(copy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return mapping;
    }

    private void finishOnboarding(String destination) {
        userPreferences.completeOnboarding();
        onboardingStep = 0;
        onboardingReplay = false;
        render(destination);
        requestNeededPermissions();
    }

    private View profileScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(body);

        body.addView(pageTitle("Profile", "FISKENTRA ACCOUNT"));
        body.addView(spacer(18));
        SupabaseAuthManager.Session account = authManager.session();

        if (account == null) {
            ImageView lakeHero = imageCard(R.drawable.fiskentra_lake_hero,
                    "Northern lake at blue hour");
            body.addView(lakeHero, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));
            body.addView(spacer(12));
            if ("landing".equals(authFormMode)) {
                LinearLayout localCard = card();
                localCard.addView(text("○  LOCAL MODE", 11, MUTED, Typeface.BOLD));
                localCard.addView(spacer(8));
                localCard.addView(text("Fiskentra works without an account", 20, TEXT, Typeface.BOLD));
                localCard.addView(text("Flic 2, GPS, saved points and the fishing journal remain available offline. Sign in adds private account access without blocking field capture.",
                        13, MUTED, Typeface.NORMAL));
                body.addView(localCard, cardMargins());

                LinearLayout choice = card();
                choice.addView(text("YOUR FISKENTRA ACCOUNT", 11, ACCENT, Typeface.BOLD));
                choice.addView(spacer(7));
                choice.addView(text(authStatus, 12,
                        authStatusError ? DANGER : MUTED, Typeface.NORMAL));
                choice.addView(spacer(16));
                Button signIn = primaryButton(authBusy ? "VERIFYING…" : "SIGN IN");
                signIn.setEnabled(!authBusy);
                signIn.setOnClickListener(v -> openAuthForm("signin"));
                choice.addView(signIn, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
                choice.addView(spacer(10));
                Button create = smallButton("CREATE ACCOUNT");
                create.setEnabled(!authBusy);
                create.setOnClickListener(v -> openAuthForm("signup"));
                choice.addView(create, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
                body.addView(choice, cardMargins());
            } else if ("checkEmail".equals(authFormMode)) {
                body.addView(checkEmailCard(), cardMargins());
            } else {
                body.addView(authFormCard(), cardMargins());
            }

            LinearLayout privacy = card();
            privacy.addView(text("PRIVACY", 11, MUTED, Typeface.BOLD));
            privacy.addView(text("Passwords are sent directly to Supabase Auth and are never stored by Fiskentra. Session tokens are encrypted with Android Keystore on this phone.",
                    12, TEXT, Typeface.NORMAL));
            body.addView(privacy, cardMargins());
            body.addView(settingsEntryCard(), cardMargins());
            return scroll;
        }

        if ("reset".equals(authFormMode)) {
            body.addView(passwordResetCard(), cardMargins());
            return scroll;
        }

        LinearLayout identity = card();
        LinearLayout identityRow = row();
        identityRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView avatar = text(profileInitial(account.displayName), 24, Color.rgb(7, 22, 12), Typeface.BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(roundRect(ACCENT, 28));
        identityRow.addView(avatar, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout identityCopy = vertical();
        identityCopy.setPadding(dp(14), 0, 0, 0);
        identityCopy.addView(text(account.displayName, 20, TEXT, Typeface.BOLD));
        identityCopy.addView(text(account.email, 12, MUTED, Typeface.NORMAL));
        identityCopy.addView(text("●  SIGNED IN", 10, SUCCESS, Typeface.BOLD));
        identityRow.addView(identityCopy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        identity.addView(identityRow);
        identity.addView(spacer(14));
        identity.addView(text(authStatus, 11,
                authBusy ? WARNING : (authStatusError ? DANGER : MUTED), Typeface.NORMAL));
        body.addView(identity, cardMargins());

        LinearLayout fieldStats = card();
        fieldStats.addView(text("THIS PHONE", 11, MUTED, Typeface.BOLD));
        fieldStats.addView(spacer(7));
        fieldStats.addView(text(pointStore.all().size() + " saved moments", 19, TEXT, Typeface.BOLD));
        fieldStats.addView(text("Your existing local points and Flic pairing stay on this phone. Account sign-in does not delete or replace them.",
                12, MUTED, Typeface.NORMAL));
        body.addView(fieldStats, cardMargins());

        LinearLayout actions = card();
        Button edit = primaryButton(authBusy ? "PLEASE WAIT…" : "EDIT DISPLAY NAME");
        edit.setEnabled(!authBusy);
        edit.setOnClickListener(v -> showDisplayNameDialog(account.displayName));
        actions.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        actions.addView(spacer(10));
        Button settings = smallButton("SETTINGS");
        settings.setEnabled(!authBusy);
        settings.setOnClickListener(v -> render("settings"));
        actions.addView(settings, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        actions.addView(spacer(10));
        Button signOut = smallButton("SIGN OUT");
        signOut.setEnabled(!authBusy);
        signOut.setOnClickListener(v -> confirmSignOut());
        actions.addView(signOut, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        body.addView(actions, cardMargins());
        return scroll;
    }

    private View settingsEntryCard() {
        LinearLayout entry = card();
        entry.addView(text("APP SETTINGS", 11, ACCENT, Typeface.BOLD));
        entry.addView(text("Units, map start page, fishing outlook and field preferences.",
                12, MUTED, Typeface.NORMAL));
        entry.addView(spacer(12));
        Button open = smallButton("OPEN SETTINGS");
        open.setOnClickListener(v -> render("settings"));
        entry.addView(open, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        return entry;
    }

    private View settingsScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(body);

        body.addView(pageTitle("Settings", "FISKENTRA · THIS PHONE"));
        body.addView(spacer(18));

        LinearLayout units = card();
        units.addView(text("MEASUREMENT UNITS", 11, ACCENT, Typeface.BOLD));
        units.addView(text("Saved values stay unchanged; only display and catch entry units change.",
                12, MUTED, Typeface.NORMAL));
        units.addView(spacer(12));
        LinearLayout unitChoices = row();
        boolean imperial = userPreferences.usesImperialUnits();
        Button metric = settingsChoice("METRIC · °C / cm / kg", !imperial);
        metric.setOnClickListener(v -> {
            userPreferences.setUsesImperialUnits(false);
            render("settings");
        });
        unitChoices.addView(metric, new LinearLayout.LayoutParams(0, dp(48), 1f));
        unitChoices.addView(spaceWide());
        Button imperialChoice = settingsChoice("IMPERIAL · °F / in / lb", imperial);
        imperialChoice.setOnClickListener(v -> {
            userPreferences.setUsesImperialUnits(true);
            render("settings");
        });
        unitChoices.addView(imperialChoice, new LinearLayout.LayoutParams(0, dp(48), 1f));
        units.addView(unitChoices);
        body.addView(units, cardMargins());

        LinearLayout explore = card();
        explore.addView(text("EXPLORE START PAGE", 11, ACCENT, Typeface.BOLD));
        explore.addView(text("Choose what opens when you enter Explore from another tab.",
                12, MUTED, Typeface.NORMAL));
        explore.addView(spacer(12));
        LinearLayout pageChoices = row();
        boolean weatherDefault = userPreferences.defaultWeatherPage();
        Button mapChoice = settingsChoice("MAP", !weatherDefault);
        mapChoice.setOnClickListener(v -> {
            userPreferences.setDefaultWeatherPage(false);
            render("settings");
        });
        pageChoices.addView(mapChoice, new LinearLayout.LayoutParams(0, dp(46), 1f));
        pageChoices.addView(spaceWide());
        Button weatherChoice = settingsChoice("WEATHER", weatherDefault);
        weatherChoice.setOnClickListener(v -> {
            userPreferences.setDefaultWeatherPage(true);
            render("settings");
        });
        pageChoices.addView(weatherChoice, new LinearLayout.LayoutParams(0, dp(46), 1f));
        explore.addView(pageChoices);
        explore.addView(spacer(16));
        explore.addView(text("DEFAULT MAP LAYER", 10, MUTED, Typeface.BOLD));
        explore.addView(spacer(8));
        addSettingsMapLayerRow(explore, new String[] {
                MapTilerMapView.STYLE_OUTDOOR, MapTilerMapView.STYLE_HYBRID
        }, new String[] {"OUTDOOR", "SATELLITE"});
        explore.addView(spacer(8));
        addSettingsMapLayerRow(explore, new String[] {
                MapTilerMapView.STYLE_TOPO, MapTilerMapView.STYLE_OCEAN
        }, new String[] {"TOPOGRAPHIC", "OCEAN"});
        body.addView(explore, cardMargins());

        LinearLayout fish = card();
        fish.addView(text("FISHING OUTLOOK", 11, ACCENT, Typeface.BOLD));
        fish.addView(text("Default target fish for the seven-day weather outlook.",
                12, MUTED, Typeface.NORMAL));
        fish.addView(spacer(12));
        for (int index = 0; index < FishingAdvisor.SPECIES.length; index += 2) {
            LinearLayout fishRow = row();
            addSpeciesSetting(fishRow, FishingAdvisor.SPECIES[index]);
            if (index + 1 < FishingAdvisor.SPECIES.length) {
                fishRow.addView(spaceWide());
                addSpeciesSetting(fishRow, FishingAdvisor.SPECIES[index + 1]);
            } else {
                fishRow.addView(new View(this), new LinearLayout.LayoutParams(
                        0, dp(44), 1f));
            }
            fish.addView(fishRow);
            if (index + 2 < FishingAdvisor.SPECIES.length) fish.addView(spacer(8));
        }
        body.addView(fish, cardMargins());

        LinearLayout safety = card();
        safety.addView(text("SAFETY", 11, ACCENT, Typeface.BOLD));
        CheckBox confirmDelete = new CheckBox(this);
        confirmDelete.setText("Confirm before deleting a saved point");
        confirmDelete.setTextColor(TEXT);
        confirmDelete.setTextSize(13);
        confirmDelete.setChecked(userPreferences.confirmDelete());
        confirmDelete.setOnCheckedChangeListener((button, checked) ->
                userPreferences.setConfirmDelete(checked));
        safety.addView(confirmDelete, matchWrap());
        safety.addView(text("Turning this off makes DELETE act immediately. Cloud deletion still keeps the point locally if it fails.",
                11, MUTED, Typeface.NORMAL));
        body.addView(safety, cardMargins());

        LinearLayout future = card();
        future.addView(text("FIELD SERVICES", 11, ACCENT, Typeface.BOLD));
        future.addView(settingLine("Offline maps", "Map tools"));
        future.addView(settingLine("GPS / Bluetooth permissions", locationManager.hasPermission()
                && flicManager.hasPermissions() ? "Ready" : "Review"));
        future.addView(settingLine("Forecast providers", "Open-Meteo"));
        future.addView(settingLine("Barometer offset", "0 m"));
        future.addView(settingLine("Safety & SOS", "Coming soon"));
        future.addView(settingLine("News & offline library", "Coming soon"));
        future.addView(settingLine("Help & tutorials", "Quick Start"));
        future.addView(spacer(10));
        LinearLayout fieldActions = row();
        Button maps = smallButton("MAP TOOLS");
        maps.setOnClickListener(v -> render("mapTools"));
        fieldActions.addView(maps, new LinearLayout.LayoutParams(0, dp(44), 1f));
        fieldActions.addView(spaceWide());
        Button permissions = smallButton("PERMISSIONS");
        permissions.setOnClickListener(v -> requestNeededPermissions());
        fieldActions.addView(permissions, new LinearLayout.LayoutParams(0, dp(44), 1f));
        future.addView(fieldActions);
        body.addView(future, cardMargins());

        List<SavedPoint> points = pointStore.all();
        int pending = pendingSyncCount(points);
        LinearLayout data = card();
        data.addView(text("LOCAL DATA & SYNC", 11, ACCENT, Typeface.BOLD));
        data.addView(spacer(7));
        data.addView(text(points.size() + " saved moments on this phone", 18, TEXT, Typeface.BOLD));
        data.addView(text(pending == 0 ? "All points are synced"
                        : pending + (pending == 1 ? " point is waiting to sync" : " points are waiting to sync"),
                12, pending == 0 ? SUCCESS : WARNING, Typeface.NORMAL));
        data.addView(spacer(12));
        Button sync = smallButton(pending == 0 ? "CHECK SYNC" : "SYNC NOW");
        sync.setOnClickListener(v -> syncPendingPoints());
        data.addView(sync, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        body.addView(data, cardMargins());

        LinearLayout beta = card();
        beta.addView(text("CLOSED BETA", 11, WARNING, Typeface.BOLD));
        beta.addView(spacer(7));
        beta.addView(text("Check field readiness and share a privacy-safe support report.",
                13, TEXT, Typeface.NORMAL));
        beta.addView(spacer(12));
        Button openBeta = smallButton("OPEN BETA CENTER");
        openBeta.setOnClickListener(v -> render("beta"));
        beta.addView(openBeta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        body.addView(beta, cardMargins());

        LinearLayout about = card();
        about.addView(text("ABOUT", 11, MUTED, Typeface.BOLD));
        about.addView(text("Fiskentra v" + BuildConfig.VERSION_NAME, 16, TEXT, Typeface.BOLD));
        about.addView(text("Settings are stored only on this phone and work without an account or internet connection.",
                12, MUTED, Typeface.NORMAL));
        about.addView(spacer(12));
        Button quickStart = smallButton("VIEW QUICK START");
        quickStart.setOnClickListener(v -> {
            onboardingReplay = true;
            onboardingStep = 0;
            render("onboarding");
        });
        about.addView(quickStart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        about.addView(spacer(10));
        Button back = smallButton("BACK TO PROFILE");
        back.setOnClickListener(v -> render("profile"));
        about.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        body.addView(about, cardMargins());
        return scroll;
    }

    private View betaScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(body);

        body.addView(pageTitle("Beta Center", "FISKENTRA · CLOSED BETA"));
        body.addView(spacer(10));
        body.addView(text("Use this page before a field test and attach its safe report when something does not work.",
                13, MUTED, Typeface.NORMAL));
        body.addView(spacer(18));

        List<SavedPoint> points = pointStore.all();
        int pending = pendingSyncCount(points);
        boolean locationReady = locationManager.hasPermission();
        boolean bluetoothReady = flicManager.hasPermissions();
        boolean notificationsReady = notificationPermissionReady();
        boolean buttonReady = flicManager.pairedButtonCount() > 0;
        boolean backgroundReady = buttonReady && FiskentraFlicService.isRunning();
        boolean syncReady = pending == 0;
        boolean[] checks = new boolean[] {
                locationReady, bluetoothReady, notificationsReady, buttonReady,
                backgroundReady, cloudConnected, syncReady
        };
        int readyCount = 0;
        for (boolean ready : checks) if (ready) readyCount++;

        LinearLayout readiness = card();
        readiness.addView(text("FIELD READINESS", 11, ACCENT, Typeface.BOLD));
        readiness.addView(spacer(7));
        readiness.addView(text(readyCount + " / " + checks.length + " checks ready", 21,
                readyCount == checks.length ? SUCCESS : WARNING, Typeface.BOLD));
        readiness.addView(spacer(8));
        readiness.addView(checkRow(locationReady, "Precise location permission"));
        readiness.addView(checkRow(bluetoothReady, "Nearby devices permission"));
        readiness.addView(checkRow(notificationsReady, "Notification permission"));
        readiness.addView(checkRow(buttonReady, "Flic 2 paired"));
        readiness.addView(checkRow(backgroundReady, "Screen-off capture service active"));
        readiness.addView(checkRow(cloudConnected, "Fiskentra cloud reachable"));
        readiness.addView(checkRow(syncReady, pending == 0
                ? "No points waiting to sync" : pending + " points waiting to sync"));
        readiness.addView(spacer(14));
        LinearLayout readinessActions = row();
        Button recheck = smallButton("RECHECK NOW");
        recheck.setOnClickListener(v -> runBetaChecks());
        readinessActions.addView(recheck, new LinearLayout.LayoutParams(0, dp(46), 1f));
        readinessActions.addView(spaceWide());
        Button device = smallButton("OPEN DEVICE");
        device.setOnClickListener(v -> render("device"));
        readinessActions.addView(device, new LinearLayout.LayoutParams(0, dp(46), 1f));
        readiness.addView(readinessActions);
        body.addView(readiness, cardMargins());

        LinearLayout report = card();
        report.addView(text("SAFE SUPPORT REPORT", 11, ACCENT, Typeface.BOLD));
        report.addView(spacer(7));
        report.addView(text("Includes app/Android version, permission states, Flic status and item counts.",
                13, TEXT, Typeface.NORMAL));
        report.addView(spacer(7));
        report.addView(text("Never includes coordinates, notes, catches, account email, passwords, tokens, API keys or the phone’s install ID.",
                12, SUCCESS, Typeface.NORMAL));
        report.addView(spacer(14));
        Button share = primaryButton("SHARE BETA REPORT");
        share.setOnClickListener(v -> shareBetaReport());
        report.addView(share, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        body.addView(report, cardMargins());

        LinearLayout expectations = card();
        expectations.addView(text("BETA EXPECTATIONS", 11, WARNING, Typeface.BOLD));
        expectations.addView(spacer(7));
        expectations.addView(text("• Save test moments locally before relying on cloud sync.\n"
                        + "• Do not clear app data if the journal has not been backed up.\n"
                        + "• Open Fiskentra once after restarting the phone.\n"
                        + "• Force Stop disables background Flic capture until the app is opened again.",
                13, TEXT, Typeface.NORMAL));
        body.addView(expectations, cardMargins());

        LinearLayout privacy = card();
        privacy.addView(text("PRIVACY", 11, MUTED, Typeface.BOLD));
        privacy.addView(text("Fiskentra has no analytics or automatic diagnostic upload in this beta. Sharing a report always requires your action through Android’s share screen.",
                12, MUTED, Typeface.NORMAL));
        privacy.addView(spacer(12));
        Button back = smallButton("BACK TO SETTINGS");
        back.setOnClickListener(v -> render("settings"));
        privacy.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        body.addView(privacy, cardMargins());
        return scroll;
    }

    private void runBetaChecks() {
        cloudConnected = false;
        cloudStatus = "Checking Fiskentra cloud…";
        syncQueue.retryPending();
        render("beta");
        supabaseConnection.check((connected, message) -> runOnUiThread(() -> {
            if (destroyed) return;
            cloudConnected = connected;
            cloudStatus = message;
            if ("beta".equals(screen)) render("beta");
        }));
    }

    private void shareBetaReport() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT,
                "Fiskentra v" + BuildConfig.VERSION_NAME + " beta report");
        share.putExtra(Intent.EXTRA_TEXT, betaReport());
        try {
            startActivity(Intent.createChooser(share, "Share Fiskentra beta report"));
        } catch (RuntimeException error) {
            Toast.makeText(this, "No app is available to share the report",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void stopBackgroundServiceIfIdle() {
        if (trackStore != null && trackStore.isActive()) return;
        if (flicManager != null && flicManager.pairedButtonCount() > 0) return;
        FiskentraFlicService.stop(this);
    }

    private String betaReport() {
        List<SavedPoint> points = pointStore.all();
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US);
        time.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        StringBuilder report = new StringBuilder();
        report.append("Fiskentra closed beta report\n");
        report.append("Generated: ").append(time.format(new Date())).append('\n');
        report.append("App: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(") ")
                .append(BuildConfig.BUILD_TYPE).append('\n');
        report.append("Android: ").append(Build.VERSION.SDK_INT).append('\n');
        report.append("Device: ").append(safeDiagnostic(Build.MANUFACTURER)).append(' ')
                .append(safeDiagnostic(Build.MODEL)).append('\n');
        report.append("Locale: ").append(Locale.getDefault().toLanguageTag()).append("\n\n");
        report.append("Location permission: ").append(locationManager.hasPermission()).append('\n');
        report.append("Nearby devices permission: ").append(flicManager.hasPermissions()).append('\n');
        report.append("Notification permission: ").append(notificationPermissionReady()).append('\n');
        report.append("Flic paired: ").append(flicManager.pairedButtonCount() > 0).append('\n');
        report.append("Flic connected: ").append(flicConnected).append('\n');
        report.append("Background capture: ").append(FiskentraFlicService.isRunning()).append('\n');
        report.append("Cloud reachable: ").append(cloudConnected).append('\n');
        report.append("Signed in: ").append(authManager.isSignedIn()).append('\n');
        report.append("Saved moments: ").append(points.size()).append('\n');
        report.append("Pending sync: ").append(pendingSyncCount(points)).append('\n');
        report.append("Fishing sessions: ").append(fishingDayStore.all().size()).append('\n');
        report.append("Track active: ").append(trackStore.isActive()).append('\n');
        report.append("Track points: ").append(trackStore.points().size()).append("\n\n");
        report.append("Excluded: coordinates, notes, catch details, email, credentials, tokens, API keys and install ID.\n");
        return report.toString();
    }

    private boolean notificationPermissionReady() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static String safeDiagnostic(String value) {
        return value == null ? "unknown" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private Button settingsChoice(String label, boolean selected) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(10);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        button.setTextColor(selected ? Color.WHITE : TEXT);
        button.setBackground(roundRect(selected ? ACCENT : SURFACE_2, BORDER, 1, 7));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private void addSettingsMapLayerRow(LinearLayout parent, String[] styles, String[] labels) {
        LinearLayout choices = row();
        for (int index = 0; index < styles.length; index++) {
            if (index > 0) choices.addView(spaceWide());
            String style = styles[index];
            Button choice = settingsChoice(labels[index], style.equals(selectedMapStyle));
            choice.setOnClickListener(v -> {
                selectedMapStyle = style;
                mapPrefs.edit().putString(MAP_STYLE, style).apply();
                render("settings");
            });
            choices.addView(choice, new LinearLayout.LayoutParams(0, dp(44), 1f));
        }
        parent.addView(choices);
    }

    private void addSpeciesSetting(LinearLayout row, String species) {
        Button choice = settingsChoice(species.toUpperCase(Locale.ROOT), species.equals(selectedSpecies));
        choice.setOnClickListener(v -> {
            selectedSpecies = species;
            weatherPrefs.edit().putString(WEATHER_SPECIES, species).apply();
            render("settings");
        });
        row.addView(choice, new LinearLayout.LayoutParams(0, dp(44), 1f));
    }

    private View authFormCard() {
        boolean signup = "signup".equals(authFormMode);
        boolean recover = "recover".equals(authFormMode);
        LinearLayout form = card();
        form.addView(text(recover ? "RESET PASSWORD" : (signup ? "CREATE ACCOUNT" : "WELCOME BACK"),
                11, ACCENT, Typeface.BOLD));
        form.addView(spacer(7));
        form.addView(text(recover
                        ? "We’ll send a secure reset link to your email."
                        : (signup ? "Create a private account for your fishing history."
                        : "Sign in with your Fiskentra email and password."),
                13, TEXT, Typeface.NORMAL));
        form.addView(spacer(14));

        EditText name = null;
        if (signup) {
            name = authField("Display name", InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            name.setText(authNameDraft);
            form.addView(name, matchWrap());
            form.addView(spacer(10));
        }
        EditText email = authField("Email address", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(authEmailDraft);
        form.addView(email, matchWrap());

        EditText password = null;
        EditText confirm = null;
        if (!recover) {
            form.addView(spacer(10));
            password = authField("Password · at least 8 characters", InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            form.addView(password, matchWrap());
            if (signup) {
                form.addView(spacer(10));
                confirm = authField("Confirm password", InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                form.addView(confirm, matchWrap());
            }
            CheckBox showPassword = new CheckBox(this);
            showPassword.setText("Show password");
            showPassword.setTextColor(MUTED);
            showPassword.setTextSize(12);
            EditText finalPassword = password;
            EditText finalConfirm = confirm;
            showPassword.setOnCheckedChangeListener((button, checked) -> {
                int type = InputType.TYPE_CLASS_TEXT | (checked
                        ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_TEXT_VARIATION_PASSWORD);
                finalPassword.setInputType(type);
                finalPassword.setSelection(finalPassword.length());
                if (finalConfirm != null) {
                    finalConfirm.setInputType(type);
                    finalConfirm.setSelection(finalConfirm.length());
                }
            });
            form.addView(showPassword, matchWrap());
        } else {
            form.addView(spacer(14));
        }

        if (!authStatus.isEmpty()) {
            form.addView(text(authBusy ? "Please wait…" : authStatus, 12,
                    authBusy ? WARNING : (authStatusError ? DANGER : MUTED), Typeface.NORMAL));
            form.addView(spacer(12));
        }

        Button submit = primaryButton(authBusy ? "PLEASE WAIT…"
                : (recover ? "SEND RESET LINK" : (signup ? "CREATE ACCOUNT" : "SIGN IN")));
        submit.setEnabled(!authBusy);
        EditText finalName = name;
        EditText finalPassword = password;
        EditText finalConfirm = confirm;
        submit.setOnClickListener(v -> submitAuthForm(signup, recover,
                finalName, email, finalPassword, finalConfirm));
        form.addView(submit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        if (!recover) {
            form.addView(spacer(10));
            TextView or = centerText("OR", 10, MUTED, Typeface.BOLD);
            form.addView(or);
            form.addView(spacer(10));
            Button google = smallButton("CONTINUE WITH GOOGLE · COMING SOON");
            google.setOnClickListener(v -> comingSoon("Google sign-in"));
            form.addView(google, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        }

        if (!signup && !recover) {
            form.addView(spacer(8));
            Button forgot = smallButton("FORGOT PASSWORD?");
            forgot.setEnabled(!authBusy);
            forgot.setOnClickListener(v -> {
                authEmailDraft = email.getText().toString().trim();
                openAuthForm("recover");
            });
            form.addView(forgot, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
            form.addView(spacer(8));
            Button resend = smallButton("RESEND CONFIRMATION EMAIL");
            resend.setEnabled(!authBusy);
            resend.setOnClickListener(v -> resendConfirmation(email));
            form.addView(resend, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        }
        form.addView(spacer(8));
        Button back = smallButton(recover ? "BACK TO SIGN IN" : "BACK");
        back.setEnabled(!authBusy);
        back.setOnClickListener(v -> openAuthForm(recover ? "signin" : "landing"));
        form.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        return form;
    }

    private View checkEmailCard() {
        LinearLayout card = card();
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.fiskentra_wordmark);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        card.addView(logo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
        TextView title = centerText("Check your email", 27, TEXT, Typeface.BOLD);
        card.addView(title);
        card.addView(spacer(10));
        TextView message = centerText("We sent a secure confirmation link to\n"
                + (authEmailDraft.isEmpty() ? "your email address" : authEmailDraft)
                + "\n\nOpen the newest message to continue account setup.",
                14, MUTED, Typeface.NORMAL);
        card.addView(message);
        card.addView(spacer(14));
        card.addView(centerText("EMAIL SENT", 12, SUCCESS, Typeface.BOLD));
        card.addView(spacer(18));
        Button resend = primaryButton("RESEND LINK");
        resend.setOnClickListener(v -> {
            EditText email = authField("Email address", InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            email.setText(authEmailDraft);
            resendConfirmation(email);
        });
        card.addView(resend, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        card.addView(spacer(9));
        Button change = smallButton("USE A DIFFERENT EMAIL");
        change.setOnClickListener(v -> openAuthForm("signup"));
        card.addView(change, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        return card;
    }

    private void submitAuthForm(boolean signup, boolean recover, EditText name,
                                EditText email, EditText password, EditText confirm) {
        String emailValue = email.getText().toString().trim();
        authEmailDraft = emailValue;
        if (!validEmail(emailValue)) {
            email.setError("Enter a valid email address");
            return;
        }
        if (recover) {
            startAuthAction("Sending reset email…");
            authManager.requestPasswordReset(emailValue, this::finishAuthAction);
            return;
        }
        String passwordValue = password.getText().toString();
        if (passwordValue.length() < 8) {
            password.setError("Use at least 8 characters");
            return;
        }
        String nameValue = name == null ? "" : name.getText().toString().trim();
        authNameDraft = nameValue;
        if (signup && (nameValue.isEmpty() || nameValue.length() > 50)) {
            name.setError("Use 1–50 characters");
            return;
        }
        if (signup && !passwordValue.equals(confirm.getText().toString())) {
            confirm.setError("Passwords do not match");
            return;
        }
        startAuthAction(signup ? "Creating account…" : "Signing in…");
        if (signup) authManager.signUp(emailValue, passwordValue, nameValue, this::finishAuthAction);
        else authManager.signIn(emailValue, passwordValue, this::finishAuthAction);
    }

    private void resendConfirmation(EditText email) {
        String value = email.getText().toString().trim();
        authEmailDraft = value;
        if (!validEmail(value)) {
            email.setError("Enter your account email first");
            return;
        }
        startAuthAction("Sending confirmation email…");
        authManager.resendConfirmation(value, this::finishAuthAction);
    }

    private View passwordResetCard() {
        LinearLayout form = card();
        form.addView(text("SET A NEW PASSWORD", 11, ACCENT, Typeface.BOLD));
        form.addView(spacer(7));
        form.addView(text("Your reset link is verified. Choose a new password for this account.",
                13, TEXT, Typeface.NORMAL));
        form.addView(spacer(14));
        EditText password = authField("New password · at least 8 characters",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText confirm = authField("Confirm new password",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(password, matchWrap());
        form.addView(spacer(10));
        form.addView(confirm, matchWrap());
        form.addView(spacer(14));
        Button save = primaryButton(authBusy ? "SAVING…" : "SAVE NEW PASSWORD");
        save.setEnabled(!authBusy);
        save.setOnClickListener(v -> {
            String value = password.getText().toString();
            if (value.length() < 8) {
                password.setError("Use at least 8 characters");
                return;
            }
            if (!value.equals(confirm.getText().toString())) {
                confirm.setError("Passwords do not match");
                return;
            }
            startAuthAction("Changing password…");
            authManager.updatePassword(value, (success, message) -> {
                if (success) authFormMode = "landing";
                finishAuthAction(success, message);
            });
        });
        form.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return form;
    }

    private void openAuthForm(String mode) {
        authFormMode = mode;
        authStatusError = false;
        authStatus = "landing".equals(mode) ? "Local mode · sign in is optional" : "";
        render("profile");
    }

    private void startAuthAction(String message) {
        authBusy = true;
        authStatusError = false;
        authStatus = message;
        render("profile");
    }

    private void showDisplayNameDialog(String currentName) {
        EditText name = authField("Display name", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setText(currentName);
        name.setSelection(name.length());
        LinearLayout form = vertical();
        form.setPadding(dp(20), dp(8), dp(20), 0);
        form.addView(name, matchWrap());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Edit profile")
                .setView(form)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String value = name.getText().toString().trim();
                    if (value.isEmpty() || value.length() > 50) {
                        name.setError("Use 1–50 characters");
                        return;
                    }
                    dialog.dismiss();
                    authBusy = true;
                    authStatus = "Updating profile…";
                    render("profile");
                    authManager.updateDisplayName(value, this::finishAuthAction);
                }));
        dialog.show();
    }

    private void confirmSignOut() {
        new AlertDialog.Builder(this)
                .setTitle("Sign out?")
                .setMessage("Local saved points, the journal and Flic pairing will remain on this phone.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SIGN OUT", (dialog, which) -> {
                    authBusy = true;
                    authStatus = "Signing out…";
                    render("profile");
                    authManager.signOut(this::finishAuthAction);
                })
                .show();
    }

    private void finishAuthAction(boolean success, String message) {
        runOnUiThread(() -> {
            if (destroyed) return;
            authBusy = false;
            authStatus = message;
            authStatusError = !success;
            if (success && "signup".equals(authFormMode) && authManager.session() == null) {
                authFormMode = "checkEmail";
            } else if (success && "recover".equals(authFormMode)) {
                authFormMode = "resetSent";
            } else if (success && "reset".equals(authFormMode)) {
                authFormMode = "landing";
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            render("profile");
        });
    }

    private static boolean isAuthRedirect(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        return data != null && "com.fiskentra.app".equalsIgnoreCase(data.getScheme())
                && "auth".equalsIgnoreCase(data.getHost());
    }

    private void handleAuthRedirect(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null) return;
        Intent consumed = new Intent(intent);
        consumed.setData(null);
        setIntent(consumed);
        boolean recovery = "recovery".equalsIgnoreCase(authLinkValue(data, "type"));
        authFormMode = recovery ? "reset" : "landing";
        authBusy = true;
        authStatusError = false;
        authStatus = recovery ? "Verifying reset link…" : "Verifying email…";
        render("profile");
        authManager.completeAuthRedirect(data, (success, message) -> runOnUiThread(() -> {
            if (destroyed) return;
            authBusy = false;
            authStatus = message;
            authStatusError = !success;
            if (!success && recovery) authFormMode = "recover";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            render("profile");
        }));
    }

    private static String authLinkValue(Uri uri, String key) {
        String query = uri.getQueryParameter(key);
        if (query != null && !query.isEmpty()) return query;
        String fragment = uri.getFragment();
        if (fragment == null || fragment.isEmpty()) return "";
        for (String part : fragment.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && key.equals(Uri.decode(pair[0]))) return Uri.decode(pair[1]);
        }
        return "";
    }

    private EditText authField(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(MUTED);
        field.setTextColor(TEXT);
        field.setTextSize(14);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setPadding(dp(13), dp(11), dp(13), dp(11));
        field.setBackground(roundRect(SURFACE_2, BORDER, 1, 7));
        return field;
    }

    private static boolean validEmail(String value) {
        int at = value.indexOf('@');
        return at > 0 && at < value.length() - 3 && value.indexOf('.', at) > at + 1;
    }

    private static String profileInitial(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? "F" : trimmed.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private View deviceScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(body);
        body.addView(pageTitle("Field button", "FLIC 2 · OFFICIAL SDK"));
        body.addView(spacer(14));

        ImageView flicHero = imageCard(R.drawable.flic2_setup_hero, "Flic 2 field button");
        body.addView(flicHero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));
        body.addView(spacer(14));

        LinearLayout status = card();
        status.addView(text(flicConnected ? "●  CONNECTED" : "○  NOT CONNECTED", 11,
                flicConnected ? ACCENT : MUTED, Typeface.BOLD));
        status.addView(spacer(8));
        status.addView(text(connectedDevice.isEmpty() ? "Flic 2" : connectedDevice, 24, TEXT, Typeface.BOLD));
        deviceStatusText = text(bleStatus, 13, MUTED, Typeface.NORMAL);
        status.addView(deviceStatusText);
        status.addView(spacer(8));
        status.addView(text(cloudSyncStatus, 12, cloudSyncColor(), Typeface.BOLD));
        status.addView(spacer(8));
        status.addView(text(FiskentraFlicService.isRunning()
                        ? "●  BACKGROUND CAPTURE ACTIVE"
                        : "○  BACKGROUND CAPTURE STARTING",
                12, FiskentraFlicService.isRunning() ? SUCCESS : WARNING, Typeface.BOLD));
        status.addView(spacer(16));
        Button scan = primaryButton(flicManager.pairedButtonCount() == 0
                ? "PAIR FLIC 2" : "PAIR ANOTHER FLIC 2");
        scan.setOnClickListener(v -> {
            if (!flicManager.hasPermissions()) { requestNeededPermissions(); return; }
            flicManager.pairNewButton();
        });
        status.addView(scan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        status.addView(spacer(9));
        Button guided = smallButton("GUIDED FLIC 2 SETUP");
        guided.setOnClickListener(v -> {
            flicSetupStep = flicManager.pairedButtonCount() > 0 ? 2 : 0;
            flicSingleTested = false;
            flicDoubleTested = false;
            flicHoldTested = false;
            render("flicSetup");
        });
        status.addView(guided, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        body.addView(status);

        body.addView(sectionTitle("PAIRING GUIDE"));
        LinearLayout guide = card();
        guide.addView(text("1  Keep Bluetooth on and the Flic 2 nearby.", 13, TEXT, Typeface.NORMAL));
        guide.addView(spacer(8));
        guide.addView(text("2  Tap PAIR FLIC 2, then hold the button for 6 seconds until it glows.", 13, TEXT, Typeface.NORMAL));
        guide.addView(spacer(8));
        guide.addView(text("3  Accept Android's Pair & connect dialog, then test all three presses below.", 13, TEXT, Typeface.NORMAL));
        guide.addView(spacer(10));
        guide.addView(text("The Flic Android app can remain installed. Fiskentra stores its own SDK pairing.", 12, MUTED, Typeface.NORMAL));
        body.addView(guide);

        body.addView(sectionTitle("BUTTON FLOW TEST"));
        LinearLayout testCard = card();
        testCard.addView(text("Test the Fiskentra action mapping", 17, TEXT, Typeface.BOLD));
        testCard.addView(text("Single press registers a catch. Double press saves a waypoint. Hold marks a tackle change.", 13, MUTED, Typeface.NORMAL));
        testCard.addView(spacer(14));
        LinearLayout testActions = row();
        Button single = smallButton("SINGLE PRESS");
        single.setOnClickListener(v -> handleButtonPress(POINT_TYPE_CATCH, "Single press registered a catch"));
        testActions.addView(single, weighted());
        testActions.addView(spaceWide());
        Button doublePress = smallButton("DOUBLE PRESS");
        doublePress.setOnClickListener(v -> handleButtonPress(POINT_TYPE_WAYPOINT, "Double press saved a waypoint"));
        testActions.addView(doublePress, weighted());
        testCard.addView(testActions);
        testCard.addView(spacer(10));
        Button hold = smallButton("HOLD");
        hold.setOnClickListener(v -> handleButtonPress(POINT_TYPE_TACKLE_CHANGE, "Hold marked a tackle change"));
        testCard.addView(hold, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        testCard.addView(spacer(12));
        buttonEventText = text(lastButtonEvent, 12, MUTED, Typeface.NORMAL);
        testCard.addView(buttonEventText);
        body.addView(testCard);

        body.addView(sectionTitle("ACCOUNT & APP"));
        LinearLayout accountCard = card();
        accountCard.addView(text(authManager.isSignedIn() ? "ACCOUNT CONNECTED" : "LOCAL MODE",
                11, authManager.isSignedIn() ? SUCCESS : MUTED, Typeface.BOLD));
        accountCard.addView(spacer(6));
        accountCard.addView(text("Profile, privacy, units, map preferences and beta diagnostics.",
                13, TEXT, Typeface.NORMAL));
        accountCard.addView(spacer(12));
        LinearLayout accountActions = row();
        Button profile = smallButton(authManager.isSignedIn() ? "PROFILE" : "SIGN IN");
        profile.setOnClickListener(v -> render("profile"));
        accountActions.addView(profile, new LinearLayout.LayoutParams(0, dp(46), 1f));
        accountActions.addView(spaceWide());
        Button settings = smallButton("SETTINGS");
        settings.setOnClickListener(v -> render("settings"));
        accountActions.addView(settings, new LinearLayout.LayoutParams(0, dp(46), 1f));
        accountCard.addView(accountActions);
        body.addView(accountCard);

        body.addView(sectionTitle("PROTOTYPE STATUS"));
        body.addView(checkRow(true, "Official Flic 2 SDK pairing and reconnect"));
        body.addView(checkRow(FiskentraFlicService.isRunning(), "Foreground service for screen-off capture"));
        body.addView(checkRow(true, "GPS location saving"));
        body.addView(checkRow(true, "Local offline point storage"));
        body.addView(checkRow(true, "Mock single/double/hold actions"));
        body.addView(checkRow(true, "Real single/double/hold event callbacks"));
        body.addView(checkRow(true, "Stale queued press protection"));
        body.addView(checkRow(true, "Physical Flic 2 button test"));
        body.addView(checkRow(false, "Locked-phone field test"));
        return scroll;
    }

    private View checkRow(boolean ready, String label) {
        LinearLayout row = row(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(8), 0, dp(8));
        TextView icon = text(ready ? "✓" : "→", 14, ready ? ACCENT : MUTED, Typeface.BOLD);
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(text(label, 13, ready ? TEXT : MUTED, Typeface.NORMAL));
        return row;
    }

    public boolean saveCurrentMoment(String source) {
        Location location = lastLocation != null ? lastLocation : locationManager.getLastLocation();
        if (!locationManager.hasPermission()) {
            requestNeededPermissions();
            Toast.makeText(this, "Allow location first", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (location == null) {
            Toast.makeText(this, "Waiting for GPS fix — try again in a moment", Toast.LENGTH_SHORT).show();
            locationManager.start();
            return false;
        }
        SavedPoint point = pointStore.add(location.getLatitude(), location.getLongitude(), source, "");
        Toast.makeText(this, "Moment saved · " + source, Toast.LENGTH_SHORT).show();
        enrichWeatherThenSync(point);
        if ("map".equals(screen) || "saved".equals(screen) || "log".equals(screen)) render(screen);
        return true;
    }

    private void enrichWeatherThenSync(SavedPoint point) {
        weatherClient.fetch(point.latitude, point.longitude, (weather, weatherMessage) -> {
            SavedPoint updated = weather == null ? point : pointStore.updateWeather(point.id, weather);
            if (updated == null) updated = point;
            if (destroyed) return;
            SavedPoint ready = updated;
            runOnUiThread(() -> {
                syncPoint(ready);
                if (weather != null && ("saved".equals(screen) || "map".equals(screen)
                        || "log".equals(screen) || "home".equals(screen))) {
                    render(screen);
                }
            });
        });
    }

    private void refreshPointWeather(SavedPoint point) {
        Toast.makeText(this, "Getting weather for this point…", Toast.LENGTH_SHORT).show();
        weatherClient.fetch(point.latitude, point.longitude, (weather, message) -> {
            if (destroyed) return;
            if (weather == null) {
                runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
                return;
            }
            SavedPoint updated = pointStore.updateWeather(point.id, weather);
            runOnUiThread(() -> {
                Toast.makeText(this, "Weather updated · " + weatherSummary(weather),
                        Toast.LENGTH_SHORT).show();
                if (updated != null && shouldSync(updated.id)) syncPoint(updated);
                render("saved");
            });
        });
    }

    private void captureFishingDayWeather(FishingDay day, boolean start) {
        Location location = lastLocation != null ? lastLocation : locationManager.getLastLocation();
        if (location == null) {
            Toast.makeText(this, "Weather needs a GPS fix", Toast.LENGTH_SHORT).show();
            return;
        }
        weatherClient.fetch(location.getLatitude(), location.getLongitude(), (weather, message) -> {
            if (destroyed) return;
            if (weather == null) {
                runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
                return;
            }
            if (start) fishingDayStore.updateStartWeather(day.id, weather);
            else fishingDayStore.updateEndWeather(day.id, weather);
            runOnUiThread(() -> {
                if ("log".equals(screen) || "home".equals(screen)) render(screen);
            });
        });
    }

    private void captureTripWeather(boolean start, long tripId) {
        Location location = lastLocation != null ? lastLocation : locationManager.getLastLocation();
        if (location == null) return;
        weatherClient.fetch(location.getLatitude(), location.getLongitude(), (weather, message) -> {
            if (destroyed) return;
            if (weather == null) return;
            if (trackStore.startedAt() != tripId) return;
            if (start) trackStore.updateStartWeather(weather);
            else trackStore.updateEndWeather(weather);
            runOnUiThread(() -> {
                if ("home".equals(screen)) render("home");
            });
        });
    }

    private void syncPoint(SavedPoint point) {
        boolean online = syncQueue.hasValidatedInternet();
        cloudSyncStatus = online
                ? "Latest point queued for automatic sync"
                : "Latest point saved locally · automatic retry queued";
        syncQueue.enqueue(point);
        if ("home".equals(screen) || "saved".equals(screen) || "device".equals(screen)) render(screen);
    }

    private void syncPendingPoints() {
        List<SavedPoint> points = pointStore.all();
        int pending = pendingSyncCount(points);
        if (pending == 0) {
            cloudSyncStatus = "All local points are synced to cloud";
            Toast.makeText(this, "All points are already synced", Toast.LENGTH_SHORT).show();
            render("saved");
            return;
        }
        if (!syncQueue.hasValidatedInternet()) {
            cloudSyncStatus = pending + (pending == 1
                    ? " local point queued · waiting for internet"
                    : " local points queued · waiting for internet");
            Toast.makeText(this, "Offline · automatic retry is ready", Toast.LENGTH_SHORT).show();
        } else {
            cloudSyncStatus = "Syncing " + pending
                    + (pending == 1 ? " local point…" : " local points…");
            Toast.makeText(this, "Automatic sync started", Toast.LENGTH_SHORT).show();
            syncQueue.retryPending();
        }
        render("saved");
    }

    private void deletePoint(SavedPoint point) {
        setSyncState(point.id, SYNC_DELETING, "Deleting from cloud...");
        cloudSyncStatus = "Deleting from cloud...";
        Toast.makeText(this, "Deleting from cloud...", Toast.LENGTH_SHORT).show();
        render("saved");
        syncQueue.delete(point, (deleted, message) -> runOnUiThread(() -> {
            if (deleted) {
                if (point.catchDetails != null) {
                    deleteLocalCatchPhoto(point.catchDetails.localPhotoPath);
                }
                pointStore.delete(point.id);
                clearSyncState(point.id);
                if (selectedMapPointId == point.id) selectedMapPointId = -1L;
                cloudSyncStatus = "Deleted from cloud and this device";
                Toast.makeText(this, "Deleted from cloud", Toast.LENGTH_SHORT).show();
            } else {
                setSyncState(point.id, SYNC_DELETE_FAILED, "Cloud delete failed · try again");
                cloudSyncStatus = "Cloud delete failed · point kept locally";
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            }
            render("saved");
        }));
    }

    private int pendingSyncCount(List<SavedPoint> points) {
        int count = 0;
        for (SavedPoint point : points) {
            if (shouldSync(point.id)) count++;
        }
        return count;
    }

    private boolean shouldSync(long pointId) {
        String state = syncPrefs.getString(syncKey(pointId, "state"), "");
        return !SYNC_SYNCED.equals(state)
                && !SYNC_SYNCING.equals(state)
                && !SYNC_DELETING.equals(state)
                && !SYNC_DELETE_FAILED.equals(state);
    }

    private void openPointOnMap(SavedPoint point) {
        selectedMapPointId = point.id;
        showingWeatherPage = false;
        Toast.makeText(this, "Opening saved point on map", Toast.LENGTH_SHORT).show();
        render("map");
    }

    private SavedPoint selectedMapPoint() {
        if (selectedMapPointId < 0) return null;
        for (SavedPoint point : pointStore.all()) {
            if (point.id == selectedMapPointId) return point;
        }
        selectedMapPointId = -1L;
        return null;
    }

    private void setSyncState(long pointId, String state, String message) {
        syncPrefs.edit()
                .putString(syncKey(pointId, "state"), state)
                .putString(syncKey(pointId, "message"), message)
                .putLong(syncKey(pointId, "updated_at"), System.currentTimeMillis())
                .apply();
    }

    private void clearSyncState(long pointId) {
        syncPrefs.edit()
                .remove(syncKey(pointId, "state"))
                .remove(syncKey(pointId, "message"))
                .remove(syncKey(pointId, "updated_at"))
                .apply();
    }

    private void recoverStaleSyncStates(List<SavedPoint> points) {
        long now = System.currentTimeMillis();
        for (SavedPoint point : points) {
            if (!SYNC_SYNCING.equals(syncPrefs.getString(syncKey(point.id, "state"), ""))) continue;
            long updatedAt = syncPrefs.getLong(syncKey(point.id, "updated_at"), point.timestamp);
            if (now - updatedAt > 20_000L) {
                setSyncState(point.id, SYNC_FAILED, "sync interrupted · retry when online");
            }
        }
    }

    private String syncLabel(long pointId) {
        String state = syncPrefs.getString(syncKey(pointId, "state"), "");
        if (SYNC_SYNCED.equals(state)) return "●  Synced to cloud";
        if (SYNC_SYNCING.equals(state)) return "○  Syncing to Supabase…";
        if (SYNC_DELETING.equals(state)) return "○  Deleting from cloud...";
        if (SYNC_DELETE_FAILED.equals(state)) return "●  Cloud delete failed · try again";
        if (SYNC_FAILED.equals(state)) {
            String message = syncPrefs.getString(syncKey(pointId, "message"), "cloud sync pending");
            return "●  Saved locally · " + message;
        }
        return "○  Saved locally";
    }

    private int syncColor(long pointId) {
        String state = syncPrefs.getString(syncKey(pointId, "state"), "");
        if (SYNC_SYNCED.equals(state)) return SUCCESS;
        if (SYNC_SYNCING.equals(state)) return WARNING;
        if (SYNC_DELETING.equals(state)) return WARNING;
        if (SYNC_DELETE_FAILED.equals(state)) return DANGER;
        if (SYNC_FAILED.equals(state)) return DANGER;
        return MUTED;
    }

    private int cloudSyncColor() {
        String value = cloudSyncStatus.toLowerCase(Locale.ROOT);
        if (value.contains("synced")) return SUCCESS;
        if (value.contains("pending") || value.contains("failed")) return DANGER;
        if (value.contains("syncing") || value.contains("deleting")) return WARNING;
        if (value.contains("deleted")) return SUCCESS;
        return MUTED;
    }

    private boolean shouldShowDeleteStatus() {
        String value = cloudSyncStatus.toLowerCase(Locale.ROOT);
        return value.contains("deleting") || value.contains("delete") || value.contains("deleted");
    }

    private String syncKey(long pointId, String field) {
        return pointId + "_" + field;
    }

    private void handleButtonPress(String source, String message) {
        boolean saved = saveCurrentMoment(source);
        String status = saved ? message : "Button press received · waiting for GPS";
        lastButtonEvent = status + " · " + nowTime();
        bleStatus = status;
        if (deviceStatusText != null) deviceStatusText.setText(bleStatus);
        if (buttonEventText != null) buttonEventText.setText(lastButtonEvent);
    }

    @Override public void onLocation(Location location) {
        runOnUiThread(() -> {
            boolean firstLocation = lastLocation == null;
            lastLocation = location;
            if (trackStore.isActive()) trackStore.add(location);
            if (activeMapView != null && !"tripSummary".equals(screen)) {
                activeMapView.setData(lastLocation, pointStore.all(), trackStore.points(), selectedMapPoint());
            } else if ("map".equals(screen) && showingWeatherPage && !forecastLoading) {
                if (weatherForecast != null && !forecastCovers(location)) {
                    weatherForecast = null;
                    forecastAttempted = false;
                }
                if (weatherForecast == null && !forecastAttempted) loadForecast(false);
            } else if ("home".equals(screen)) {
                if (weatherForecast == null && !forecastAttempted && !forecastLoading) loadForecast(false);
                else if (firstLocation) render("home");
            }
        });
    }

    @Override public void onStatus(String status) {
        runOnUiThread(() -> {
            bleStatus = status;
            if (deviceStatusText != null) deviceStatusText.setText(status);
            if("flicSetup".equals(screen)&&design!=null&&content!=null)render(screen);
        });
    }

    @Override public void onButtonChanged(String name, String address, boolean connected) {
        runOnUiThread(() -> {
            flicConnected = connected;
            String suffix = address == null ? "" : address.substring(Math.max(0, address.length() - 5));
            connectedDevice = suffix.isEmpty() ? name : name + " · " + suffix;
            if (connected && content != null) content.post(this::ensureBackgroundService);
            if ("device".equals(screen) || "home".equals(screen) || "flicSetup".equals(screen)) render(screen);
        });
    }

    @Override public void onAction(FiskentraFlic2Manager.Action action) {
        if (FiskentraFlicService.isRunning()) return;
        runOnUiThread(() -> {
            switch (action) {
                case CATCH:
                    handleButtonPress(POINT_TYPE_CATCH,
                            "Single press registered a catch · foreground fallback");
                    break;
                case WAYPOINT:
                    handleButtonPress(POINT_TYPE_WAYPOINT,
                            "Double press saved a waypoint · foreground fallback");
                    break;
                case TACKLE_CHANGE:
                    handleButtonPress(POINT_TYPE_TACKLE_CHANGE,
                            "Hold marked a tackle change · foreground fallback");
                    break;
            }
        });
    }

    @Override public void onActionResult(
            FiskentraFlic2Manager.Action action, boolean saved, String message) {
        runOnUiThread(() -> {
            bleStatus = message;
            lastButtonEvent = message + " · " + nowTime();
            if (deviceStatusText != null) deviceStatusText.setText(bleStatus);
            if (buttonEventText != null) buttonEventText.setText(lastButtonEvent);
            if (saved && ("home".equals(screen) || "map".equals(screen)
                    || "log".equals(screen) || "saved".equals(screen))) {
                render(screen);
            }
        });
    }

    @Override public void onStaleEventIgnored() {
        runOnUiThread(() -> {
            bleStatus = "Old queued Flic press ignored safely";
            lastButtonEvent = "Ignored a queued press older than 15 seconds · " + nowTime();
            if (deviceStatusText != null) deviceStatusText.setText(bleStatus);
            if (buttonEventText != null) buttonEventText.setText(lastButtonEvent);
        });
    }

    private View pageTitle(String title, String eyebrow) {
        LinearLayout box = vertical();
        LinearLayout brandRow = row();
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView wordmark = new ImageView(this);
        wordmark.setImageResource(R.drawable.fiskentra_wordmark);
        wordmark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        wordmark.setContentDescription("Fiskentra home");
        wordmark.setOnClickListener(v -> render("home"));
        brandRow.addView(wordmark, new LinearLayout.LayoutParams(dp(132), dp(42)));
        TextView version = text("v" + BuildConfig.VERSION_NAME, 9, MUTED, Typeface.BOLD);
        version.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        brandRow.addView(version, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        box.addView(brandRow);
        box.addView(spacer(6));
        box.addView(text(eyebrow, 11, ACCENT, Typeface.BOLD));
        box.addView(text(title, 30, TEXT, Typeface.BOLD));
        return box;
    }

    private TextView centerText(String value, int sp, int color, int style) {
        TextView valueView = text(value, sp, color, style);
        valueView.setGravity(Gravity.CENTER);
        return valueView;
    }

    private View infoCard(String title, String description) {
        LinearLayout info = card();
        info.addView(text(title, 11, ACCENT, Typeface.BOLD));
        info.addView(spacer(6));
        info.addView(text(description, 13, TEXT, Typeface.NORMAL));
        return info;
    }

    private View settingLine(String label, String value) {
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(11), 0, dp(11));
        TextView labelView = text(label, 13, TEXT, Typeface.NORMAL);
        line.addView(labelView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueView = text(value, 12, MUTED, Typeface.BOLD);
        valueView.setGravity(Gravity.END);
        line.addView(valueView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f));
        return line;
    }

    private View toggleLine(String label, boolean enabled, String status) {
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(10), 0, dp(10));
        line.addView(text(label, 13, TEXT, Typeface.NORMAL), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView statusView = text(status, 10, enabled ? SUCCESS : MUTED, Typeface.BOLD);
        statusView.setGravity(Gravity.END);
        line.addView(statusView);
        return line;
    }

    private Button fieldAction(String title, String subtitle, int color) {
        Button action = new Button(this);
        action.setAllCaps(false);
        action.setText(title + "\n" + subtitle);
        action.setTextColor(color);
        action.setTextSize(16);
        action.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        action.setGravity(Gravity.CENTER);
        action.setBackground(roundRect(Color.argb(85, Color.red(color),
                Color.green(color), Color.blue(color)), color, 1, 7));
        return action;
    }

    private Button dangerButton(String label) {
        Button button = smallButton(label);
        button.setTextColor(DANGER);
        button.setBackground(roundRect(Color.rgb(52, 24, 24), DANGER, 1, 7));
        return button;
    }

    private View toolButton(String title, String subtitle) {
        LinearLayout tool = vertical();
        tool.setGravity(Gravity.CENTER);
        tool.setPadding(dp(5), dp(10), dp(5), dp(10));
        tool.setBackground(roundRect(SURFACE, BORDER, 1, 7));
        tool.addView(centerText(title, 10, ACCENT, Typeface.BOLD));
        tool.addView(centerText(subtitle, 9, MUTED, Typeface.NORMAL));
        tool.setOnClickListener(v -> comingSoon(title));
        return tool;
    }

    private View permissionCard(String title, String description, boolean allowed) {
        LinearLayout permission = card();
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = vertical();
        copy.addView(text(title, 16, TEXT, Typeface.BOLD));
        copy.addView(text(description, 11, MUTED, Typeface.NORMAL));
        line.addView(copy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView status = text(allowed ? "ALLOWED" : "NOT ALLOWED", 10,
                allowed ? SUCCESS : WARNING, Typeface.BOLD);
        line.addView(status);
        permission.addView(line);
        return permission;
    }

    private View progressSegments(int current, int count) {
        LinearLayout progress = row();
        for (int index = 0; index < count; index++) {
            View segment = new View(this);
            segment.setBackground(roundRect(index <= current ? ACCENT : BORDER, 2));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(4), 1f);
            if (index > 0) params.setMargins(dp(5), 0, 0, 0);
            progress.addView(segment, params);
        }
        return progress;
    }

    private View flicTestAction(String title, String subtitle, int color,
                                boolean passed, Runnable action) {
        LinearLayout card = card();
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = vertical();
        copy.addView(text(title, 16, color, Typeface.BOLD));
        copy.addView(text(subtitle, 12, TEXT, Typeface.NORMAL));
        line.addView(copy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(text(passed ? "PASSED" : "TEST", 11,
                passed ? SUCCESS : ACCENT, Typeface.BOLD));
        card.addView(line);
        card.setOnClickListener(v -> action.run());
        return card;
    }

    private void comingSoon(String feature) {
        Toast.makeText(this, feature + " · coming soon", Toast.LENGTH_SHORT).show();
    }

    private TextView sectionTitle(String title) {
        TextView t = text(title, 11, MUTED, Typeface.BOLD);
        LinearLayout.LayoutParams lp = matchWrap(); lp.setMargins(0, dp(24), 0, dp(10));
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout vertical() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v;
    }

    private LinearLayout card() {
        LinearLayout v = vertical();
        v.setPadding(dp(16), dp(15), dp(16), dp(15));
        v.setBackground(roundRect(SURFACE, BORDER, 1, 9));
        return v;
    }

    private ImageView imageCard(int resource, String description) {
        ImageView image = new ImageView(this);
        image.setImageResource(resource);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription(description);
        image.setBackground(roundRect(SURFACE_2, BORDER, 1, 9));
        image.setClipToOutline(true);
        return image;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-condensed", style));
        t.setIncludeFontPadding(false); t.setLineSpacing(dp(2), 1.12f);
        return t;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(12); b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setBackground(roundRect(ACCENT, 7)); b.setGravity(Gravity.CENTER);
        return b;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label); b.setTextColor(TEXT); b.setTextSize(10); b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setBackground(roundRect(SURFACE_2, BORDER, 1, 7)); b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    private int markerColor(String type) {
        if (POINT_TYPE_CATCH.equals(type)) return SUCCESS;
        if (POINT_TYPE_WAYPOINT.equals(type)) return WARNING;
        if (POINT_TYPE_TACKLE_CHANGE.equals(type)) return Color.rgb(197, 155, 255);
        if ("Sighting".equals(type)) return Color.rgb(255, 142, 89);
        if ("Camp".equals(type)) return Color.rgb(104, 196, 255);
        if ("Hazard".equals(type)) return DANGER;
        if ("Map".equals(type)) return ACCENT;
        return Color.rgb(121, 227, 143);
    }

    private String markerLetter(String type) {
        if (POINT_TYPE_CATCH.equals(type)) return "C";
        if (POINT_TYPE_WAYPOINT.equals(type)) return "W";
        if (POINT_TYPE_TACKLE_CHANGE.equals(type)) return "T";
        if ("Sighting".equals(type)) return "S";
        if ("Camp".equals(type)) return "P";
        if ("Hazard".equals(type)) return "!";
        if ("Map".equals(type)) return "M";
        return "?";
    }

    private String shortTypeLabel(String type) {
        if (POINT_TYPE_TACKLE_CHANGE.equals(type)) return "Tackle";
        if (type == null || type.trim().isEmpty()) return "Other";
        return type;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d;
    }

    private GradientDrawable roundRect(int color, int strokeColor, int strokeDp, int radiusDp) {
        GradientDrawable d = roundRect(color, radiusDp);
        d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private View spacer(int height) {
        View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height))); return v;
    }

    private View spaceWide() {
        View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1)); return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams cardMargins() {
        LinearLayout.LayoutParams lp = matchWrap(); lp.setMargins(0, 0, 0, dp(12)); return lp;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static String formatCoords(double lat, double lon) {
        return String.format(Locale.US, "%.5f, %.5f", lat, lon);
    }

    private String weatherSummary(WeatherSnapshot weather) {
        return weather.condition() + " · " + formatTemperature(weather.temperatureC)
                + " · " + weather.windDirection() + " " + formatWind(weather.windSpeedKmh);
    }

    private String weatherDetails(WeatherSnapshot weather) {
        if (userPreferences.usesImperialUnits()) {
            return String.format(Locale.US,
                    "Feels %s · Humidity %d%% · Pressure %.2f inHg · Precipitation %.2f in",
                    formatTemperature(weather.apparentTemperatureC),
                    weather.humidityPercent,
                    weather.pressureHpa * 0.0295299830714d,
                    weather.precipitationMm / 25.4d);
        }
        return String.format(Locale.US,
                "Feels %s · Humidity %d%% · Pressure %.0f hPa · Precipitation %.1f mm",
                formatTemperature(weather.apparentTemperatureC),
                weather.humidityPercent,
                weather.pressureHpa,
                weather.precipitationMm);
    }

    private String catchSummary(CatchDetails details) {
        List<String> parts = new ArrayList<>();
        parts.add(details.species.isEmpty() ? "Unidentified catch" : details.species);
        if (details.lengthCm > 0d) {
            parts.add(String.format(Locale.US, userPreferences.usesImperialUnits()
                    ? "%.1f in" : "%.1f cm", displayLength(details.lengthCm)));
        }
        if (details.weightKg > 0d) {
            parts.add(String.format(Locale.US, userPreferences.usesImperialUnits()
                    ? "%.2f lb" : "%.2f kg", displayWeight(details.weightKg)));
        }
        parts.add(details.released ? "Released" : "Kept");
        return String.join(" · ", parts);
    }

    private String formatTemperature(double celsius) {
        double value = userPreferences.usesImperialUnits()
                ? celsius * 9d / 5d + 32d : celsius;
        return Math.round(value) + (userPreferences.usesImperialUnits() ? "°F" : "°C");
    }

    private String formatTemperatureRange(double minCelsius, double maxCelsius) {
        boolean imperial = userPreferences.usesImperialUnits();
        double min = imperial ? minCelsius * 9d / 5d + 32d : minCelsius;
        double max = imperial ? maxCelsius * 9d / 5d + 32d : maxCelsius;
        return Math.round(min) + "–" + Math.round(max) + (imperial ? "°F" : "°C");
    }

    private String formatWind(double kmh) {
        if (userPreferences.usesImperialUnits()) {
            return Math.round(kmh * 0.6213711922d) + " mph";
        }
        return Math.round(kmh) + " km/h";
    }

    private String formatPrecipitation(double millimetres) {
        if (userPreferences.usesImperialUnits()) {
            return String.format(Locale.US, "%.2f in", millimetres / 25.4d);
        }
        return String.format(Locale.US, "%.1f mm", millimetres);
    }

    private double displayLength(double centimetres) {
        return userPreferences.usesImperialUnits() ? centimetres / 2.54d : centimetres;
    }

    private double displayWeight(double kilograms) {
        return userPreferences.usesImperialUnits() ? kilograms * 2.2046226218d : kilograms;
    }

    private double storedLength(double displayedValue) {
        return userPreferences.usesImperialUnits() ? displayedValue * 2.54d : displayedValue;
    }

    private double storedWeight(double displayedValue) {
        return userPreferences.usesImperialUnits() ? displayedValue / 2.2046226218d : displayedValue;
    }

    private static String nowTime() {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
    }

    private static String formatDate(long time) {
        return new SimpleDateFormat("EEE, d MMM · HH:mm", Locale.US).format(new Date(time));
    }

    private static String formatForecastDate(String isoDate) {
        try {
            Date parsed = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate);
            if (parsed != null) {
                return new SimpleDateFormat("EEE, d MMM", Locale.US).format(parsed);
            }
        } catch (Exception ignored) { }
        return isoDate;
    }

    private static String forecastClock(String isoTime) {
        int separator = isoTime.indexOf('T');
        if (separator >= 0 && isoTime.length() >= separator + 6) {
            return isoTime.substring(separator + 1, separator + 6);
        }
        return isoTime;
    }

    private boolean forecastCovers(Location location) {
        if (weatherForecast == null || location == null) return false;
        float[] distance = new float[1];
        Location.distanceBetween(location.getLatitude(), location.getLongitude(),
                weatherForecast.latitude, weatherForecast.longitude, distance);
        return distance[0] <= 10_000f;
    }

    private static String formatTime(long time) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(time));
    }

    private static String formatLogDay(long time) {
        return new SimpleDateFormat("EEEE, d MMMM", Locale.US).format(new Date(time));
    }

    private static String formatDuration(long durationMs) {
        long totalMinutes = Math.max(0L, durationMs) / 60_000L;
        if (totalMinutes < 1L) return "<1 min";
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours == 0L) return minutes + " min";
        if (minutes == 0L) return hours + " h";
        return hours + " h " + minutes + " min";
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
