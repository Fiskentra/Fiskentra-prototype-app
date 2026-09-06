package com.fiskentra.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.fiskentra.app.R;
import com.fiskentra.app.model.*;
import com.fiskentra.app.weather.FishingAdvisor;
import java.text.SimpleDateFormat;
import java.util.*;

/** Native, reference-led presentation. Persistence and integrations remain in the host. */
public final class DesignScreens {
    public static final int BG = Color.rgb(0, 17, 30), PANEL = Color.rgb(2, 22, 37);
    public static final int LINE = Color.rgb(57, 65, 70), INK = Color.rgb(240, 232, 226);
    public static final int MUTED = Color.rgb(184, 174, 168), BLUE = Color.rgb(0, 158, 255);
    public static final int BUTTON = Color.rgb(0, 83, 211), LIME = Color.rgb(171, 211, 35);
    public static final int AMBER = Color.rgb(255, 181, 0), PURPLE = Color.rgb(199, 139, 243);
    public static final int RED = Color.rgb(255, 75, 36);

    public static final class State {
        public List<SavedPoint> points = Collections.emptyList();
        public List<FishingDay> days = Collections.emptyList();
        public WeatherForecast forecast;
        public Location location;
        public SavedPoint selected;
        public String species = "Pike", device = "Flic 2", status = "", authMode = "landing";
        public String email = "", name = "", authStatus = "", mapStyle = "outdoor-v4";
        public boolean connected, bluetooth, gps, gpsEnabled, notifications, active, signedIn, busy, authError, imperial;
        public boolean singleTest, doubleTest, holdTest;
        public boolean offlineDownloading, offlineComplete, offlineAvailable, offlineChecked;
        public int pending, setupStep, offlinePacks, offlineProgress;
        public long tripStarted, tripStopped, offlineBytes;
        public double distanceKm, offlineLatitude, offlineLongitude, offlineRadiusKm;
        public String offlineStatus = "Checking saved maps…", offlineStyle = "";
    }
    public interface Host {
        State read();
        void go(String screen);
        void command(String action);
        void species(String species);
        MapTilerMapView map();
        void point(SavedPoint point, String action);
        void trip(FishingDay day);
        void savePoint(SavedPoint point, String title, String type, String note);
        void saveCatch(SavedPoint point, CatchDetails details);
        String syncLabel(SavedPoint point);
        int syncColor(SavedPoint point);
        void auth(boolean signup, boolean recover, EditText name, EditText email, EditText password, EditText confirm);
        View legacy(String screen);
    }
    private final Activity a;
    private final Host host;
    private final Typeface regular, medium, semibold;
    private State s;
    private String route = "home", savedFilter = "All", savedQuery = "", journalFilter = "All";
    private boolean journalFeed, showPoints = true, showTrack = true;
    private int offlineRadiusKm = 5;
    private final Calendar month = Calendar.getInstance(Locale.US);
    private long selectedDay = 0;
    private SavedPoint editing;

