package com.fiskentra.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.fiskentra.app.backend.SupabaseConnection;
import com.fiskentra.app.backend.SupabasePointSync;
import com.fiskentra.app.ble.FiskentraBleManager;
import com.fiskentra.app.data.PointStore;
import com.fiskentra.app.data.TrackStore;
import com.fiskentra.app.location.FiskentraLocationManager;
import com.fiskentra.app.model.SavedPoint;
import com.fiskentra.app.ui.MapCanvasView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements
        FiskentraLocationManager.Listener, FiskentraBleManager.Listener {

    private static final int REQUEST_PERMISSIONS = 1001;
    private static final int BG = Color.rgb(10, 18, 14);
    private static final int SURFACE = Color.rgb(18, 30, 24);
    private static final int SURFACE_2 = Color.rgb(24, 40, 32);
    private static final int TEXT = Color.rgb(242, 247, 244);
    private static final int MUTED = Color.rgb(156, 174, 163);
    private static final int ACCENT = Color.rgb(0, 174, 213);
    private static final int SUCCESS = Color.rgb(83, 214, 137);
    private static final int WARNING = Color.rgb(244, 190, 85);
    private static final int DANGER = Color.rgb(246, 114, 103);
    private static final String SYNC_PREFS = "fiskentra_point_sync_status";
    private static final String SYNC_SYNCING = "syncing";
    private static final String SYNC_SYNCED = "synced";
    private static final String SYNC_FAILED = "failed";

    private FrameLayout content;
    private LinearLayout nav;
    private PointStore pointStore;
    private TrackStore trackStore;
    private FiskentraLocationManager locationManager;
    private FiskentraBleManager bleManager;
    private SupabaseConnection supabaseConnection;
    private SupabasePointSync pointSync;
    private SharedPreferences syncPrefs;
    private Location lastLocation;
    private String screen = "home";
    private String bleStatus = "No device connected";
    private String connectedDevice = "";
    private boolean cloudConnected = false;
    private String cloudStatus = "Checking Fiskentra cloud…";
    private String cloudSyncStatus = "Saved points sync after each new moment";
    private LinearLayout deviceResults;
    private TextView deviceStatusText;
    private TextView buttonEventText;
    private String lastButtonEvent = "No button event yet";
    private final List<DeviceRow> discoveredDevices = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        pointStore = new PointStore(this);
        trackStore = new TrackStore(this);
        locationManager = new FiskentraLocationManager(this, this);
        bleManager = new FiskentraBleManager(this, this);
        supabaseConnection = new SupabaseConnection();
        pointSync = new SupabasePointSync(this);
        syncPrefs = getSharedPreferences(SYNC_PREFS, MODE_PRIVATE);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        content = new FrameLayout(this);
        page.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(8), dp(8), dp(10));
        nav.setBackgroundColor(Color.rgb(11, 21, 16));
        page.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));
        setContentView(page);

        render("home");
        supabaseConnection.check((connected, message) -> runOnUiThread(() -> {
            cloudConnected = connected;
            cloudStatus = message;
            if ("home".equals(screen)) render("home");
        }));
        requestNeededPermissions();
    }

    @Override protected void onResume() {
        super.onResume();
        if (locationManager.hasPermission()) locationManager.start();
    }

    @Override protected void onPause() {
        super.onPause();
        locationManager.stop();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        bleManager.close();
        supabaseConnection.close();
        pointSync.close();
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
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && locationManager.hasPermission()) locationManager.start();
        if (requestCode == REQUEST_PERMISSIONS) render(screen);
    }

    private void render(String next) {
        screen = next;
        content.removeAllViews();
        deviceResults = null;
        deviceStatusText = null;
        buttonEventText = null;
        switch (screen) {
            case "map": content.addView(mapScreen()); break;
            case "saved": content.addView(savedScreen()); break;
            case "device": content.addView(deviceScreen()); break;
            default: content.addView(homeScreen()); break;
        }
        renderNav();
    }

    private void renderNav() {
        nav.removeAllViews();
        addNav("⌂", "Home", "home");
        addNav("⌖", "Map", "map");
        addNav("◆", "Saved", "saved");
        addNav("◉", "Device", "device");
    }

    private void addNav(String icon, String label, String target) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(4), 0, dp(4), 0);
        TextView i = text(icon, 21, screen.equals(target) ? ACCENT : MUTED, Typeface.BOLD);
        i.setGravity(Gravity.CENTER);
        TextView l = text(label, 11, screen.equals(target) ? ACCENT : MUTED, Typeface.NORMAL);
        l.setGravity(Gravity.CENTER);
        item.addView(i);
        item.addView(l);
        item.setOnClickListener(v -> render(target));
        nav.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private View homeScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(18), dp(20), dp(28));
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

        TextView hello = text("Remember the moment.\nKeep moving.", 31, TEXT, Typeface.BOLD);
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

        LinearLayout trip = card();
        LinearLayout tripRow = row();
        tripRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tripCopy = vertical();
        boolean tracking = trackStore.isActive();
        tripCopy.addView(text(tracking ? "●  TRIP RECORDING" : "TRIP TRACK", 11, tracking ? ACCENT : MUTED, Typeface.BOLD));
        tripCopy.addView(text(tracking ? trackStore.points().size() + " track points" : "Record your route while Fiskentra is open", 13, TEXT, Typeface.NORMAL));
        tripRow.addView(tripCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button tripButton = smallButton(tracking ? "STOP" : "START");
        tripButton.setOnClickListener(v -> {
            if (tracking) trackStore.stop();
            else {
                trackStore.start();
                if (lastLocation != null) trackStore.add(lastLocation);
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
        deviceCopy.addView(text(connectedDevice.isEmpty() ? "SafeX Lite" : connectedDevice, 18, TEXT, Typeface.BOLD));
        deviceCopy.addView(text(bleStatus, 12, connectedDevice.isEmpty() ? MUTED : ACCENT, Typeface.NORMAL));
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

    private View mapScreen() {
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(20), dp(20), dp(20));
        body.addView(pageTitle("Explore", "YOUR FIELD MAP"));
        MapCanvasView map = new MapCanvasView(this);
        map.setBackground(roundRect(SURFACE, 20));
        map.setData(lastLocation, pointStore.all(), trackStore.points());
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        mapLp.setMargins(0, dp(18), 0, dp(14));
        body.addView(map, mapLp);

        LinearLayout notice = row();
        notice.setGravity(Gravity.CENTER_VERTICAL);
        TextView note = text("Offline prototype map · real tiles next", 12, MUTED, Typeface.NORMAL);
        notice.addView(note, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView count = text(pointStore.all().size() + " saved", 12, ACCENT, Typeface.BOLD);
        notice.addView(count);
        body.addView(notice);
        body.addView(spacer(14));
        Button button = primaryButton("＋  SAVE HERE");
        button.setOnClickListener(v -> saveCurrentMoment("Map"));
        body.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        return body;
    }

    private View savedScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(body);
        List<SavedPoint> points = pointStore.all();
        body.addView(pageTitle("Saved", points.size() + (points.size() == 1 ? " MOMENT" : " MOMENTS")));
        body.addView(spacer(18));
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
            for (SavedPoint p : points) body.addView(savedPointCard(p), cardMargins());
        }
        return scroll;
    }

    private View savedPointCard(SavedPoint point) {
        LinearLayout card = card();
        LinearLayout header = row();
        TextView type = text(point.type.toUpperCase(Locale.ROOT), 11, ACCENT, Typeface.BOLD);
        header.addView(type, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView delete = text("DELETE", 10, DANGER, Typeface.BOLD);
        delete.setPadding(dp(8), dp(4), 0, dp(4));
        delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete this moment?")
                .setMessage("The saved location will be removed from this device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> { pointStore.delete(point.id); render("saved"); })
                .show());
        header.addView(delete);
        card.addView(header);
        card.addView(spacer(8));
        card.addView(text(formatCoords(point.latitude, point.longitude), 17, TEXT, Typeface.BOLD));
        card.addView(text(formatDate(point.timestamp), 12, MUTED, Typeface.NORMAL));
        card.addView(spacer(8));
        card.addView(text(syncLabel(point.id), 12, syncColor(point.id), Typeface.BOLD));
        return card;
    }

    private View deviceScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(body);
        body.addView(pageTitle("Field button", "BLUEUP SAFEX LITE PROTOTYPE"));
        body.addView(spacer(18));

        LinearLayout status = card();
        status.addView(text(connectedDevice.isEmpty() ? "○  NOT CONNECTED" : "●  CONNECTED", 11,
                connectedDevice.isEmpty() ? MUTED : ACCENT, Typeface.BOLD));
        status.addView(spacer(8));
        status.addView(text(connectedDevice.isEmpty() ? "SafeX Lite" : connectedDevice, 24, TEXT, Typeface.BOLD));
        deviceStatusText = text(bleStatus, 13, MUTED, Typeface.NORMAL);
        status.addView(deviceStatusText);
        status.addView(spacer(8));
        status.addView(text(cloudSyncStatus, 12, cloudSyncColor(), Typeface.BOLD));
        status.addView(spacer(16));
        Button scan = primaryButton("⌁  SCAN FOR BLE DEVICES");
        scan.setOnClickListener(v -> {
            if (!bleManager.hasPermissions()) { requestNeededPermissions(); return; }
            discoveredDevices.clear();
            if (deviceResults != null) deviceResults.removeAllViews();
            bleManager.scan();
        });
        status.addView(scan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        body.addView(status);

        body.addView(sectionTitle("NEARBY DEVICES"));
        deviceResults = vertical();
        if (discoveredDevices.isEmpty()) {
            deviceResults.addView(text("Tap scan. SafeX / BlueUP devices will appear here with other nearby BLE hardware.", 13, MUTED, Typeface.NORMAL));
        } else {
            for (DeviceRow d : discoveredDevices) addDeviceResult(d);
        }
        body.addView(deviceResults);

        body.addView(sectionTitle("BUTTON FLOW TEST"));
        LinearLayout testCard = card();
        testCard.addView(text("Test button behavior before hardware arrives", 17, TEXT, Typeface.BOLD));
        testCard.addView(text("Single press and double press simulate the future SafeX Lite actions and save the current GPS position.", 13, MUTED, Typeface.NORMAL));
        testCard.addView(spacer(14));
        LinearLayout testActions = row();
        Button single = smallButton("SINGLE PRESS");
        single.setOnClickListener(v -> handleButtonPress("BLE single press", "Simulated single press saved a GPS point"));
        testActions.addView(single, weighted());
        testActions.addView(spaceWide());
        Button doublePress = smallButton("DOUBLE PRESS");
        doublePress.setOnClickListener(v -> handleButtonPress("BLE double press", "Simulated double press saved a priority GPS point"));
        testActions.addView(doublePress, weighted());
        testCard.addView(testActions);
        testCard.addView(spacer(12));
        buttonEventText = text(lastButtonEvent, 12, MUTED, Typeface.NORMAL);
        testCard.addView(buttonEventText);
        body.addView(testCard);

        body.addView(sectionTitle("PROTOTYPE STATUS"));
        body.addView(checkRow(true, "BLE scanning & connection"));
        body.addView(checkRow(true, "GPS location saving"));
        body.addView(checkRow(true, "Local offline point storage"));
        body.addView(checkRow(false, "SafeX button-packet decoder needs device profile/capture"));
        return scroll;
    }

    private View checkRow(boolean ready, String label) {
        LinearLayout row = row(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(8), 0, dp(8));
        TextView icon = text(ready ? "✓" : "→", 14, ready ? ACCENT : MUTED, Typeface.BOLD);
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(text(label, 13, ready ? TEXT : MUTED, Typeface.NORMAL));
        return row;
    }

    private void addDeviceResult(DeviceRow d) {
        if (deviceResults == null) return;
        if (deviceResults.getChildCount() == 1 && deviceResults.getChildAt(0) instanceof TextView) deviceResults.removeAllViews();
        LinearLayout card = row();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(10), dp(12));
        card.setBackground(roundRect(SURFACE, 14));
        LinearLayout copy = vertical();
        copy.addView(text(d.name, 14, TEXT, Typeface.BOLD));
        copy.addView(text(d.address + " · " + d.rssi + " dBm", 10, MUTED, Typeface.NORMAL));
        card.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button c = smallButton("CONNECT");
        c.setOnClickListener(v -> bleManager.connect(d.address));
        card.addView(c, new LinearLayout.LayoutParams(dp(92), dp(40)));
        LinearLayout.LayoutParams lp = matchWrap(); lp.setMargins(0, 0, 0, dp(8));
        deviceResults.addView(card, lp);
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
        syncPoint(point);
        if ("map".equals(screen) || "saved".equals(screen)) render(screen);
        return true;
    }

    private void syncPoint(SavedPoint point) {
        setSyncState(point.id, SYNC_SYNCING, "Syncing to Supabase");
        cloudSyncStatus = "Latest point: syncing to Supabase…";
        if ("home".equals(screen) || "saved".equals(screen) || "device".equals(screen)) render(screen);
        pointSync.sync(point, (synced, message) -> runOnUiThread(() -> {
            setSyncState(point.id, synced ? SYNC_SYNCED : SYNC_FAILED, message);
            cloudSyncStatus = synced ? "Latest point: synced to cloud" : "Latest point: saved locally · sync pending";
            if ("home".equals(screen) || "saved".equals(screen) || "device".equals(screen)) render(screen);
            if (!synced) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }));
    }

    private void setSyncState(long pointId, String state, String message) {
        syncPrefs.edit()
                .putString(syncKey(pointId, "state"), state)
                .putString(syncKey(pointId, "message"), message)
                .apply();
    }

    private String syncLabel(long pointId) {
        String state = syncPrefs.getString(syncKey(pointId, "state"), "");
        if (SYNC_SYNCED.equals(state)) return "●  Synced to cloud";
        if (SYNC_SYNCING.equals(state)) return "○  Syncing to Supabase…";
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
        if (SYNC_FAILED.equals(state)) return DANGER;
        return MUTED;
    }

    private int cloudSyncColor() {
        String value = cloudSyncStatus.toLowerCase(Locale.ROOT);
        if (value.contains("synced")) return SUCCESS;
        if (value.contains("pending") || value.contains("failed")) return DANGER;
        if (value.contains("syncing")) return WARNING;
        return MUTED;
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
            lastLocation = location;
            if (trackStore.isActive()) trackStore.add(location);
            if ("home".equals(screen) || "map".equals(screen)) render(screen);
        });
    }

    @Override public void onStatus(String status) {
        runOnUiThread(() -> {
            bleStatus = status;
            if (deviceStatusText != null) deviceStatusText.setText(status);
        });
    }

    @Override public void onDeviceFound(String name, String address, int rssi) {
        runOnUiThread(() -> {
            DeviceRow row = new DeviceRow(name, address, rssi);
            discoveredDevices.add(row);
            addDeviceResult(row);
        });
    }

    @Override public void onConnected(String name, String address) {
        runOnUiThread(() -> {
            connectedDevice = name + " · " + address.substring(Math.max(0, address.length() - 5));
            if ("device".equals(screen)) render("device");
        });
    }

    @Override public void onButtonCandidate(byte[] payload) {
        // Intentionally do not save a location yet. The SafeX profile must identify which
        // notification really represents a press; treating sensor/battery data as a press
        // would create false points. Surface the raw notification length for field testing.
        runOnUiThread(() -> {
            bleStatus = "Notification received · " + payload.length + " bytes · decoder pending";
            lastButtonEvent = "Raw BLE payload: " + hex(payload);
            if (deviceStatusText != null) deviceStatusText.setText(bleStatus);
            if (buttonEventText != null) buttonEventText.setText(lastButtonEvent);
        });
    }

    private View pageTitle(String title, String eyebrow) {
        LinearLayout box = vertical();
        box.addView(text(eyebrow, 11, ACCENT, Typeface.BOLD));
        box.addView(text(title, 31, TEXT, Typeface.BOLD));
        return box;
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
        v.setPadding(dp(17), dp(16), dp(17), dp(16));
        v.setBackground(roundRect(SURFACE, 18));
        return v;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(sp); t.setTextColor(color); t.setTypeface(Typeface.create("sans", style));
        t.setIncludeFontPadding(false); t.setLineSpacing(dp(2), 1.12f);
        return t;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label); b.setTextColor(Color.rgb(7, 22, 12)); b.setTextSize(13); b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(roundRect(ACCENT, 14)); b.setGravity(Gravity.CENTER);
        return b;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label); b.setTextColor(TEXT); b.setTextSize(10); b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(roundRect(SURFACE_2, 12)); b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d;
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

    private static String nowTime() {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
    }

    private static String formatDate(long time) {
        return new SimpleDateFormat("EEE, d MMM · HH:mm", Locale.getDefault()).format(new Date(time));
    }

    private static String hex(byte[] payload) {
        if (payload.length == 0) return "(empty)";
        StringBuilder out = new StringBuilder();
        int shown = Math.min(payload.length, 24);
        for (int i = 0; i < shown; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format(Locale.US, "%02X", payload[i] & 0xff));
        }
        if (payload.length > shown) out.append(" ...");
        return out.toString();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class DeviceRow {
        final String name; final String address; final int rssi;
        DeviceRow(String name, String address, int rssi) { this.name = name; this.address = address; this.rssi = rssi; }
    }
}