    public DesignScreens(Activity activity, Host host) {
        this.a = activity; this.host = host;
        regular = a.getResources().getFont(R.font.barlow_condensed_regular);
        medium = a.getResources().getFont(R.font.barlow_condensed_medium);
        semibold = a.getResources().getFont(R.font.barlow_condensed_semibold);
    }
    public void edit(SavedPoint p) { editing = p; }
    public View create(String screen) {
        s = host.read(); route = screen;
        switch (screen) {
            case "map": return mapScreen(false, false);
            case "mapTools": return mapScreen(true, false);
            case "offlineMaps": return offlineMaps();
            case "tripActive": return mapScreen(false, true);
            case "log": return journal();
            case "saved": return saved();
            case "device": return devices();
            case "forecastDetail": return forecast(true);
            case "species": return species();
            case "tripSetup": return tripSetup();
            case "tripSummary": return summary();
            case "pointEdit": return pointEditor();
            case "catchEdit": return catchEditor();
            case "profile": return account();
            case "profileSetup": return profileSetup();
            case "flicRequired": return flicRequired();
            case "flicSetup": return flicSetup();
            case "onboarding": return welcome();
            case "home": return today();
            default: return host.legacy(screen);
        }
    }
    public View navigation(String screen) {
        LinearLayout bar = row(); bar.setBackgroundColor(BG);
        int[] icons = {R.drawable.ic_cloud_sun, R.drawable.ic_notebook, R.drawable.ic_bookmark, R.drawable.ic_bluetooth};
        String[] labels = {"Forecast / Map", "Journal", "Saved", "Devices"};
        String[] routes = {"home", "log", "saved", "device"};
        int selected = screen.equals("saved") || screen.equals("pointEdit") || screen.equals("catchEdit") ? 2
                : screen.equals("log") || screen.equals("tripSummary") ? 1
                : Arrays.asList("device", "settings", "beta", "profile").contains(screen) ? 3 : 0;
        for (int n = 0; n < 4; n++) {
            final String target = routes[n];
            LinearLayout tab = col(); tab.setGravity(Gravity.CENTER); tab.setContentDescription(labels[n]);
            tab.addView(icon(icons[n], n == selected ? BLUE : MUTED, 24));
            TextView label = text(labels[n], 11, n == selected ? BLUE : MUTED, false);
            label.setGravity(Gravity.CENTER); tab.addView(label);
            tab.setOnClickListener(v -> host.go(target));
            bar.addView(tab, new LinearLayout.LayoutParams(0, dp(56), 1));
        }
        return bar;
    }
    private LinearLayout base(boolean back, boolean tabs) {
        LinearLayout root = col(); root.setBackgroundColor(BG);
        root.addView(header(back), new LinearLayout.LayoutParams(-1, dp(48)));
        if (tabs) root.addView(topTabs(), new LinearLayout.LayoutParams(-1, dp(34)));
        return root;
    }
    private View header(boolean back) {
        LinearLayout bar = row(); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(10), 0, dp(6), 0);
        if (back) bar.addView(iconButton(R.drawable.ic_arrow_left, "Back", a::onBackPressed));
        else {
            ImageView brand = new ImageView(a); brand.setImageResource(R.drawable.fiskentra_wordmark);
            brand.setColorFilter(BLUE); brand.setScaleType(ImageView.ScaleType.FIT_CENTER);
            brand.setContentDescription("Fiskentra home"); brand.setOnClickListener(v -> host.go("home"));
            bar.addView(brand, new LinearLayout.LayoutParams(dp(110), dp(36)));
        }
        TextView location = text(route.equals("tripSummary")?(s.tripStarted==0?"No recorded trip":"Trip · "+date(s.tripStarted,"MMM d")):"Current location", 13, INK, false);
        location.setGravity(Gravity.CENTER); location.setMaxLines(1);
        location.setOnClickListener(v -> host.go("map"));
        bar.addView(location, new LinearLayout.LayoutParams(0, -1, 1));
        bar.addView(iconButton(R.drawable.ic_refresh, "Refresh weather", () -> host.command("refresh")));
        bar.addView(iconButton(R.drawable.ic_search, "Search saved items", () -> host.go("saved")));
        return bar;
    }
    private View topTabs() {
        LinearLayout tabs = row();
        String[] labels = {"Forecast", "Map", "To map"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i; boolean selected = i == 0 ? route.equals("home") : i == 1 && !route.equals("home");
            LinearLayout cell = col(); TextView label = text(labels[i], 13, selected ? BLUE : MUTED, false);
            label.setGravity(Gravity.CENTER); cell.addView(label, new LinearLayout.LayoutParams(-1, 0, 1));
            View underline = new View(a); underline.setBackgroundColor(selected ? BLUE : LINE);
            cell.addView(underline, new LinearLayout.LayoutParams(-1, dp(selected ? 2 : 1)));
            cell.setOnClickListener(v -> host.go(index == 0 ? "home" : "map"));
            tabs.addView(cell, new LinearLayout.LayoutParams(0, -1, 1));
        }
        return tabs;
    }
    private LinearLayout scrollBody(LinearLayout root, int padding) {
        ScrollView scroll = new ScrollView(a); scroll.setFillViewport(true);
        LinearLayout body = col(); body.setPadding(dp(padding), dp(8), dp(padding), dp(12));
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); return body;
    }
    private View forecast(boolean detailed) {
        if (detailed) return detailedForecast();
        LinearLayout root = base(detailed, !detailed), body = scrollBody(root, 10);
        WeatherSnapshot now = s.forecast == null ? null : s.forecast.current;
        LinearLayout hero = panel(8), title = row(); title.setGravity(Gravity.CENTER_VERTICAL);
        title.addView(icon(R.drawable.ic_fish, LIME, 40));
        LinearLayout fish = col(); fish.setPadding(dp(8), 0, 0, 0);
        fish.addView(text(s.species + "     " + bite(0), 18, INK, true));
        fish.addView(text(assessmentText(0), 12, MUTED, false));
        title.addView(fish, new LinearLayout.LayoutParams(0, -2, 1));
        title.addView(text(now == null ? "—" : temperature(now.temperatureC), 23, INK, false));
        title.addView(icon(weatherIcon(now == null ? -1 : now.weatherCode), AMBER, 28)); hero.addView(title);
        hero.setOnClickListener(v -> host.go("species")); body.addView(hero, gap(8));
        LinearLayout metrics = row();
        metrics.addView(metric(R.drawable.ic_gauge, "Pressure", now == null ? "—" : Math.round(now.pressureHpa) + " hPa"), weight());
        metrics.addView(metric(R.drawable.ic_wind, "Wind", now == null ? "—" : now.windDirection() + " " + Math.round(now.windSpeedKmh) + " km/h"), weight());
        metrics.addView(metric(R.drawable.ic_droplet, "Humidity", now == null ? "—" : now.humidityPercent + "%"), weight());
        metrics.addView(metric(R.drawable.ic_moon, "Moon", "Not available"), weight()); body.addView(metrics, gap(8));
        section(body, detailed ? "Bite activity" : "Bite forecast", "Next 5 days");
        body.addView(new ForecastChart(), new LinearLayout.LayoutParams(-1, dp(detailed ? 132 : 85)));
        section(body, "Weather outlook", s.forecast == null ? "Waiting for GPS" : "Open-Meteo");
        body.addView(dailyStrip(), gap(8));
        LinearLayout connectivity = row();
        connectivity.addView(tinyStatus(R.drawable.ic_bluetooth, s.connected ? "Flic 2 connected" : "Flic 2 offline", s.connected ? BLUE : MUTED), weight());
        connectivity.addView(tinyStatus(R.drawable.ic_map_pin, gps(), MUTED), weight());
        connectivity.addView(tinyStatus(R.drawable.ic_download, "Local capture", BLUE), weight()); body.addView(connectivity, gap(9));
        section(body, detailed ? "Factors" : "Fishing conditions", "Weather estimate");
        LinearLayout factors = panel(8);
        factors.addView(infoRow(R.drawable.ic_temperature, "Air temperature", now == null ? "Not loaded" : temperature(now.temperatureC), null));
        factors.addView(infoRow(R.drawable.ic_wind, "Wind", now == null ? "Not loaded" : now.windDirection() + " " + Math.round(now.windSpeedKmh) + " km/h", null));
        factors.addView(infoRow(R.drawable.ic_cloud_rain, "Precipitation", now == null ? "Not loaded" : String.format(Locale.US, "%.1f mm", now.precipitationMm), null));
        factors.addView(text("Weather-based estimate, not a catch guarantee. Hourly bite windows and water temperature are not available yet.", 12, MUTED, false));
        body.addView(factors, gap(8));
        if (s.forecast == null) body.addView(button("LOAD FORECAST", R.drawable.ic_refresh, true, () -> host.command("refresh")), gap(8));
        else body.addView(text("Updated " + date(s.forecast.fetchedAt, "HH:mm") + " · " + s.forecast.timezone, 11, MUTED, false), gap(8));
        LinearLayout actions = row();
        actions.addView(button(detailed ? "SPECIES" : "DETAILED FORECAST", R.drawable.ic_fish, false, () -> host.go(detailed ? "species" : "forecastDetail")), weight());
        body.addView(actions, gap(8));
        body.addView(button("START TRIP", R.drawable.ic_navigation, true, () -> host.go("tripSetup")), gap(0));
        return root;
    }
    private View detailedForecast() {
        LinearLayout root=base(true,false),body=scrollBody(root,10);
        LinearLayout heading=panel(5),range=row();range.setGravity(Gravity.CENTER_VERTICAL);
        range.addView(icon(R.drawable.ic_fish,LIME,26));
        TextView name=text(s.species,17,INK,true);name.setPadding(dp(6),0,dp(6),0);name.setOnClickListener(v->host.go("species"));range.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        for(String label:new String[]{"3 hours","24 hours","5 days"}){
            View choice=chip(label,label.equals("5 days"),()->{if(label.equals("5 days"))message("Daily outlook",assessmentText(0));else soon("Hourly fishing outlook");});
            range.addView(choice,new LinearLayout.LayoutParams(dp(58),dp(34)));
        }
        heading.addView(range);body.addView(heading,gap(8));
        section(body,"Bite activity (5 days)","Weather estimate");
        body.addView(new ForecastChart(),new LinearLayout.LayoutParams(-1,dp(110)));
        LinearLayout scores=row();for(int i=0;i<5;i++){LinearLayout scoreCell=col();scoreCell.setGravity(Gravity.CENTER);scoreCell.addView(icon(R.drawable.ic_fish,scoreColor(score(i)),24));scores.addView(scoreCell,weight());}body.addView(scores,gap(10));
        section(body,"Weather by hour","Coming soon");
        LinearLayout hourly=row();
        for(String time:new String[]{"09:00","12:00","15:00","18:00","21:00","00:00"}){
            LinearLayout cell=col();cell.setGravity(Gravity.CENTER);cell.addView(text(time,11,BLUE,false));cell.addView(icon(R.drawable.ic_cloud,MUTED,26));cell.addView(text("—",16,MUTED,false));
            for(int id:new int[]{R.drawable.ic_wind,R.drawable.ic_droplet,R.drawable.ic_gauge}){LinearLayout value=row();value.setGravity(Gravity.CENTER);value.addView(icon(id,MUTED,15));value.addView(text(" —",11,MUTED,false));cell.addView(value,gap(3));}
            hourly.addView(cell,weight());
        }
        body.addView(hourly,gap(6));body.addView(text("Hourly measurements are not available yet.",11,MUTED,false),gap(8));
        section(body,"5-day forecast",s.forecast==null?"Not loaded":"Open-Meteo");body.addView(dailyStrip(),gap(10));
        WeatherSnapshot current=s.forecast==null?null:s.forecast.current;
        section(body,"Factors",current==null?"Not loaded":"Current observations");
        LinearLayout factors=row(),left=panel(5),right=panel(5);
        left.addView(factor(R.drawable.ic_gauge,"Pressure",current==null?"Not loaded":Math.round(current.pressureHpa)+" hPa"));
        left.addView(factor(R.drawable.ic_wind,"Wind",current==null?"Not loaded":current.windDirection()+" "+Math.round(current.windSpeedKmh)+" km/h"));
        left.addView(factor(R.drawable.ic_cloud_rain,"Precipitation",current==null?"Not loaded":String.format(Locale.US,"%.1f mm",current.precipitationMm)));
        left.addView(factor(R.drawable.ic_temperature,"Water temperature","Not measured"));
        right.addView(factor(R.drawable.ic_moon,"Moon","Coming soon"));right.addView(factor(R.drawable.ic_compass,"Geomagnetic activity","Coming soon"));
        right.addView(factor(R.drawable.ic_waves,"Waves","Not measured"));right.addView(factor(R.drawable.ic_droplet,"Humidity",current==null?"Not loaded":current.humidityPercent+"%"));
        factors.addView(left,weight());factors.addView(right,weight());body.addView(factors,gap(10));
        body.addView(text("Daily scores use air weather, not measured fish activity. They are not a catch guarantee.",12,MUTED,false),gap(10));
        if(s.forecast==null)body.addView(button("LOAD FORECAST",R.drawable.ic_refresh,true,()->host.command("refresh")),gap(8));
        LinearLayout actions=row();actions.addView(button("CHANGE VIEW",0,false,()->host.go("home")),weight());actions.addView(button("DATA SOURCE",R.drawable.ic_help,false,()->message("Weather data",s.forecast==null?"Open-Meteo forecast has not been loaded. Connect to the internet and enable Location to load it.":"Open-Meteo\nUpdated "+date(s.forecast.fetchedAt,"MMM d, HH:mm")+"\n"+s.forecast.timezone+"\nPreviously loaded forecasts remain available offline.")),weight());body.addView(actions,gap(0));
        return root;
    }
    private View factor(int id,String title,String value){
        LinearLayout item=row();item.setGravity(Gravity.CENTER_VERTICAL);item.addView(icon(id,MUTED,22));LinearLayout copy=col();copy.setPadding(dp(5),dp(5),0,dp(5));copy.addView(text(title,12,INK,false));copy.addView(text(value,11,MUTED,false));item.addView(copy,new LinearLayout.LayoutParams(0,-2,1));return item;
    }
    private View today(){
        LinearLayout root=base(false,true),body=scrollBody(root,10);WeatherSnapshot now=s.forecast==null?null:s.forecast.current;
        LinearLayout hero=panel(7),line=row();line.setGravity(Gravity.CENTER_VERTICAL);line.addView(icon(R.drawable.ic_fish,LIME,40));LinearLayout species=col();species.setPadding(dp(8),0,0,0);species.addView(text(s.species+"    "+bite(0),17,INK,false));species.addView(text(assessmentText(0),12,MUTED,false));line.addView(species,new LinearLayout.LayoutParams(0,-2,1));line.addView(text(now==null?"—":temperature(now.temperatureC),21,INK,false));line.addView(icon(weatherIcon(now==null?-1:now.weatherCode),AMBER,28));hero.addView(line);hero.setOnClickListener(v->host.go("species"));body.addView(hero,gap(6));
        LinearLayout metrics=row();metrics.addView(metric(R.drawable.ic_gauge,"Pressure",now==null?"—":Math.round(now.pressureHpa)+" hPa"),weight());metrics.addView(metric(R.drawable.ic_wind,"Wind",now==null?"—":now.windDirection()+" "+Math.round(now.windSpeedKmh)+" km/h"),weight());metrics.addView(metric(R.drawable.ic_droplet,"Humidity",now==null?"—":now.humidityPercent+"%"),weight());metrics.addView(metric(R.drawable.ic_moon,"Moon","Coming soon"),weight());body.addView(metrics,gap(3));
        section(body,"Bite forecast","Hourly · coming soon");LinearLayout hours=row();for(String hour:new String[]{"09:00","12:00","15:00","18:00"}){LinearLayout cell=col();cell.setGravity(Gravity.CENTER);cell.addView(text(hour,13,BLUE,false));cell.addView(icon(R.drawable.ic_cloud,MUTED,28));cell.addView(text("Not available",11,MUTED,false));hours.addView(cell,weight());}body.addView(hours,gap(10));
        LinearLayout connection=row();connection.addView(tinyStatus(R.drawable.ic_bluetooth,s.connected?"Flic connected":"Flic offline",BLUE),weight());connection.addView(tinyStatus(R.drawable.ic_map_pin,gps(),MUTED),weight());connection.addView(tinyStatus(R.drawable.ic_download,"Local capture",BLUE),weight());body.addView(connection,gap(3));
        section(body,"Bite activity","Next 5 days");body.addView(new ForecastChart(),new LinearLayout.LayoutParams(-1,dp(74)));section(body,"Next 5 days",s.forecast==null?"Forecast not loaded":"Open-Meteo");body.addView(dailyStrip(),gap(5));
        if(s.forecast==null)body.addView(actionLink(R.drawable.ic_refresh,"LOAD FORECAST",BLUE,()->host.command("refresh")),gap(4));else body.addView(actionLink(R.drawable.ic_cloud_sun,"DETAILED FORECAST",BLUE,()->host.go("forecastDetail")),gap(4));
        LinearLayout footer=col();footer.setPadding(dp(10),dp(6),dp(10),dp(8));footer.addView(button("START TRIP",R.drawable.ic_navigation,true,()->host.go("tripSetup")));root.addView(footer);
        SwipeSwitchLayout swipe=new SwipeSwitchLayout(a);swipe.configure(false,new SwipeSwitchLayout.Listener(){public void onSwipeLeft(){host.go("map");}public void onSwipeRight(){host.go("map");}});swipe.addView(root,new FrameLayout.LayoutParams(-1,-1));return swipe;
    }
    private View dailyStrip() {
        LinearLayout days = row();
        if (s.forecast == null || s.forecast.days.isEmpty()) {
            TextView empty = text("Connect to load the forecast. Saved observations remain available offline.", 14, MUTED, false);
            empty.setPadding(dp(8), dp(20), dp(8), dp(20)); days.addView(empty); return days;
        }
        for (int i = 0; i < Math.min(5, s.forecast.days.size()); i++) {
            ForecastDay d = s.forecast.days.get(i); final int dayIndex = i;
            LinearLayout cell = col(); cell.setGravity(Gravity.CENTER); cell.setPadding(dp(2), dp(5), dp(2), dp(5));
            cell.addView(text(shortDate(d.date), 12, i == 0 ? BLUE : MUTED, false));
            cell.addView(icon(weatherIcon(d.weatherCode), INK, 27));
            TextView temperatures=text(temperature(d.maxTemperatureC) + " / " + temperature(d.minTemperatureC),12,INK,false);
            temperatures.setSingleLine(true);temperatures.setGravity(Gravity.CENTER);
            temperatures.setAutoSizeTextTypeUniformWithConfiguration(10,15,1,android.util.TypedValue.COMPLEX_UNIT_SP);
            cell.addView(temperatures,new LinearLayout.LayoutParams(-1,dp(19)));
            cell.addView(text(bite(i), 12, scoreColor(score(i)), false));
            cell.setOnClickListener(v -> message(shortDate(d.date), d.condition() + "\n" + assessmentText(dayIndex)));
            days.addView(cell, weight());
        }
        return days;
    }
    private View species() {
        LinearLayout root = base(true, false), body = scrollBody(root, 12);
        EditText search = field("Search species", "", false); body.addView(search, gap(12));
        section(body, "Selected species", "");
        LinearLayout selected = panel(10); selected.addView(infoRow(R.drawable.ic_fish, s.species, bite(0), null));
        selected.addView(text(assessmentText(0), 13, MUTED, false)); body.addView(selected, gap(12));
        section(body, "Other species", ""); LinearLayout choices = col(); body.addView(choices, gap(12));
        Runnable fill = () -> { choices.removeAllViews(); LinearLayout line = row(); int count = 0;
            for (String name : FishingAdvisor.SPECIES) if (!name.equals(s.species) && name.toLowerCase(Locale.US).contains(search.getText().toString().toLowerCase(Locale.US))) {
                line.addView(button(name, R.drawable.ic_fish, false, () -> host.species(name)), weight());
                if (++count % 3 == 0) { choices.addView(line, gap(4)); line = row(); }
            } if (line.getChildCount() > 0) choices.addView(line, gap(4)); };
        fill.run(); watch(search, fill);
        LinearLayout details = panel(10); section(details, "Bite score " + bite(0), "");
        details.addView(text(assessmentText(0), 15, INK, false));
        section(details, "Recommendations", "Planning guide");
        details.addView(infoRow(R.drawable.ic_droplet, "Depth", "Observe local conditions", null));
        details.addView(infoRow(R.drawable.ic_fish_hook, "Lures", "Choose for species and water", null));
        details.addView(infoRow(R.drawable.ic_waves, "Water conditions", "Not measured", null));
        details.addView(text("The forecast uses air weather only. Depth, water temperature and hourly bite predictions are not measured by this version.", 13, MUTED, false)); body.addView(details, gap(14));
        body.addView(button("ADD TO PLAN", R.drawable.ic_bookmark, true, () -> host.go("tripSetup")), gap(8));
        body.addView(button("COMPARE SPECIES", R.drawable.ic_fish, false, () -> soon("Species comparison")), gap(0)); return root;
    }
    private View tripSetup() {
        LinearLayout root = base(true, false), body = scrollBody(root, 12);
        body.addView(infoCard(R.drawable.ic_map_pin, "Selected spot", coords(), () -> host.go("map")), gap(7));
        body.addView(infoCard(R.drawable.ic_calendar, "Planned time", date(System.currentTimeMillis(), "EEE, MMM d, yyyy") + " · Start when ready", () -> soon("Scheduled trips")), gap(7));
        body.addView(infoCard(R.drawable.ic_route, "Route", "Choose on the map", () -> host.go("map")), gap(7));
        body.addView(infoCard(R.drawable.ic_map, "Offline map", "Downloads not available yet", () -> host.go("mapTools")), gap(7));
        body.addView(infoCard(R.drawable.ic_alert_triangle, "Forecast & risks", assessmentText(0), () -> host.go("forecastDetail")), gap(7));
        body.addView(infoCard(R.drawable.ic_list_check, "Gear checklist", "Review before leaving", () -> checklist()), gap(7));
        LinearLayout status = panel(5); status.addView(infoRow(R.drawable.ic_bluetooth, "Flic 2", s.connected ? "Connected" : "Not connected", () -> host.go("device")));
        status.addView(infoRow(R.drawable.ic_map_pin, "GPS & location", gps(), () -> host.command("permissions")));
        status.addView(infoRow(R.drawable.ic_cloud, "Cloud sync", s.pending == 0 ? "All points synced" : s.pending + " queued", () -> host.command("sync")));
        body.addView(status, gap(8));
        body.addView(toggle(R.drawable.ic_volume, "Voice guidance", false, null), gap(5));
        body.addView(infoRow(R.drawable.ic_route, "Track recording", "Enabled during trip", null), gap(10));
        body.addView(button("START TRIP", R.drawable.ic_navigation, true, () -> host.command("startTrip")), gap(8));
        body.addView(button("CHANGE ROUTE", R.drawable.ic_route, false, () -> host.go("map")), gap(0)); return root;
    }

    private View buildMapScreen(boolean tools, boolean active) {
        LinearLayout root=base(false,!active);
        if(active){LinearLayout stats=panel(7);LinearLayout line=row();line.addView(metric(R.drawable.ic_clock,"Trip",duration()),weight());line.addView(metric(R.drawable.ic_route,"Distance",String.format(Locale.US,"%.1f km",s.distanceKm)),weight());line.addView(metric(R.drawable.ic_bluetooth,"Flic 2",s.connected?"Connected":"Offline"),weight());stats.addView(line);stats.addView(infoRow(R.drawable.ic_map_pin,gps(),"Local capture",null));root.addView(stats,gap(4));}
        FrameLayout field=new FrameLayout(a);MapTilerMapView map=host.map();map.setOverlayVisibility(showPoints,showTrack);field.addView(map,new FrameLayout.LayoutParams(-1,-1));
        root.addView(field,new LinearLayout.LayoutParams(-1,0,1));
        if(!active&&!tools){LinearLayout legend=panel(6);String[] types={"Catch","Waypoint","Tackle change","Camp","Hazard"};for(String type:types){LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);line.addView(icon(pointIcon(type),pointColor(type),17));TextView t=text(type.equals("Tackle change")?"Tackle":type,12,INK,false);t.setPadding(dp(5),0,0,0);line.addView(t);legend.addView(line,new LinearLayout.LayoutParams(-1,dp(23)));}FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(106),-2,Gravity.TOP|Gravity.START);p.setMargins(dp(8),dp(10),0,0);field.addView(legend,p);}
        LinearLayout controls=col();
        View locate=iconButton(R.drawable.ic_current_location,"My location",()->host.command("recenter"));locate.setBackground(shape(BG,LINE,24));controls.addView(locate,gap(8));
        View north=iconButton(R.drawable.ic_compass,"North up",map::northUp);north.setBackground(shape(BG,LINE,24));controls.addView(north,gap(8));
        LinearLayout zoom=col();zoom.setBackground(shape(BG,LINE,24));zoom.addView(iconButton(R.drawable.ic_plus,"Zoom in",()->map.zoomBy(1)));zoom.addView(iconButton(R.drawable.ic_minus,"Zoom out",()->map.zoomBy(-1)));controls.addView(zoom,gap(8));
        View layers=iconButton(R.drawable.ic_layers,"Map layers",()->host.go("mapTools"));layers.setBackground(shape(BG,LINE,24));controls.addView(layers);
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(40),-2,Gravity.TOP|Gravity.END);cp.setMargins(0,dp(10),dp(8),0);field.addView(controls,cp);
        if(tools){LinearLayout sheet=panel(10);section(sheet,"Map & layers","");
            sheet.addView(toggle(R.drawable.ic_map,"Offline map",s.offlineComplete,()->host.go("offlineMaps")));
            sheet.addView(toggle(R.drawable.ic_droplet,"Depth",false,null));
            sheet.addView(toggle(R.drawable.ic_fish,"Fishing zones",false,null));
            sheet.addView(toggle(R.drawable.ic_layers,"Satellite",MapTilerMapView.STYLE_HYBRID.equals(s.mapStyle),()->host.command("style:"+(MapTilerMapView.STYLE_HYBRID.equals(s.mapStyle)?MapTilerMapView.STYLE_OUTDOOR:MapTilerMapView.STYLE_HYBRID))));
            sheet.addView(toggle(R.drawable.ic_map_pin,"My points",showPoints,()->{showPoints=!showPoints;map.setOverlayVisibility(showPoints,showTrack);}));
            sheet.addView(toggle(R.drawable.ic_route,"Recorded track",showTrack,()->{showTrack=!showTrack;map.setOverlayVisibility(showPoints,showTrack);}));
            section(sheet,"Tools","");LinearLayout tools1=row();tools1.addView(tool(R.drawable.ic_ruler,"Ruler",()->soon("Map ruler")),weight());tools1.addView(tool(R.drawable.ic_current_location,"Coordinates",()->message("Coordinates",coords())),weight());tools1.addView(tool(R.drawable.ic_upload,"Import GPX",()->soon("GPX import")),weight());sheet.addView(tools1,gap(5));
            LinearLayout tools2=row();tools2.addView(tool(R.drawable.ic_download,"Export GPX",()->soon("GPX export")),weight());tools2.addView(tool(R.drawable.ic_map,"Download area",()->host.go("offlineMaps")),weight());tools2.addView(tool(R.drawable.ic_microphone,"Voice",()->soon("Voice navigation")),weight());sheet.addView(tools2,gap(8));
            ScrollView scroll=new ScrollView(a);scroll.addView(sheet);LinearLayout dock=col();dock.setBackgroundColor(BG);dock.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));View done=button("DONE",0,true,()->host.go("map"));LinearLayout.LayoutParams doneLp=new LinearLayout.LayoutParams(-1,dp(44));doneLp.setMargins(dp(8),dp(4),dp(8),dp(6));dock.addView(done,doneLp);FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-1,dp(410),Gravity.BOTTOM);field.addView(dock,sp);
        }else if(active){LinearLayout actions=col();actions.setPadding(dp(8),dp(6),dp(8),dp(6));actions.addView(fieldButton("WAYPOINT","1 PRESS",R.drawable.ic_map_pin,AMBER,()->host.command("save:Waypoint")),gap(5));actions.addView(fieldButton("CATCH","2 PRESSES",R.drawable.ic_fish,LIME,()->host.command("save:Catch")),gap(5));actions.addView(fieldButton("TACKLE CHANGE","HOLD",R.drawable.ic_fish_hook,PURPLE,()->host.command("save:Tackle change")),gap(5));TextView background=text("● Route recording continues with the screen locked",11,LIME,false);background.setGravity(Gravity.CENTER);actions.addView(background,gap(5));LinearLayout bottom=row();bottom.addView(tool(R.drawable.ic_player_pause,"Pause",()->soon("Pause track")),weight());bottom.addView(tool(R.drawable.ic_alert_triangle,"SOS / Share",()->soon("SOS sharing")),weight());bottom.addView(tool(R.drawable.ic_player_stop,"Finish",()->host.command("finishTrip")),weight());actions.addView(bottom);root.addView(actions);
        }else{LinearLayout destination=panel(8);LinearLayout detail=row();detail.addView(icon(R.drawable.ic_navigation,BLUE,32));LinearLayout label=col();label.setPadding(dp(8),0,0,0);label.addView(text(s.selected!=null?"Selected point":s.location!=null?"Your position":"Recent saved area · "+gps(),13,MUTED,false));label.addView(text(coords(),17,INK,false));detail.addView(label);destination.addView(detail,gap(8));destination.addView(button(s.active?"ACTIVE TRIP":"START TRIP",R.drawable.ic_navigation,true,()->host.go(s.active?"tripActive":"tripSetup")),gap(6));destination.addView(button("SAVE POINT",R.drawable.ic_map_pin,false,()->host.command("save:Waypoint")));root.addView(destination,gap(0));}
        return root;
    }
    private View fieldButton(String title,String sub,int id,int color,Runnable action){LinearLayout b=row();b.setGravity(Gravity.CENTER_VERTICAL);b.setPadding(dp(12),dp(6),dp(12),dp(6));b.setBackground(shape(Color.argb(30,Color.red(color),Color.green(color),Color.blue(color)),color,5));b.addView(icon(id,color,38));LinearLayout copy=col();copy.setGravity(Gravity.CENTER);copy.addView(text(title,23,color,false));copy.addView(text(sub,13,color,false));b.addView(copy,new LinearLayout.LayoutParams(0,dp(48),1));b.setOnClickListener(v->action.run());return b;}
    private View tool(int id,String title,Runnable action){LinearLayout b=panel(5);b.setGravity(Gravity.CENTER);b.addView(icon(id,INK,24));TextView t=text(title,12,MUTED,false);t.setGravity(Gravity.CENTER);b.addView(t);b.setMinimumHeight(dp(50));b.setContentDescription(title);b.setOnClickListener(v->action.run());return b;}
    private View buildJournal(){
        LinearLayout root=base(false,false),body=scrollBody(root,8);LinearLayout heading=row();heading.setGravity(Gravity.CENTER_VERTICAL);heading.addView(text("Fishing journal",19,INK,true),new LinearLayout.LayoutParams(0,-2,1));heading.addView(iconButton(R.drawable.ic_filter,"Journal filters",()->journalFilterDialog()));heading.addView(iconButton(R.drawable.ic_plus,"Start fishing day",()->host.command("startDay")));body.addView(heading,gap(4));
        LinearLayout tabs=row();tabs.addView(chip("Calendar",!journalFeed,()->{journalFeed=false;host.go("log");}),weight());tabs.addView(chip("Feed",journalFeed,()->{journalFeed=true;host.go("log");}),weight());body.addView(tabs,gap(5));
        if(!journalFeed)body.addView(compactCalendar(),gap(5));
        LinearLayout metrics=panel(5),line=row();line.addView(text(s.days.size()+" trips",13,INK,false),weight());long catches=s.points.stream().filter(p->p.type.equals("Catch")).count();line.addView(text(catches+" catches",13,INK,false),weight());line.addView(text(s.active?"Trip active":"No active trip",13,s.active?LIME:MUTED,false),weight());metrics.addView(line);body.addView(metrics,gap(6));
        LinearLayout filters=row();for(String f:new String[]{"All","Catches","Trips","Notes"})filters.addView(chip(f,journalFilter.equals(f),()->{journalFilter=f;host.go("log");}),weight());body.addView(filters,gap(6));
        int shown=0;for(SavedPoint p:s.points){if(selectedDay>0&&!sameDay(selectedDay,p.timestamp))continue;if(journalFilter.equals("Catches")&&!p.type.equals("Catch"))continue;if(journalFilter.equals("Trips"))continue;if(journalFilter.equals("Notes")&&(p.note==null||p.note.isEmpty()))continue;body.addView(journalItem(p),gap(5));shown++;}
        if(journalFilter.equals("Trips")){for(FishingDay day:s.days){if(selectedDay>0&&!sameDay(selectedDay,day.startedAt))continue;body.addView(infoCard(R.drawable.ic_route,date(day.startedAt,"MMM d · HH:mm"),day.isActive()?"Active fishing day":"View trip summary",()->host.trip(day)),gap(5));shown++;}}
        if(shown==0)body.addView(infoCard(R.drawable.ic_notebook,"No entries",selectedDay>0?"No moments on this date":"Save a catch or start a fishing day",null),gap(6));
        if(selectedDay>0)body.addView(button("SHOW ALL DATES",R.drawable.ic_calendar,false,()->{selectedDay=0;host.go("log");}),gap(6));
        return root;
    }
    private View compactCalendar(){LinearLayout cal=col();LinearLayout title=row();title.setGravity(Gravity.CENTER_VERTICAL);title.addView(iconButton(R.drawable.ic_arrow_left,"Previous month",()->{month.add(Calendar.MONTH,-1);host.go("log");}));TextView caption=text(date(month.getTimeInMillis(),"MMMM yyyy"),15,INK,true);caption.setGravity(Gravity.CENTER);title.addView(caption,new LinearLayout.LayoutParams(0,-2,1));title.addView(iconButton(R.drawable.ic_arrow_right,"Next month",()->{month.add(Calendar.MONTH,1);host.go("log");}));cal.addView(title,new LinearLayout.LayoutParams(-1,dp(38)));LinearLayout week=row();for(String day:new String[]{"Mon","Tue","Wed","Thu","Fri","Sat","Sun"}){TextView t=text(day,11,MUTED,false);t.setGravity(Gravity.CENTER);week.addView(t,weight());}cal.addView(week,gap(3));Calendar cursor=(Calendar)month.clone();cursor.set(Calendar.DAY_OF_MONTH,1);int offset=(cursor.get(Calendar.DAY_OF_WEEK)+5)%7;cursor.add(Calendar.DAY_OF_MONTH,-offset);int rows=(offset+month.getActualMaximum(Calendar.DAY_OF_MONTH)+6)/7;for(int r=0;r<rows;r++){LinearLayout days=row();for(int n=0;n<7;n++){long time=cursor.getTimeInMillis();boolean current=cursor.get(Calendar.MONTH)==month.get(Calendar.MONTH);LinearLayout cell=col();cell.setGravity(Gravity.CENTER);boolean selected=selectedDay>0?sameDay(time,selectedDay):sameDay(time,System.currentTimeMillis());if(selected)cell.setBackground(shape(BUTTON,0,4));TextView number=text(String.valueOf(cursor.get(Calendar.DAY_OF_MONTH)),14,current?INK:MUTED,selected);number.setGravity(Gravity.CENTER);cell.addView(number);boolean has=s.points.stream().anyMatch(p->sameDay(p.timestamp,time));if(has){ImageView dot=icon(R.drawable.ic_circle_check,LIME,8);cell.addView(dot);}else cell.addView(new View(a),new LinearLayout.LayoutParams(1,dp(8)));cell.setContentDescription(date(time,"EEEE, MMMM d")+(has?", saved moments":""));cell.setOnClickListener(v->{selectedDay=time;host.go("log");});days.addView(cell,new LinearLayout.LayoutParams(0,dp(35),1));cursor.add(Calendar.DAY_OF_MONTH,1);}cal.addView(days);}return cal;}
    private boolean sameDay(long x,long y){return date(x,"yyyy-MM-dd").equals(date(y,"yyyy-MM-dd"));}
    private View journalItem(SavedPoint p){LinearLayout card=panel(6),line=row();LinearLayout day=col();day.setGravity(Gravity.CENTER);day.addView(text(date(p.timestamp,"d"),22,INK,false));day.addView(text(date(p.timestamp,"EEE"),11,MUTED,false));line.addView(day,new LinearLayout.LayoutParams(dp(32),-1));ImageView photo=pointPhoto(p,68);line.addView(photo,new LinearLayout.LayoutParams(dp(68),dp(68)));LinearLayout copy=col();copy.setPadding(dp(8),0,0,0);copy.addView(text(pointName(p),16,INK,true));copy.addView(text(date(p.timestamp,"HH:mm")+" · "+p.type,12,MUTED,false));copy.addView(text(p.weather==null?"Weather not captured":temperature(p.weather.temperatureC)+", "+p.weather.condition(),12,MUTED,false));copy.addView(text(host.syncLabel(p),11,host.syncColor(p),false));line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));line.addView(icon(pointIcon(p.type),pointColor(p.type),20));card.addView(line);card.setOnClickListener(v->host.point(p,"open"));return card;}
    private String pointName(SavedPoint p){return p.title!=null&&!p.title.isEmpty()?p.title:p.catchDetails!=null&&!p.catchDetails.species.isEmpty()?p.catchDetails.species:p.type;}
    private ImageView pointPhoto(SavedPoint p,int size){ImageView photo=new ImageView(a);photo.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(size)));photo.setScaleType(ImageView.ScaleType.CENTER_CROP);String path=p.catchDetails==null?"":p.catchDetails.localPhotoPath;if(!path.isEmpty()){android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(path,bounds);android.graphics.BitmapFactory.Options decode=new android.graphics.BitmapFactory.Options();int target=Math.max(dp(size),dp(120));while(bounds.outWidth/Math.max(1,decode.inSampleSize)>target*2||bounds.outHeight/Math.max(1,decode.inSampleSize)>target*2)decode.inSampleSize=decode.inSampleSize==0?2:decode.inSampleSize*2;photo.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path,decode));photo.setContentDescription("Saved catch photo");}else{photo.setImageResource(pointIcon(p.type));photo.setColorFilter(pointColor(p.type));photo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);photo.setPadding(dp(15),dp(15),dp(15),dp(15));photo.setBackgroundColor(PANEL);photo.setContentDescription(p.type+" · no photo");}return photo;}
    private View chip(String label,boolean selected,Runnable action){TextView t=text(label,12,selected?INK:MUTED,false);t.setSingleLine(true);t.setAutoSizeTextTypeUniformWithConfiguration(10,12,1,android.util.TypedValue.COMPLEX_UNIT_SP);t.setGravity(Gravity.CENTER);t.setPadding(dp(3),dp(5),dp(3),dp(5));t.setMinimumHeight(dp(30));t.setBackground(shape(selected?BUTTON:BG,LINE,4));t.setOnClickListener(v->action.run());return t;}
    private void journalFilterDialog(){new AlertDialog.Builder(a).setTitle("Journal filter").setItems(new String[]{"All","Catches","Trips","Notes"},(d,w)->{journalFilter=new String[]{"All","Catches","Trips","Notes"}[w];host.go("log");}).show();}
    private View buildSaved(){LinearLayout root=base(false,false),body=scrollBody(root,8);section(body,"Saved",s.points.size()+" moments");EditText query=field("Search saved items",savedQuery,false);body.addView(query,gap(6));LinearLayout filters=row();for(String f:new String[]{"All","Catch","Waypoint","Tackle","Camp","Hazard"})filters.addView(chip(f,savedFilter.equals(f),()->{savedFilter=f;savedQuery=query.getText().toString();host.go("saved");}),weight());body.addView(filters,gap(6));LinearLayout sync=panel(5),syncRow=row();syncRow.setGravity(Gravity.CENTER_VERTICAL);syncRow.addView(icon(R.drawable.ic_cloud,INK,24));LinearLayout syncCopy=col();syncCopy.setPadding(dp(6),0,0,0);syncCopy.addView(text(s.pending==0?"Cloud synced":"Local changes queued",14,INK,false));syncCopy.addView(text(Math.max(0,s.points.size()-s.pending)+" of "+s.points.size()+" synced",11,MUTED,false));syncRow.addView(syncCopy,new LinearLayout.LayoutParams(0,-2,1));syncRow.addView(button("SYNC",0,true,()->host.command("sync")),new LinearLayout.LayoutParams(dp(76),dp(36)));sync.addView(syncRow);body.addView(sync,gap(6));LinearLayout items=col();body.addView(items);Runnable fill=()->{savedQuery=query.getText().toString();items.removeAllViews();int count=0;for(SavedPoint p:s.points){if(!savedFilter.equals("All")&&!p.type.startsWith(savedFilter))continue;String match=(pointName(p)+" "+p.note+" "+p.latitude+" "+p.longitude+" "+(p.catchDetails==null?"":p.catchDetails.notes)).toLowerCase(Locale.US);if(!match.contains(savedQuery.toLowerCase(Locale.US)))continue;items.addView(savedItem(p),gap(6));count++;}if(count==0)items.addView(infoCard(R.drawable.ic_search,"No saved items","Try another search or filter",null));};fill.run();watch(query,fill);return root;}
    private View savedItem(SavedPoint p){LinearLayout card=panel(7),head=row();head.setGravity(Gravity.TOP);head.addView(icon(pointIcon(p.type),pointColor(p.type),30));LinearLayout copy=col();copy.setPadding(dp(7),0,0,0);copy.addView(text(pointName(p),17,INK,true));copy.addView(text(date(p.timestamp,"MM.dd.yyyy HH:mm")+(p.catchDetails!=null&&p.catchDetails.weightKg>0?String.format(Locale.US," · %.2f kg",p.catchDetails.weightKg):""),12,MUTED,false));copy.addView(text(String.format(Locale.US,"%.5f, %.5f",p.latitude,p.longitude),12,MUTED,false));if(p.title!=null&&!p.title.isEmpty())copy.addView(text("Custom name · this phone",11,MUTED,false));if(p.weather!=null)copy.addView(text(temperature(p.weather.temperatureC)+" · "+p.weather.condition(),12,MUTED,false));if(p.note!=null&&!p.note.isEmpty())copy.addView(text(p.note,12,INK,false));if(p.catchDetails!=null&&!p.catchDetails.notes.isEmpty()&&!p.catchDetails.notes.equals(p.note))copy.addView(text(p.catchDetails.notes,12,INK,false));copy.addView(text(host.syncLabel(p),12,host.syncColor(p),false));head.addView(copy,new LinearLayout.LayoutParams(0,-2,1));head.addView(iconButton(R.drawable.ic_dots,"Point actions",()->pointMenu(p)));card.addView(head);card.addView(divider());LinearLayout actions=row();actions.addView(actionLink(R.drawable.ic_map_pin,"ON MAP",BLUE,()->host.point(p,"map")),weight());actions.addView(actionLink(R.drawable.ic_edit,"EDIT",BLUE,()->host.point(p,"edit")),weight());actions.addView(actionLink(R.drawable.ic_trash,"DELETE",RED,()->host.point(p,"delete")),weight());card.addView(actions);return card;}
    private View actionLink(int id,String label,int color,Runnable run){LinearLayout r=row();r.setGravity(Gravity.CENTER);r.setMinimumHeight(dp(34));r.addView(icon(id,color,17));TextView t=text(label,12,color,false);t.setPadding(dp(4),0,0,0);r.addView(t);r.setContentDescription(label);r.setOnClickListener(v->run.run());return r;}
    private void pointMenu(SavedPoint p){List<String> labels=new ArrayList<>(),actions=new ArrayList<>();labels.add("Open map");actions.add("map");labels.add("Edit name, type & note");actions.add("edit");if("Catch".equals(p.type)){labels.add("Edit catch details");actions.add("catchEdit");labels.add("Add photo");actions.add("photo");}labels.add("Refresh weather");actions.add("weather");labels.add("Delete");actions.add("delete");new AlertDialog.Builder(a).setTitle(pointName(p)).setItems(labels.toArray(new String[0]),(d,w)->host.point(p,actions.get(w))).show();}

    private View buildOfflineMaps(){
        LinearLayout root=base(true,false),body=scrollBody(root,10);section(body,"Offline maps",s.offlinePacks==0?"No saved area":s.offlinePacks+" saved");
        body.addView(text("Download the current fishing area before leaving coverage. Saved points, GPS and trip recording already work without internet.",15,MUTED,false),gap(10));
        LinearLayout status=panel(7);status.addView(infoRow(R.drawable.ic_map,s.offlineAvailable?s.offlineComplete?"Area ready":"Offline area":"No offline area",s.offlineStatus,null));
        if(s.offlineAvailable){ProgressBar progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgress(s.offlineProgress);progress.setProgressTintList(ColorStateList.valueOf(s.offlineComplete?LIME:BLUE));status.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));status.addView(text(s.offlineComplete?"100% · available without internet":s.offlineProgress+"% · "+formatBytes(s.offlineBytes)+" downloaded",12,MUTED,false));}
        body.addView(status,gap(8));
        if(s.offlineAvailable){
            LinearLayout details=panel(4);details.addView(infoRow(R.drawable.ic_map_pin,"Area centre",String.format(Locale.US,"%.4f, %.4f",s.offlineLatitude,s.offlineLongitude),null));details.addView(divider());details.addView(infoRow(R.drawable.ic_ruler,"Radius",String.format(Locale.US,"%.0f km",s.offlineRadiusKm),null));details.addView(divider());details.addView(infoRow(R.drawable.ic_layers,"Map style",s.offlineStyle,null));details.addView(divider());details.addView(infoRow(R.drawable.ic_download,"Downloaded",formatBytes(s.offlineBytes),null));body.addView(details,gap(8));
            if(!s.offlineComplete)body.addView(button(s.offlineDownloading?"PAUSE DOWNLOAD":"RESUME DOWNLOAD",s.offlineDownloading?R.drawable.ic_player_pause:R.drawable.ic_download,true,()->host.command(s.offlineDownloading?"offlinePause":"offlineResume")),gap(7));
            body.addView(button("DELETE OFFLINE AREA",R.drawable.ic_trash,false,()->host.command("offlineDelete")),gap(10));
            body.addView(text("Changing map style requires deleting this area and downloading it again.",12,MUTED,false));
        }else{
            section(body,"Download around current GPS","Choose radius");LinearLayout choices=row();for(int radius:new int[]{2,5,10}){final int value=radius;choices.addView(chip(radius+" km",offlineRadiusKm==radius,()->{offlineRadiusKm=value;host.go("offlineMaps");}),weight());}body.addView(choices,gap(9));
            LinearLayout details=panel(4);details.addView(infoRow(R.drawable.ic_map_pin,"Current position",s.location==null?"Waiting for GPS":String.format(Locale.US,"%.4f, %.4f",s.location.getLatitude(),s.location.getLongitude()),null));details.addView(divider());details.addView(infoRow(R.drawable.ic_layers,"Map style",MapTilerMapView.styleName(s.mapStyle),null));details.addView(divider());details.addView(infoRow(R.drawable.ic_download,"Zoom range","8–16",null));body.addView(details,gap(8));
            body.addView(text("Download size depends on map detail and style. Wi-Fi is recommended. You can pause and resume later.",12,MUTED,false),gap(9));View download=button("DOWNLOAD CURRENT AREA",R.drawable.ic_download,true,()->host.command("offlineDownload:"+offlineRadiusKm));boolean canDownload=s.location!=null&&s.offlineChecked&&!s.offlineDownloading;download.setEnabled(canDownload);download.setAlpha(canDownload?1f:.45f);body.addView(download,gap(8));
        }
        return root;
    }

    private String formatBytes(long value){if(value<=0)return "0 MB";double mb=value/(1024d*1024d);return mb<1024?String.format(Locale.US,"%.1f MB",mb):String.format(Locale.US,"%.2f GB",mb/1024d);}

private View buildDevices(){LinearLayout root=base(false,false),body=scrollBody(root,10);LinearLayout device=panel(8);device.addView(infoRow(R.drawable.ic_bluetooth,s.device.isEmpty()?"Flic 2":s.device,s.connected?"Connected":"Not connected",null));LinearLayout buttons=row();buttons.addView(button("CONFIGURE",0,false,()->host.go("flicSetup")),weight());buttons.addView(button("ADD DEVICE",0,false,()->host.command("pair")),weight());device.addView(buttons);body.addView(device,gap(8));section(body,"Flic 2 button mapping","");LinearLayout mapping=panel(4);mapping.addView(infoRow(R.drawable.ic_map_pin,"1 press","Waypoint",()->soon("Button remapping")));mapping.addView(divider());mapping.addView(infoRow(R.drawable.ic_fish,"2 presses","Catch",()->soon("Button remapping")));mapping.addView(divider());mapping.addView(infoRow(R.drawable.ic_fish_hook,"Hold","Tackle change",()->soon("Button remapping")));mapping.addView(button("TEST PRESSES",0,false,()->host.command("testFlic")));body.addView(mapping,gap(7));section(body,"App settings","");LinearLayout settings=panel(3);settings.addView(infoRow(R.drawable.ic_map,"Offline maps",s.offlineComplete?"Ready":s.offlineDownloading?s.offlineProgress+"%":s.offlineAvailable?"Paused":"Set up",()->host.go("offlineMaps")));settings.addView(divider());settings.addView(infoRow(R.drawable.ic_cloud,"Cloud backup",s.pending==0?"Synced":s.pending+" queued",()->host.command("sync")));settings.addView(divider());settings.addView(infoRow(R.drawable.ic_user,"Profile & sign-in",s.signedIn?s.name:"Local mode",()->host.go("profile")));settings.addView(divider());settings.addView(infoRow(R.drawable.ic_world,"Units & language",s.imperial?"Imperial · English":"Metric · English",()->unitDialog()));settings.addView(divider());settings.addView(infoRow(R.drawable.ic_map_pin,"GPS / Bluetooth permissions",s.gps&&s.bluetooth?"Allowed":"Review",()->host.command("permissions")));settings.addView(divider());settings.addView(infoRow(R.drawable.ic_sun,"Forecast providers","Open-Meteo",()->host.go("forecastDetail")));settings.addView(divider());settings.addView(infoRow(R.drawable.ic_gauge,"Barometer offset","Coming soon",()->soon("Barometer calibration")));settings.addView(divider());settings.addView(infoRow(R.drawable.ic_alert_triangle,"Safety & SOS","Configure",()->soon("Safety & SOS")));settings.addView(divider());settings.addView(infoRow(R.drawable.ic_notebook,"News & offline library","Coming soon",()->soon("Offline library")));settings.addView(divider());settings.addView(infoRow(R.drawable.ic_help,"Help & tutorials","",()->host.go("flicSetup")));body.addView(settings,gap(8));body.addView(infoCard(R.drawable.ic_shield_check,"Field readiness",s.gps&&s.bluetooth?"Permissions ready":"Review permissions",()->host.go("beta")),gap(5));body.addView(text(s.status,12,MUTED,false));return root;}
    private void unitDialog(){new AlertDialog.Builder(a).setTitle("Units · interface language: English").setSingleChoiceItems(new String[]{"Metric","Imperial"},s.imperial?1:0,(d,w)->{host.command(w==0?"metric":"imperial");d.dismiss();}).setNegativeButton("Cancel",null).show();}
    private List<SavedPoint> summaryCatches(){
        long end=s.active||s.tripStopped==0?System.currentTimeMillis():s.tripStopped;List<SavedPoint> catches=new ArrayList<>();
        for(SavedPoint point:s.points)if("Catch".equals(point.type)&&TripStatistics.includes(point.timestamp,s.tripStarted,end))catches.add(point);
        catches.sort(Comparator.comparingLong(point->point.timestamp));return catches;
    }
    private View buildSummary(){
        LinearLayout root=base(true,false),body=scrollBody(root,10);List<SavedPoint> catches=summaryCatches();
        List<Long> times=new ArrayList<>();List<String> lureNames=new ArrayList<>();for(SavedPoint point:catches){times.add(point.timestamp);lureNames.add(point.catchDetails==null?"":point.catchDetails.lure);}
        int[] buckets=TripStatistics.hourlyBuckets(times,TimeZone.getDefault());LinkedHashMap<String,Integer> lures=TripStatistics.lureCounts(lureNames);
        int peak=0;for(int i=1;i<buckets.length;i++)if(buckets[i]>buckets[peak])peak=i;
        LinearLayout stats=row();stats.addView(summaryMetric(R.drawable.ic_clock,"Duration",s.tripStarted==0?"—":duration()),weight());
        stats.addView(summaryMetric(R.drawable.ic_route,"Distance",String.format(Locale.US,"%.1f "+(s.imperial?"mi":"km"),s.distanceKm*(s.imperial?.621371:1))),weight());
        stats.addView(summaryMetric(R.drawable.ic_fish,"Catches",String.valueOf(catches.size())),weight());
        stats.addView(summaryMetric(R.drawable.ic_sun,"Peak time",catches.isEmpty()?"—":String.format(Locale.US,"%02d–%02d",peak*4,peak*4+4)),weight());body.addView(stats,gap(8));
        MapTilerMapView map=host.map();body.addView(map,new LinearLayout.LayoutParams(-1,dp(114)));
        section(body,"Catches ("+catches.size()+")","");
        if(catches.isEmpty())body.addView(text("No catches recorded for this trip.",14,MUTED,false),gap(14));
        else{
            HorizontalScrollView gallery=new HorizontalScrollView(a);LinearLayout photos=row();gallery.setHorizontalScrollBarEnabled(false);
            for(SavedPoint point:catches){ImageView photo=pointPhoto(point,82);photo.setOnClickListener(v->host.point(point,"catchEdit"));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(94),dp(82));lp.rightMargin=dp(4);photos.addView(photo,lp);}
            gallery.addView(photos);body.addView(gallery,gap(10));
            SavedPoint best=catches.get(0);for(SavedPoint point:catches)if(point.catchDetails!=null&&(best.catchDetails==null||point.catchDetails.weightKg>best.catchDetails.weightKg))best=point;
            final SavedPoint selected=best;CatchDetails details=best.catchDetails;LinearLayout card=panel(8);section(card,details==null||details.species.isEmpty()?"Catch":details.species,details!=null&&details.weightKg>0?"Heaviest recorded":"Recorded catch");
            LinearLayout measures=row();measures.addView(summaryValue("Weight",details==null||details.weightKg==0?"Not recorded":String.format(Locale.US,"%.2f "+(s.imperial?"lb":"kg"),details.weightKg*(s.imperial?2.20462:1))),weight());
            measures.addView(summaryValue("Length",details==null||details.lengthCm==0?"Not recorded":String.format(Locale.US,"%.1f "+(s.imperial?"in":"cm"),details.lengthCm*(s.imperial?1/2.54:1))),weight());
            measures.addView(summaryValue("Lure",details==null||details.lure.isEmpty()?"Not recorded":details.lure),weight());card.addView(measures,gap(8));card.addView(divider());
            LinearLayout conditions=row();conditions.addView(summaryValue("Depth","Not recorded"),weight());conditions.addView(summaryValue("Weather",best.weather==null?"Not captured":temperature(best.weather.temperatureC)+" · "+best.weather.condition()),weight());conditions.addView(summaryValue("Moon","Not recorded"),weight());card.addView(conditions,gap(8));card.addView(divider());
            LinearLayout position=row();position.addView(summaryValue("Location",String.format(Locale.US,"%.4f, %.4f",best.latitude,best.longitude)),new LinearLayout.LayoutParams(0,-2,2));position.addView(summaryValue("Time",date(best.timestamp,"HH:mm")),weight());card.addView(position);
            card.setOnClickListener(v->host.point(selected,"catchEdit"));body.addView(card,gap(10));
        }
        body.addView(infoCard(R.drawable.ic_microphone,"Audio note","Coming soon",()->soon("Audio notes")),gap(10));
        LinearLayout graphs=row(),hours=panel(6),lurePanel=panel(6);section(hours,"Catches by time","");hours.addView(new CatchHistogram(buckets),new LinearLayout.LayoutParams(-1,dp(106)));section(lurePanel,"Catches by lure","");
        lurePanel.addView(new LureChart(lures,catches.size()),new LinearLayout.LayoutParams(-1,dp(76)));int index=0;int[] colors={BLUE,LIME,AMBER,PURPLE};
        for(Map.Entry<String,Integer> entry:lures.entrySet()){LinearLayout legend=row();legend.addView(icon(R.drawable.ic_circle_check,colors[index++%colors.length],14));TextView label=text(entry.getKey()+" · "+Math.round(entry.getValue()*100f/catches.size())+"%",10,MUTED,false);label.setMaxLines(2);legend.addView(label,new LinearLayout.LayoutParams(0,-2,1));lurePanel.addView(legend);}
        graphs.addView(hours,new LinearLayout.LayoutParams(0,-1,1));LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-1,1);right.leftMargin=dp(6);graphs.addView(lurePanel,right);body.addView(graphs,gap(10));
        body.addView(button("EDIT",R.drawable.ic_edit,true,()->{if(catches.isEmpty())host.go("log");else{String[] names=new String[catches.size()];for(int i=0;i<names.length;i++){SavedPoint point=catches.get(i);names[i]=date(point.timestamp,"HH:mm")+" · "+pointName(point);}new AlertDialog.Builder(a).setTitle("Choose a catch").setItems(names,(dialog,which)->host.point(catches.get(which),"catchEdit")).show();}}),gap(8));
        body.addView(button("SHARE REPORT",R.drawable.ic_share,false,()->soon("Trip report sharing")),gap(8));body.addView(button("EXPORT GPX",R.drawable.ic_download,false,()->soon("GPX export")),gap(0));return root;
    }
    private View summaryValue(String label,String value){LinearLayout item=col();item.addView(text(label,12,MUTED,false));item.addView(text(value,14,INK,false));return item;}
    private View summaryMetric(int id,String label,String value){
        LinearLayout item=col(),line=row();item.setPadding(dp(3),dp(4),dp(3),dp(4));line.setGravity(Gravity.CENTER_VERTICAL);line.addView(icon(id,MUTED,14));
        TextView number=text(value,12,INK,false);number.setPadding(dp(3),0,0,0);number.setSingleLine(true);number.setAutoSizeTextTypeUniformWithConfiguration(10,15,1,android.util.TypedValue.COMPLEX_UNIT_SP);line.addView(number,new LinearLayout.LayoutParams(0,dp(20),1));item.addView(line);
        TextView caption=text(label,11,MUTED,false);caption.setSingleLine(true);caption.setAutoSizeTextTypeUniformWithConfiguration(10,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);item.addView(caption,new LinearLayout.LayoutParams(-1,dp(18)));item.setContentDescription(label+": "+value);return item;
    }
    private final class CatchHistogram extends View {
        private final int[] counts;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        CatchHistogram(int[] counts){super(a);this.counts=counts;setContentDescription("Recorded catches in four-hour groups: "+Arrays.toString(counts));}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);int max=1,total=0;for(int count:counts){max=Math.max(max,count);total+=count;}float bottom=getHeight()-dp(20),top=dp(8),step=getWidth()/6f;paint.setTypeface(regular);paint.setTextSize(dp(10));paint.setStrokeWidth(dp(.5f));
            paint.setColor(LINE);for(int i=0;i<3;i++)canvas.drawLine(0,top+(bottom-top)*i/2,getWidth(),top+(bottom-top)*i/2,paint);
            for(int i=0;i<counts.length;i++){float x=i*step;paint.setColor(i%2==0?BLUE:LIME);canvas.drawRect(x+dp(5),bottom-(bottom-top)*counts[i]/max,x+step-dp(5),bottom,paint);paint.setColor(MUTED);canvas.drawText(String.format(Locale.US,"%02d",i*4),x+dp(5),getHeight()-dp(5),paint);}
            if(total==0){paint.setColor(MUTED);canvas.drawText("No catches yet",dp(12),getHeight()/2f,paint);}
        }
    }
    private final class LureChart extends View {
        private final Map<String,Integer> values;private final int total;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        LureChart(Map<String,Integer> values,int total){super(a);this.values=values;this.total=total;setContentDescription("Recorded catches by lure: "+values.toString());}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float size=Math.min(getWidth(),getHeight())-dp(18),left=(getWidth()-size)/2f,top=dp(9);android.graphics.RectF bounds=new android.graphics.RectF(left,top,left+size,top+size);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(13));int[] colors={BLUE,LIME,AMBER,PURPLE};float angle=-90;int index=0;if(total==0){paint.setColor(LINE);canvas.drawArc(bounds,0,360,false,paint);}else for(int value:values.values()){float sweep=value*360f/total;paint.setColor(colors[index++%colors.length]);canvas.drawArc(bounds,angle,sweep,false,paint);angle+=sweep;}paint.setStyle(Paint.Style.FILL);paint.setColor(INK);paint.setTypeface(semibold);paint.setTextSize(dp(19));String label=String.valueOf(total);canvas.drawText(label,(getWidth()-paint.measureText(label))/2f,getHeight()/2f+dp(7),paint);}
    }
    private String duration(){if(s.tripStarted==0)return "00:00";long mins=(Math.max(s.tripStarted,s.active?System.currentTimeMillis():s.tripStopped)-s.tripStarted)/60000;return String.format(Locale.US,"%02d:%02d",mins/60,mins%60);}

    private LinearLayout authBase() { return authBase(false); }
    private LinearLayout authBase(boolean spacious) {
        LinearLayout root=col();root.setBackgroundColor(BG);
        if(spacious){FrameLayout header=new FrameLayout(a);header.addView(iconButton(R.drawable.ic_arrow_left,"Back",a::onBackPressed),new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP|Gravity.START));ImageView mark=new ImageView(a);mark.setImageResource(R.drawable.fiskentra_wordmark);mark.setColorFilter(BLUE);mark.setScaleType(ImageView.ScaleType.FIT_CENTER);FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(dp(200),dp(60),Gravity.TOP|Gravity.CENTER_HORIZONTAL);mp.topMargin=dp(70);header.addView(mark,mp);root.addView(header,new LinearLayout.LayoutParams(-1,dp(150)));return root;}
        LinearLayout top=row();top.setGravity(Gravity.CENTER_VERTICAL);top.addView(iconButton(R.drawable.ic_arrow_left,"Back",a::onBackPressed));
        ImageView logo=new ImageView(a);logo.setImageResource(R.drawable.fiskentra_wordmark);logo.setColorFilter(BLUE);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(52),1);lp.setMargins(dp(24),0,dp(64),0);top.addView(logo,lp);
        root.addView(top,new LinearLayout.LayoutParams(-1,dp(70)));return root;
    }
    private View buildWelcome() {
        FrameLayout scene=new FrameLayout(a);scene.setBackgroundColor(BG);
        ImageView lake=new ImageView(a);lake.setImageResource(R.drawable.fiskentra_lake_hero);lake.setScaleType(ImageView.ScaleType.CENTER_CROP);
        scene.addView(lake,new FrameLayout.LayoutParams(-1,dp(280),Gravity.TOP));
        LinearLayout root=col();root.setPadding(dp(18),dp(110),dp(18),dp(18));
        ImageView logo=new ImageView(a);logo.setImageResource(R.drawable.fiskentra_wordmark);logo.setColorFilter(BLUE);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(logo,new LinearLayout.LayoutParams(-1,dp(72)));
        root.addView(new View(a),new LinearLayout.LayoutParams(1,dp(42)));
        TextView title=text("Plan the water.\nRemember the moment.",30,INK,true);title.setGravity(Gravity.CENTER);root.addView(title,gap(18));
        TextView subtitle=text("Fishing forecast and one-press field logging.\nYour moments stay available offline.",16,MUTED,false);subtitle.setGravity(Gravity.CENTER);root.addView(subtitle,gap(12));
        root.addView(new View(a),new LinearLayout.LayoutParams(1,0,1));
        root.addView(button("CREATE ACCOUNT",0,true,()->host.go("auth:signup")),gap(10));
        root.addView(button("SIGN IN",0,false,()->host.go("auth:signin")),gap(10));
        root.addView(actionLink(R.drawable.ic_arrow_right,"EXPLORE FISKENTRA",BLUE,()->host.command("continueLocal")),gap(10));
        root.addView(actionLink(R.drawable.ic_shield_check,"PRIVACY & TERMS",MUTED,()->soon("Public policies")));
        scene.addView(root,new FrameLayout.LayoutParams(-1,-1));return scene;
    }
    private View buildAccount() {
        if(!s.signedIn&&s.authMode.equals("landing"))return buildWelcome();
        if(s.authMode.equals("checkEmail"))return verifyEmail();
        if(s.authMode.equals("resetSent"))return emailSent();
        if(s.authMode.equals("reset"))return host.legacy("reset");
        LinearLayout root=authBase(!s.signedIn&&!s.authMode.equals("signup")),body=scrollBody(root,20);
        if(s.signedIn){section(body,"Your profile","");body.addView(infoCard(R.drawable.ic_user,s.name,s.email,null),gap(14));body.addView(text(s.authStatus,14,s.authError?RED:MUTED,false),gap(14));body.addView(infoCard(R.drawable.ic_bookmark,"This phone",s.points.size()+" saved moments",null),gap(14));body.addView(text("Signing out will not remove your saved moments or Flic pairing.",14,MUTED,false),gap(16));body.addView(button("EDIT DISPLAY NAME",R.drawable.ic_edit,true,()->host.command("editName")),gap(10));body.addView(button("PROFILE SETUP",R.drawable.ic_user,false,()->host.go("profileSetup")),gap(10));body.addView(button("SIGN OUT",R.drawable.ic_logout,false,()->host.command("signout")));return root;}
        boolean signup=s.authMode.equals("signup"),recover=s.authMode.equals("recover");
        body.addView(new View(a),new LinearLayout.LayoutParams(1,dp(signup?10:24)));
        body.addView(text(signup?"Create your account":recover?"Reset password":"Welcome back",30,INK,true),gap(24));
        if(signup||recover)body.addView(text(signup?"Keep your fishing memories in one place.":"Enter your email and we'll send you a reset link.",18,MUTED,false),gap(24));
        EditText name=field("Display name",s.name,false),email=field("you@example.com",s.email,false),password=field("Password","",false),confirm=field("Confirm password","",false);
        email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        confirm.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if(signup)labelField(body,"Display name",name);
        labelField(body,"Email",email);
        if(!recover){
            body.addView(text("Password",18,INK,false),gap(8));
            body.addView(passwordField(password),gap(24));
            if(signup){body.addView(text("Confirm password",18,INK,false),gap(8));body.addView(passwordField(confirm),gap(12));body.addView(text("Use at least 8 characters. Choose a unique password.",14,MUTED,false),gap(22));}
        }
        if(!signup&&!recover){
            LinearLayout options=row();options.setGravity(Gravity.CENTER_VERTICAL);
            TextView retained=text("Stay signed in",16,MUTED,false);
            options.addView(icon(R.drawable.ic_check,BLUE,20));options.addView(retained,new LinearLayout.LayoutParams(0,-2,1));
            TextView forgot=text("Forgot password?",17,BLUE,false);forgot.setPadding(dp(6),dp(10),0,dp(10));forgot.setOnClickListener(v->host.go("auth:recover"));options.addView(forgot);
            body.addView(options,gap(30));
        }
        if(!s.authStatus.isEmpty())body.addView(text(s.authStatus,16,s.authError?RED:MUTED,false),gap(14));
        if(recover)body.addView(new View(a),new LinearLayout.LayoutParams(1,0,1));
        View submit=button(s.busy?"PLEASE WAIT…":signup?"CREATE ACCOUNT":recover?"SEND RESET LINK":"SIGN IN",0,true,()->host.auth(signup,recover,name,email,password,confirm));
        submit.setMinimumHeight(dp(58));submit.setEnabled(!s.busy);submit.setAlpha(s.busy?.5f:1f);body.addView(submit,gap(20));
        if(!recover){
            LinearLayout or=row();or.setGravity(Gravity.CENTER_VERTICAL);View left=divider(),right=divider();or.addView(left,new LinearLayout.LayoutParams(0,dp(1),1));TextView middle=text("or",18,MUTED,false);middle.setPadding(dp(18),0,dp(18),0);or.addView(middle);or.addView(right,new LinearLayout.LayoutParams(0,dp(1),1));body.addView(or,gap(20));
            View google=button("CONTINUE WITH GOOGLE",0,false,()->soon("Google sign-in"));google.setMinimumHeight(dp(58));body.addView(google,gap(26));
        }
        TextView change=text(signup||recover?"Back to sign in":"New to Fiskentra? Create account",18,BLUE,false);change.setGravity(Gravity.CENTER);change.setPadding(0,dp(10),0,dp(10));change.setOnClickListener(v->host.go(signup||recover?"auth:signin":"auth:signup"));body.addView(change,gap(22));
        LinearLayout privacy=row();privacy.setGravity(Gravity.CENTER);privacy.addView(icon(R.drawable.ic_lock,MUTED,18));TextView privacyText=text("Your fishing locations stay private.",16,MUTED,false);privacyText.setPadding(dp(8),0,0,0);privacy.addView(privacyText);body.addView(privacy);
        return root;
    }
    private View passwordField(EditText input){
        LinearLayout box=row();box.setGravity(Gravity.CENTER_VERTICAL);box.setBackground(shape(PANEL,LINE,4));input.setBackgroundColor(Color.TRANSPARENT);input.setTextSize(20);input.setMinimumHeight(dp(58));box.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        final boolean[] visible={false};View eye=iconButton(R.drawable.ic_eye,"Show or hide password",()->{visible[0]=!visible[0];int caret=input.getSelectionStart();input.setInputType(InputType.TYPE_CLASS_TEXT|(visible[0]?InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:InputType.TYPE_TEXT_VARIATION_PASSWORD));input.setTypeface(regular);input.setSelection(Math.max(0,Math.min(caret,input.length())));});box.addView(eye);return box;
    }
    private void labelField(LinearLayout body,String label,EditText input){input.setMinimumHeight(dp(58));input.setTextSize(20);body.addView(text(label,18,INK,false),gap(8));body.addView(input,gap(24));}
    private View accessCard(int id,String title,String subtitle,boolean allowed){LinearLayout card=row();card.setPadding(dp(10),dp(12),dp(10),dp(12));card.setGravity(Gravity.CENTER_VERTICAL);card.setMinimumHeight(dp(68));card.setBackground(shape(PANEL,LINE,4));card.addView(icon(id,BLUE,34));LinearLayout copy=col();copy.setPadding(dp(8),0,0,0);copy.addView(text(title,16,INK,false));copy.addView(text(subtitle,12,MUTED,false));card.addView(copy,new LinearLayout.LayoutParams(0,-2,1));card.addView(text(allowed?"Allowed":"Not allowed",12,allowed?LIME:MUTED,false));card.setOnClickListener(v->host.command("permissions"));return card;}
    private View verifyEmail(){
        LinearLayout root=authBase(),body=scrollBody(root,22);
        accountProgress(body,1);
        body.addView(text("Verify your email",28,INK,false),gap(14));
        body.addView(text("We sent a confirmation link to\n"+s.email,17,MUTED,false),gap(24));
        body.addView(text("Verification code",14,MUTED,false),gap(10));
        LinearLayout digits=row();
        for(int i=0;i<6;i++){TextView cell=text("",22,INK,true);cell.setBackground(shape(PANEL,LINE,4));cell.setContentDescription("Code entry coming soon");LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(3),0,dp(3),0);digits.addView(cell,lp);}
        body.addView(digits,gap(14));
        body.addView(text("Code entry is coming soon. For now, open the secure confirmation link in your email.",14,MUTED,false),gap(22));
        if(!s.authStatus.isEmpty())body.addView(text(s.authStatus,14,s.authError?RED:LIME,false),gap(14));
        body.addView(button("OPEN VERIFICATION EMAIL",0,true,()->host.command("openEmail")),gap(14));
        body.addView(actionLink(R.drawable.ic_refresh,"RESEND EMAIL",BLUE,()->host.command("resend")),gap(14));
        body.addView(actionLink(R.drawable.ic_edit,"CHANGE EMAIL",BLUE,()->host.go("auth:signup")),gap(22));
        body.addView(text("After confirming your address, return to Fiskentra and sign in.",14,MUTED,false),gap(18));
        body.addView(new View(a),new LinearLayout.LayoutParams(1,0,1));
        body.addView(button("BACK TO SIGN IN",R.drawable.ic_arrow_left,false,()->host.go("auth:signin")));
        return root;
    }
    private View emailSent(){
        LinearLayout root=col();root.setBackgroundColor(BG);
        LinearLayout header=row();header.addView(iconButton(R.drawable.ic_arrow_left,"Back to sign in",()->host.go("auth:signin")));root.addView(header,new LinearLayout.LayoutParams(-1,dp(52)));
        LinearLayout body=scrollBody(root,22);body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.addView(new View(a),new LinearLayout.LayoutParams(1,dp(56)));
        FrameLayout badge=new FrameLayout(a);badge.setBackground(shape(BG,BLUE,60));
        ImageView envelope=icon(R.drawable.ic_mail,BLUE,64);envelope.setScaleType(ImageView.ScaleType.FIT_CENTER);
        badge.addView(envelope,new FrameLayout.LayoutParams(dp(64),dp(64),Gravity.CENTER));
        body.addView(badge,new LinearLayout.LayoutParams(dp(110),dp(110)));
        TextView title=text("Check your email",30,INK,true);title.setGravity(Gravity.CENTER);LinearLayout.LayoutParams titleLp=gap(18);titleLp.topMargin=dp(30);body.addView(title,titleLp);
        TextView copy=text("We sent a secure reset link to\n"+s.email,18,MUTED,false);copy.setGravity(Gravity.CENTER);body.addView(copy,gap(22));
        LinearLayout status=row();status.setGravity(Gravity.CENTER);status.addView(icon(s.authError?R.drawable.ic_alert_triangle:R.drawable.ic_circle_check,s.authError?RED:LIME,24));TextView message=text(s.authStatus.isEmpty()?"Email sent":s.authStatus,18,s.authError?RED:LIME,false);message.setPadding(dp(6),0,0,0);status.addView(message);body.addView(status,gap(32));
        body.addView(new View(a),new LinearLayout.LayoutParams(1,0,1));
        body.addView(button("OPEN EMAIL APP",0,true,()->host.command("openEmail")),gap(12));
        body.addView(button("RESEND LINK",0,false,()->host.command("resendReset")),gap(18));
        body.addView(actionLink(R.drawable.ic_arrow_left,"USE A DIFFERENT EMAIL",BLUE,()->host.go("auth:recover")));
        return root;
    }
    private void accountProgress(LinearLayout body,int step){
        section(body,"Account setup","Step "+step+" of 3");LinearLayout progress=row();
        for(int i=0;i<3;i++){View segment=new View(a);segment.setBackground(shape(i<step?BUTTON:LINE,0,2));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(4),1);lp.rightMargin=i<2?dp(4):0;progress.addView(segment,lp);}body.addView(progress,gap(24));
    }
    private View profileSetup(){
        LinearLayout root=authBase(),body=scrollBody(root,20);accountProgress(body,2);body.addView(text("Set up your profile",30,INK,true),gap(22));
        LinearLayout avatar=col();avatar.setGravity(Gravity.CENTER);avatar.setBackground(shape(BG,BLUE,60));ImageView camera=icon(R.drawable.ic_camera,INK,32);camera.setScaleType(ImageView.ScaleType.FIT_CENTER);avatar.addView(camera);
        TextView avatarLabel=text("ADD PHOTO",13,BLUE,false);avatarLabel.setGravity(Gravity.CENTER);avatar.addView(avatarLabel);avatar.setContentDescription("Add profile photo");avatar.setOnClickListener(v->soon("Profile photos"));
        LinearLayout.LayoutParams avatarLp=new LinearLayout.LayoutParams(dp(108),dp(108));avatarLp.gravity=Gravity.CENTER_HORIZONTAL;avatarLp.bottomMargin=dp(22);body.addView(avatar,avatarLp);
        body.addView(text("Display name",17,INK,false),gap(6));EditText name=field("Set your display name",s.name,false);name.setFocusable(false);name.setContentDescription("Edit display name");name.setOnClickListener(v->{if(s.signedIn)host.command("editName");else host.go("auth:signin");});body.addView(name,gap(16));
        body.addView(text("Home region",17,INK,false),gap(6));LinearLayout region=row();region.setGravity(Gravity.CENTER_VERTICAL);region.setPadding(dp(10),dp(10),dp(6),dp(10));region.setBackground(shape(PANEL,LINE,4));region.addView(text("Coming soon",16,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));region.addView(icon(R.drawable.ic_chevron_down,INK,22));region.setContentDescription("Home region");region.setOnClickListener(v->soon("Home region"));body.addView(region,gap(16));
        body.addView(text("Primary activity",17,INK,false),gap(7));LinearLayout activities=row();View fishing=tool(R.drawable.ic_fish,"Fishing",()->message("Fishing","Fishing is the supported activity for this Android release."));fishing.setBackground(shape(BUTTON,BLUE,4));activities.addView(fishing,weight());activities.addView(tool(R.drawable.ic_route,"Hiking",()->soon("Hiking mode")),weight());activities.addView(tool(R.drawable.ic_current_location,"Hunting",()->soon("Hunting mode")),weight());body.addView(activities,gap(16));
        LinearLayout units=row();for(String unit:new String[]{"Metric","Imperial"}){TextView choice=(TextView)chip(unit,unit.equals("Imperial")==s.imperial,()->host.command(unit.toLowerCase(Locale.US)));choice.setAutoSizeTextTypeUniformWithConfiguration(14,18,1,android.util.TypedValue.COMPLEX_UNIT_SP);units.addView(choice,new LinearLayout.LayoutParams(0,dp(42),1));}body.addView(units,gap(16));
        body.addView(infoRow(R.drawable.ic_lock,"Catch locations","Private",()->message("Private locations","Your locations are not published publicly. Sharing controls are coming later.")),gap(8));body.addView(infoRow(R.drawable.ic_cloud,"Cloud backup",s.pending==0?"Up to date":s.pending+" queued",()->host.command("sync")),gap(20));
        View next=button("CONTINUE",0,true,()->host.go("flicRequired"));next.setMinimumHeight(dp(52));body.addView(next,gap(16));body.addView(actionLink(R.drawable.ic_arrow_left,"BACK",BLUE,()->host.go("profile")));return root;
    }
    private View flicRequired(){LinearLayout root=authBase(),body=scrollBody(root,18);section(body,"Device setup","Flic 2");ImageView device=new ImageView(a);device.setImageResource(R.drawable.flic2_setup_hero);device.setScaleType(ImageView.ScaleType.FIT_CENTER);body.addView(device,new LinearLayout.LayoutParams(-1,dp(180)));body.addView(text("Connect your field button",27,INK,false),gap(12));body.addView(text("Pair and test Flic 2 for instant field capture on the water.",16,MUTED,false),gap(16));body.addView(infoCard(R.drawable.ic_map_pin,"1 press","Save a waypoint",null),gap(10));body.addView(infoCard(R.drawable.ic_fish,"2 presses","Log a catch",null),gap(10));body.addView(infoCard(R.drawable.ic_fish_hook,"Hold","Record a tackle change",null),gap(16));body.addView(text("Bluetooth and location access are needed for field capture.",13,MUTED,false),gap(16));body.addView(button("SET UP FLIC 2",0,true,()->host.go("flicSetup")),gap(10));body.addView(actionLink(R.drawable.ic_arrow_right,"SET UP LATER",BLUE,()->host.command("continueLocal")));return root;}
    private View buildFlicSetup(){LinearLayout root=authBase(),body=scrollBody(root,18);section(body,"Set up your Flic 2","Step "+(s.setupStep+1)+" of 4");LinearLayout progress=row();for(int i=0;i<4;i++){View segment=new View(a);segment.setBackgroundColor(i<=s.setupStep?BLUE:LINE);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(3),1);p.setMargins(dp(2),0,dp(2),dp(18));progress.addView(segment,p);}body.addView(progress);String[] titles={"Ready for the water","Connect your button","Test your Flic 2","You're ready to fish"};body.addView(text(titles[Math.max(0,Math.min(3,s.setupStep))],28,INK,true),gap(14));
        boolean ready;
        if(s.setupStep==0){body.addView(text("Allow the permissions Fiskentra needs to capture moments reliably.",16,MUTED,false),gap(16));body.addView(accessCard(R.drawable.ic_map_pin,"Precise location","Required for field events",s.gps),gap(12));body.addView(accessCard(R.drawable.ic_bluetooth,"Nearby devices","Required for Flic 2",s.bluetooth),gap(12));body.addView(accessCard(R.drawable.ic_bell,"Notifications","Background capture status",s.notifications),gap(16));body.addView(button("ALLOW PERMISSIONS",0,true,()->host.command("permissions")),gap(18));ready=s.gps&&s.bluetooth;}
        else if(s.setupStep==1){ImageView device=new ImageView(a);device.setImageResource(R.drawable.flic2_setup_hero);device.setScaleType(ImageView.ScaleType.FIT_CENTER);body.addView(device,new LinearLayout.LayoutParams(-1,dp(190)));body.addView(text("Keep Flic 2 close to your phone. Press and hold the button when pairing starts.",16,MUTED,false),gap(14));body.addView(infoCard(R.drawable.ic_bluetooth,s.device.isEmpty()?"Flic 2":s.device,s.connected?"Connected":"Not connected",null),gap(12));body.addView(text(s.status,13,MUTED,false),gap(12));body.addView(button("PAIR FLIC 2",R.drawable.ic_bluetooth,true,()->host.command("pair")),gap(16));ready=s.connected;}
        else if(s.setupStep==2){body.addView(text("Press the physical button. These test presses will not create journal entries.",16,MUTED,false),gap(20));testRow(body,R.drawable.ic_map_pin,"Single press","Waypoint",s.singleTest);testRow(body,R.drawable.ic_fish,"Double press","Catch",s.doubleTest);testRow(body,R.drawable.ic_fish_hook,"Press and hold","Tackle change",s.holdTest);body.addView(text(s.connected?"Listening for your Flic 2…":"Reconnect Flic 2 to continue.",14,s.connected?BLUE:AMBER,false),gap(18));ready=s.singleTest&&s.doubleTest&&s.holdTest;}
        else{ImageView readyImage=new ImageView(a);readyImage.setImageResource(R.drawable.flic2_setup_hero);readyImage.setScaleType(ImageView.ScaleType.FIT_CENTER);body.addView(readyImage,new LinearLayout.LayoutParams(-1,dp(170)));body.addView(icon(R.drawable.ic_circle_check,LIME,42),gap(12));body.addView(text(s.connected&&s.singleTest&&s.doubleTest&&s.holdTest?"Your button is connected and tested. Use one press for a waypoint, two for a catch, and hold for a tackle change.":"Review the connection and test results below before heading out.",17,MUTED,false),gap(20));body.addView(infoCard(R.drawable.ic_bluetooth,"Flic 2",s.connected?"Connected":"Disconnected",null),gap(8));body.addView(infoCard(R.drawable.ic_map_pin,"Single press",s.singleTest?"Passed":"Not tested",null),gap(8));body.addView(infoCard(R.drawable.ic_fish,"Double press",s.doubleTest?"Passed":"Not tested",null),gap(8));body.addView(infoCard(R.drawable.ic_fish_hook,"Hold",s.holdTest?"Passed":"Not tested",null),gap(8));body.addView(infoCard(R.drawable.ic_current_location,"GPS",gps(),null),gap(8));ready=true;}
        body.addView(new View(a),new LinearLayout.LayoutParams(1,0,1));View next=button(s.setupStep==3?"ENTER FISKENTRA":"CONTINUE",R.drawable.ic_arrow_right,true,()->host.command("setupNext"));next.setEnabled(ready);next.setAlpha(ready?1f:.45f);body.addView(next,gap(12));if(s.setupStep>0)body.addView(actionLink(R.drawable.ic_arrow_left,"PREVIOUS STEP",MUTED,()->host.command("setupBack")),gap(8));body.addView(actionLink(R.drawable.ic_arrow_right,"SET UP LATER",MUTED,()->host.command("continueLocal")));return root;
    }
private void testRow(LinearLayout body,int id,String title,String value,boolean passed){int color=id==R.drawable.ic_fish?LIME:id==R.drawable.ic_map_pin?AMBER:PURPLE;LinearLayout card=row();card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(10),dp(14),dp(10),dp(14));card.setBackground(shape(PANEL,color,4));ImageView actionIcon=icon(id,color,42);actionIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);card.addView(actionIcon);LinearLayout copy=col();copy.setPadding(dp(10),0,0,0);copy.addView(text(title.toUpperCase(Locale.US),18,color,false));copy.addView(text(value,14,INK,false));card.addView(copy,new LinearLayout.LayoutParams(0,-2,1));LinearLayout result=col();result.setGravity(Gravity.CENTER);result.addView(icon(passed?R.drawable.ic_circle_check:R.drawable.ic_clock,passed?LIME:MUTED,28));TextView status=text(passed?"Passed":"Waiting",12,passed?LIME:MUTED,false);status.setGravity(Gravity.CENTER);result.addView(status);card.addView(result,new LinearLayout.LayoutParams(dp(64),-2));body.addView(card,gap(12));}

    private View buildPointEditor(){
        SavedPoint p=editing;if(p==null)return infoCard(R.drawable.ic_map_pin,"Choose a saved point","Open a saved item to edit it",()->host.go("saved"));
        LinearLayout root=base(false,false),body=scrollBody(root,10);section(body,"Edit saved point",date(p.timestamp,"MMM d · HH:mm"));
        body.addView(text("Update the details you will recognize later in the field.",15,MUTED,false),gap(10));
        EditText name=field("Optional custom name",p.title==null?"":p.title,false);name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(PointMetadata.MAX_TITLE_LENGTH)});
        EditText note=field("What should you remember here?",p.note==null?"":p.note,false);note.setSingleLine(false);note.setMinLines(3);note.setGravity(Gravity.TOP|Gravity.START);note.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(PointMetadata.MAX_NOTE_LENGTH)});
        String currentType=PointMetadata.isAllowedType(p.type)?p.type:"Waypoint";String[] selectedType={currentType};
        LinearLayout form=panel(4);form.addView(editorRow(R.drawable.ic_edit,"Name",name));form.addView(divider());
        LinearLayout typeRow=row();typeRow.setGravity(Gravity.CENTER_VERTICAL);typeRow.setPadding(dp(4),dp(7),dp(4),dp(7));typeRow.addView(icon(pointIcon(currentType),pointColor(currentType),20));TextView typeLabel=text("Type",14,INK,false);typeLabel.setPadding(dp(8),0,0,0);typeRow.addView(typeLabel,new LinearLayout.LayoutParams(0,-2,1));TextView typeValue=text(currentType,14,BLUE,false);typeValue.setGravity(Gravity.END);typeRow.addView(typeValue);typeRow.addView(icon(R.drawable.ic_chevron_down,INK,18));
        boolean catchLocked="Catch".equals(p.type)&&p.catchDetails!=null;typeRow.setContentDescription(catchLocked?"Type Catch. Catch type is locked while details exist":"Choose point type");typeRow.setOnClickListener(v->{if(catchLocked){message("Catch type is protected","Catch details are attached to this point. Editing or removing those details will be supported separately.");return;}int checked=Math.max(0,PointMetadata.TYPES.indexOf(selectedType[0]));String[] choices=PointMetadata.TYPES.toArray(new String[0]);new AlertDialog.Builder(a).setTitle("Point type").setSingleChoiceItems(choices,checked,(dialog,which)->{selectedType[0]=choices[which];typeValue.setText(choices[which]);dialog.dismiss();}).setNegativeButton("Cancel",null).show();});form.addView(typeRow);form.addView(divider());
        form.addView(infoRow(R.drawable.ic_map_pin,"Location",String.format(Locale.US,"%.5f, %.5f",p.latitude,p.longitude),()->host.point(p,"map")));form.addView(divider());form.addView(infoRow(R.drawable.ic_cloud,"Weather",p.weather==null?"Not captured":temperature(p.weather.temperatureC)+", "+p.weather.condition(),()->host.point(p,"weather")));form.addView(divider());
        LinearLayout noteBox=col();noteBox.setPadding(dp(8),dp(8),dp(8),dp(8));noteBox.addView(text("NOTE",12,MUTED,true),gap(4));noteBox.addView(note,new LinearLayout.LayoutParams(-1,dp(88)));form.addView(noteBox);body.addView(form,gap(8));
        body.addView(infoCard(R.drawable.ic_cloud,"Cloud sync","Type and note sync when a connection is available",null),gap(6));
        body.addView(infoCard(R.drawable.ic_lock,"Custom name","Stored on this phone in v0.17",null),gap(10));
        if("Catch".equals(p.type))body.addView(button("EDIT CATCH DETAILS",R.drawable.ic_fish,false,()->host.point(p,"catchEdit")),gap(8));
        LinearLayout footer=col();footer.setPadding(dp(10),dp(4),dp(10),dp(8));footer.addView(button("SAVE CHANGES",R.drawable.ic_check,true,()->{String error=PointMetadata.validate(name.getText().toString(),selectedType[0],note.getText().toString());if(!error.isEmpty()){message("Check point details",error);return;}host.savePoint(p,name.getText().toString(),selectedType[0],note.getText().toString());}),gap(6));footer.addView(button("KEEP WITHOUT CHANGES",0,false,()->host.go("saved")));root.addView(footer);return root;
    }

    private View buildCatchEditor(){
        SavedPoint p=editing;if(p==null)return infoCard(R.drawable.ic_fish,"Choose a catch","Open a saved catch to edit it",()->host.go("saved"));
        LinearLayout root=base(false,false),body=scrollBody(root,10);CatchDetails d=p.catchDetails;
        body.addView(infoCard(R.drawable.ic_circle_check,"Catch saved via GPS",host.syncLabel(p),null),gap(8));
        LinearLayout photos=row();photos.addView(pointPhoto(p,92),new LinearLayout.LayoutParams(0,dp(92),1));
        View add=tool(R.drawable.ic_camera,"Add photo",()->host.point(p,"photo"));LinearLayout.LayoutParams addLp=new LinearLayout.LayoutParams(0,dp(92),1);addLp.leftMargin=dp(8);photos.addView(add,addLp);body.addView(photos,gap(8));
        EditText species=field("Species",d==null?"":d.species,false),length=field("cm",d==null||d.lengthCm==0?"":String.valueOf(d.lengthCm),true),weight=field("kg",d==null||d.weightKg==0?"":String.valueOf(d.weightKg),true),lure=field("Lure / bait",d==null?"":d.lure,false),notes=field("Add a note…",d==null?"":d.notes,false);
        LinearLayout form=panel(4);form.addView(editorRow(R.drawable.ic_fish,"Species",species));form.addView(divider());form.addView(editorRow(R.drawable.ic_ruler,"Weight (kg)",weight));form.addView(divider());form.addView(editorRow(R.drawable.ic_ruler,"Length (cm)",length));form.addView(divider());form.addView(infoRow(R.drawable.ic_clock,"Time",date(p.timestamp,"HH:mm"),null));form.addView(divider());form.addView(infoRow(R.drawable.ic_map_pin,"Location",String.format(Locale.US,"%.5f, %.5f",p.latitude,p.longitude),()->host.point(p,"map")));form.addView(divider());form.addView(infoRow(R.drawable.ic_droplet,"Depth","Coming soon",()->soon("Depth logging")));form.addView(divider());form.addView(editorRow(R.drawable.ic_fish_hook,"Lure",lure));form.addView(divider());form.addView(infoRow(R.drawable.ic_fish_hook,"Tackle","Coming soon",()->soon("Tackle details")));form.addView(divider());form.addView(infoRow(R.drawable.ic_cloud,"Weather (auto)",p.weather==null?"Not captured":temperature(p.weather.temperatureC)+", "+p.weather.condition(),()->host.point(p,"weather")));form.addView(divider());form.addView(editorRow(R.drawable.ic_notebook,"Note",notes));form.addView(button("AUDIO NOTE",R.drawable.ic_microphone,false,()->soon("Audio notes")));body.addView(form,gap(8));
        CheckBox released=new CheckBox(a);released.setText("Released");released.setTextColor(INK);released.setTypeface(regular);released.setChecked(d!=null&&d.released);body.addView(released,gap(4));
        body.addView(infoRow(R.drawable.ic_lock,"Location sharing","Coming soon",()->soon("Location sharing controls")),gap(6));
        LinearLayout footer=col();footer.setPadding(dp(10),dp(4),dp(10),dp(8));
        footer.addView(button("SAVE CATCH",R.drawable.ic_check,true,()->{try{double cm=number(length),kg=number(weight);if(cm>1000||kg>10000)throw new IllegalArgumentException();host.saveCatch(p,new CatchDetails(species.getText().toString(),cm,kg,lure.getText().toString(),notes.getText().toString(),released.isChecked(),d==null?"":d.localPhotoPath));}catch(Exception e){message("Check measurements","Use positive numbers for length and weight.");}}),gap(6));
        footer.addView(button("KEEP WITHOUT CHANGES",0,false,()->host.go("saved")));root.addView(footer);return root;
    }
    private View editorRow(int id,String label,EditText field){LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.addView(icon(id,MUTED,20));TextView t=text(label,14,INK,false);t.setPadding(dp(8),0,0,0);r.addView(t,new LinearLayout.LayoutParams(dp(93),-2));field.setBackgroundColor(Color.TRANSPARENT);field.setTextSize(14);field.setGravity(Gravity.END);field.setPadding(dp(4),dp(4),dp(4),dp(4));field.setMinimumHeight(dp(38));r.addView(field,new LinearLayout.LayoutParams(0,dp(40),1));return r;}
    private double number(EditText e){String v=e.getText().toString().trim().replace(',','.');double n=v.isEmpty()?0:Double.parseDouble(v);if(!Double.isFinite(n)||n<0)throw new IllegalArgumentException();return n;}

    // Remaining screens are grouped below to keep all design-only state outside persistence.
    private View mapScreen(boolean tools, boolean active) { View screen=buildMapScreen(tools,active);if(tools||active)return screen;SwipeSwitchLayout swipe=new SwipeSwitchLayout(a);swipe.configure(true,new SwipeSwitchLayout.Listener(){public void onSwipeLeft(){host.go("home");}public void onSwipeRight(){host.go("home");}});swipe.addView(screen,new FrameLayout.LayoutParams(-1,-1));return swipe; }
    private View journal() { return buildJournal(); }
    private View saved() { return buildSaved(); }
    private View offlineMaps() { return buildOfflineMaps(); }
    private View devices() { return buildDevices(); }
    private View summary() { return buildSummary(); }
    private View account() { return buildAccount(); }
    private View welcome() { return buildWelcome(); }
    private View flicSetup() { return buildFlicSetup(); }
    private View pointEditor() { return buildPointEditor(); }
    private View catchEditor() { return buildCatchEditor(); }

    private LinearLayout col() { LinearLayout v = new LinearLayout(a); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(a); v.setOrientation(LinearLayout.HORIZONTAL); return v; }
    private LinearLayout panel(int padding) { LinearLayout v = col(); v.setPadding(dp(padding),dp(padding),dp(padding),dp(padding)); v.setBackground(shape(PANEL, LINE, 4)); return v; }
    private TextView text(String value, int size, int color, boolean bold) { TextView t = new TextView(a); t.setText(value); t.setTextSize(size<18?size+3:size); t.setTextColor(color); t.setTypeface(bold ? semibold : regular); t.setIncludeFontPadding(false); return t; }
    private ImageView icon(int id, int color, int size) { ImageView v = new ImageView(a); v.setImageResource(id); v.setColorFilter(color); v.setScaleType(ImageView.ScaleType.CENTER_INSIDE); v.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size))); v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return v; }
    private View iconButton(int id, String label, Runnable action) { FrameLayout frame = new FrameLayout(a); frame.setLayoutParams(new LinearLayout.LayoutParams(dp(40),dp(44))); ImageView i = icon(id, INK, 22); frame.addView(i,new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER)); frame.setContentDescription(label); frame.setOnClickListener(v -> action.run()); return frame; }
    private View button(String title, int id, boolean primary, Runnable action) { LinearLayout b = row(); b.setGravity(Gravity.CENTER); b.setMinimumHeight(dp(44)); b.setPadding(dp(5),dp(7),dp(5),dp(7)); b.setBackground(shape(primary ? BUTTON : BG, primary ? BUTTON : BLUE,4)); if(id!=0) { b.addView(icon(id, primary ? INK : BLUE,22)); View gap=new View(a); b.addView(gap,new LinearLayout.LayoutParams(dp(8),1)); } TextView t=text(title,16,primary?INK:BLUE,false); t.setGravity(Gravity.CENTER); b.addView(t); b.setContentDescription(title); b.setOnClickListener(v -> action.run()); return b; }
    private GradientDrawable shape(int fill, int border, int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); if(border!=0)d.setStroke(dp(1),border);return d; }
    private LinearLayout.LayoutParams gap(int bottom) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(bottom);return p; }
    private LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(2),0,dp(2),0);return p; }
    private void section(LinearLayout body,String title,String right) { LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);line.setPadding(0,dp(8),0,dp(8));line.addView(text(title,15,INK,true),new LinearLayout.LayoutParams(0,-2,1));line.addView(text(right,11,MUTED,false));body.addView(line); }
    private View divider() { View v=new View(a);v.setBackgroundColor(LINE);v.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(1)));return v; }
    private View metric(int id,String label,String value) { LinearLayout box=col();box.setPadding(dp(4),dp(5),dp(4),dp(5));LinearLayout line=row();line.addView(icon(id,MUTED,16));line.addView(text(label,11,MUTED,false));box.addView(line);box.addView(text(value,13,INK,false));return box; }
    private View tinyStatus(int id,String value,int color) { LinearLayout box=row();box.setGravity(Gravity.CENTER_VERTICAL);box.addView(icon(id,color,14));box.addView(text(value,10,color,false));return box; }
    private View infoRow(int id,String title,String value,Runnable action) { LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(4),dp(7),dp(4),dp(7));r.setMinimumHeight(dp(36));r.addView(icon(id,MUTED,22));TextView l=text(title,14,INK,false);l.setPadding(dp(8),0,dp(6),0);r.addView(l,new LinearLayout.LayoutParams(0,-2,1));TextView detail=text(value,13,MUTED,false);detail.setMaxWidth(dp(165));detail.setGravity(Gravity.END);r.addView(detail);if(action!=null){r.addView(icon(R.drawable.ic_chevron_right,INK,18));r.setOnClickListener(v->action.run());}return r; }
    private View infoCard(int id,String title,String value,Runnable action) { LinearLayout p=panel(4);p.addView(infoRow(id,title,value,action));return p; }
    private View toggle(int id,String title,boolean enabled,Runnable action) { LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(5),dp(4),dp(5),dp(4));r.addView(icon(id,MUTED,22));TextView label=text(title,14,INK,false);label.setPadding(dp(8),0,0,0);r.addView(label,new LinearLayout.LayoutParams(0,-2,1));Switch sw=new Switch(a);sw.setChecked(enabled);sw.setContentDescription(title);sw.setButtonTintList(ColorStateList.valueOf(BLUE));final boolean[] reverting={false};sw.setOnCheckedChangeListener((b,c)->{if(reverting[0])return;if(action==null){reverting[0]=true;sw.setChecked(enabled);reverting[0]=false;soon(title);}else action.run();});r.addView(sw);return r; }
    private EditText field(String hint,String value,boolean number) { EditText e=new EditText(a);e.setText(value);e.setHint(hint);e.setTextColor(INK);e.setHintTextColor(MUTED);e.setTextSize(16);e.setTypeface(regular);e.setSingleLine(true);e.setPadding(dp(10),dp(9),dp(10),dp(9));e.setBackground(shape(PANEL,LINE,4));e.setInputType(number?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT);e.setMinimumHeight(dp(44));return e; }
    private void watch(EditText edit,Runnable action) {edit.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence x,int st,int c,int af){}public void onTextChanged(CharSequence x,int st,int b,int c){action.run();}public void afterTextChanged(Editable e){}});}
    private void message(String title,String body) {new AlertDialog.Builder(a).setTitle(title).setMessage(body).setPositiveButton("OK",null).show();}
    private void soon(String feature) {message(feature,"Coming soon. This feature is shown in the design but is not available yet.");}
    private void checklist() {String[] items={"Fishing licence","Rod & reel","Tackle","Landing net","Life jacket","First-aid kit","Water","Charged phone","Flic 2"};boolean[] checked=new boolean[items.length];new AlertDialog.Builder(a).setTitle("Gear checklist").setMultiChoiceItems(items,checked,(d,w,c)->checked[w]=c).setPositiveButton("Done",null).show();}
    private String gps(){return !s.gpsEnabled?"Location is off":s.location==null?"Waiting for GPS":"GPS ±"+Math.round(s.location.getAccuracy())+" m";}
    private String coords(){return s.selected==null&&s.location==null&&!s.points.isEmpty()?String.format(Locale.US,"%.5f, %.5f",s.points.get(0).latitude,s.points.get(0).longitude):s.selected!=null?String.format(Locale.US,"%.5f, %.5f",s.selected.latitude,s.selected.longitude):s.location==null?"Waiting for location":String.format(Locale.US,"%.5f, %.5f",s.location.getLatitude(),s.location.getLongitude());}
    private String temperature(double c){return Math.round(s.imperial?c*9/5+32:c)+(s.imperial?"°F":"°C");}
    private String date(long time,String pattern){return new SimpleDateFormat(pattern,Locale.US).format(new Date(time));}
    private String shortDate(String iso){try{return new SimpleDateFormat("EEE d",Locale.US).format(new SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(iso));}catch(Exception ignored){return iso;}}
    private int score(int i){return s.forecast==null||s.forecast.days.size()<=i?-1:FishingAdvisor.assess(s.forecast.days.get(i),s.species).score;}
    private String bite(int i){int score=score(i);return score<0?"Not loaded":"Bite "+Math.max(1,Math.min(5,(score+19)/20))+" of 5";}
    private String assessmentText(int i){return s.forecast==null||s.forecast.days.size()<=i?"Load weather for a fishing estimate":FishingAdvisor.assess(s.forecast.days.get(i),s.species).reason;}
    private int scoreColor(int score){return score<0?MUTED:score>=65?LIME:score>=45?AMBER:RED;}
    private int weatherIcon(int code){if(code==0||code==1)return R.drawable.ic_sun;if(code==2)return R.drawable.ic_cloud_sun;if(code==45||code==48)return R.drawable.ic_cloud_fog;if(code>=95)return R.drawable.ic_cloud_storm;if(code>=71&&code<=86)return R.drawable.ic_snowflake;if(code>=51)return R.drawable.ic_cloud_rain;return R.drawable.ic_cloud;}
    private int pointIcon(String type){if("Catch".equals(type))return R.drawable.ic_fish;if("Tackle change".equals(type))return R.drawable.ic_fish_hook;if("Camp".equals(type))return R.drawable.ic_tent;if("Hazard".equals(type))return R.drawable.ic_alert_triangle;return R.drawable.ic_map_pin;}
    private int pointColor(String type){if("Catch".equals(type))return LIME;if("Tackle change".equals(type))return PURPLE;if("Hazard".equals(type))return RED;if("Camp".equals(type))return BLUE;return AMBER;}
    private int dp(float v){return Math.round(v*a.getResources().getDisplayMetrics().density);}

    /** Data visualization only: no invented hourly measurements or decorative image drawing. */
    private final class ForecastChart extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        ForecastChart(){super(a);setContentDescription("Daily weather-based fishing scores");}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float left=dp(24),right=getWidth()-dp(8),top=dp(8),bottom=getHeight()-dp(20);paint.setTypeface(regular);paint.setTextSize(dp(11));paint.setColor(LINE);paint.setStrokeWidth(dp(0.5f));for(int i=0;i<3;i++){float y=top+(bottom-top)*i/2;c.drawLine(left,y,right,y,paint);}if(s.forecast==null||s.forecast.days.isEmpty()){paint.setColor(MUTED);c.drawText("Load forecast to see activity",dp(40),(top+bottom)/2,paint);return;}int n=Math.min(5,s.forecast.days.size());Path line=new Path();for(int i=0;i<n;i++){float x=left+(right-left)*i/Math.max(1,n-1),y=bottom-(bottom-top)*score(i)/100f;if(i==0)line.moveTo(x,y);else line.lineTo(x,y);paint.setColor(MUTED);c.drawText(shortDate(s.forecast.days.get(i).date),Math.min(x,right-dp(25)),getHeight()-dp(4),paint);}paint.setColor(Color.rgb(24,225,196));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));c.drawPath(line,paint);paint.setStyle(Paint.Style.FILL);}
    }
}
