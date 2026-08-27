package com.fiskentra.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.fiskentra.app.FiskentraApplication;
import com.fiskentra.app.MainActivity;
import com.fiskentra.app.R;
import com.fiskentra.app.backend.SupabasePointSync;
import com.fiskentra.app.data.PointStore;
import com.fiskentra.app.flic.FiskentraFlic2Manager;
import com.fiskentra.app.location.FiskentraLocationManager;
import com.fiskentra.app.model.SavedPoint;

import java.util.ArrayList;
import java.util.List;

/** Keeps Flic 2, GPS and local point capture alive while the screen is off. */
public final class FiskentraFlicService extends Service implements
        FiskentraFlic2Manager.Listener, FiskentraLocationManager.Listener {

    private static final int NOTIFICATION_ID = 620;
    private static final String CHANNEL_ID = "fiskentra_field_button";
    private static final long MAX_LOCATION_AGE_MS = 30_000L;
    private static final long GPS_WAIT_TIMEOUT_MS = 20_000L;
    private static final String SYNC_PREFS = "fiskentra_point_sync_status";

    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<PendingAction> pendingActions = new ArrayList<>();

    private FiskentraFlic2Manager flicManager;
    private FiskentraLocationManager locationManager;
    private PointStore pointStore;
    private SupabasePointSync pointSync;
    private SharedPreferences syncPrefs;
    private NotificationManager notificationManager;
    private Location lastLocation;

    public static void start(Context context) {
        Intent intent = new Intent(context, FiskentraFlicService.class);
        context.startForegroundService(intent);
    }

    public static boolean isRunning() {
        return running;
    }

    @Override public void onCreate() {
        super.onCreate();
        FiskentraApplication application = (FiskentraApplication) getApplication();
        flicManager = application.getFlicManager();
        locationManager = new FiskentraLocationManager(this, this);
        pointStore = new PointStore(this);
        pointSync = new SupabasePointSync(this);
        syncPrefs = getSharedPreferences(SYNC_PREFS, MODE_PRIVATE);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        createNotificationChannel();
        try {
            enterForeground("Starting Flic 2 and GPS…");
        } catch (SecurityException error) {
            flicManager.reportActionResult(null, false,
                    "Background service needs Location and Nearby devices permissions");
            stopSelf();
            return;
        }

        running = true;
        flicManager.addListener(this);
        flicManager.attachAndConnectPairedButtons();
        locationManager.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        pendingActions.clear();
        if (locationManager != null) locationManager.stop();
        if (flicManager != null) flicManager.removeListener(this);
        if (pointSync != null) pointSync.close();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onLocation(Location location) {
        lastLocation = location;
        if (!isFresh(location) || pendingActions.isEmpty()) return;
        List<PendingAction> ready = new ArrayList<>(pendingActions);
        pendingActions.clear();
        for (PendingAction pending : ready) {
            if (SystemClock.elapsedRealtime() - pending.receivedAtMs <= GPS_WAIT_TIMEOUT_MS) {
                saveAction(pending.action, location);
            }
        }
    }

    @Override public void onAction(FiskentraFlic2Manager.Action action) {
        Location location = lastLocation != null ? lastLocation : locationManager.getLastLocation();
        if (isFresh(location)) {
            saveAction(action, location);
            return;
        }

        PendingAction pending = new PendingAction(action, SystemClock.elapsedRealtime());
        pendingActions.add(pending);
        updateNotification("Button received · waiting for a fresh GPS fix");
        handler.postDelayed(() -> expirePendingAction(pending), GPS_WAIT_TIMEOUT_MS);
    }

    @Override public void onStatus(String status) {
        updateNotification(status);
    }

    @Override public void onButtonChanged(String name, String address, boolean connected) {
        updateNotification(connected
                ? name + " connected · background capture active"
                : name + " reconnecting…");
    }

    @Override public void onStaleEventIgnored() {
        updateNotification("Old queued Flic press ignored safely");
    }

    private void saveAction(FiskentraFlic2Manager.Action action, Location location) {
        String type = pointType(action);
        SavedPoint point = pointStore.add(
                location.getLatitude(), location.getLongitude(), type, "Captured by Flic 2 in background");
        setSyncState(point.id, "syncing", "Syncing to Supabase");
        String message = type + " saved · " + coordinateSummary(location);
        updateNotification(message);
        flicManager.reportActionResult(action, true, message);

        pointSync.sync(point, (synced, syncMessage) -> {
            setSyncState(point.id, synced ? "synced" : "failed", syncMessage);
            if (!synced) updateNotification(type + " saved locally · cloud sync pending");
        });
    }

    private void expirePendingAction(PendingAction pending) {
        if (!pendingActions.remove(pending)) return;
        String message = "Flic press not saved · no fresh GPS fix within 20 seconds";
        updateNotification(message);
        flicManager.reportActionResult(pending.action, false, message);
    }

    private boolean isFresh(Location location) {
        if (location == null) return false;
        long elapsedNanos = location.getElapsedRealtimeNanos();
        if (elapsedNanos > 0L) {
            long ageNanos = SystemClock.elapsedRealtimeNanos() - elapsedNanos;
            return ageNanos >= 0L && ageNanos <= MAX_LOCATION_AGE_MS * 1_000_000L;
        }
        long ageMs = Math.abs(System.currentTimeMillis() - location.getTime());
        return ageMs <= MAX_LOCATION_AGE_MS;
    }

    private void setSyncState(long pointId, String state, String message) {
        syncPrefs.edit()
                .putString(pointId + "_state", state)
                .putString(pointId + "_message", message)
                .apply();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Fiskentra field button", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps Flic 2 and GPS capture available while the phone is locked");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private void enterForeground(String status) {
        Notification notification = notification(status);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            startForeground(NOTIFICATION_ID, notification, types);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String status) {
        if (notificationManager == null || !running) return;
        notificationManager.notify(NOTIFICATION_ID, notification(status));
    }

    private Notification notification(String status) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Fiskentra field button active")
                .setContentText(status)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    private static String pointType(FiskentraFlic2Manager.Action action) {
        if (action == FiskentraFlic2Manager.Action.CATCH) return "Catch";
        if (action == FiskentraFlic2Manager.Action.WAYPOINT) return "Waypoint";
        return "Tackle change";
    }

    private static String coordinateSummary(Location location) {
        return String.format(java.util.Locale.US, "%.5f, %.5f",
                location.getLatitude(), location.getLongitude());
    }

    private static final class PendingAction {
        final FiskentraFlic2Manager.Action action;
        final long receivedAtMs;

        PendingAction(FiskentraFlic2Manager.Action action, long receivedAtMs) {
            this.action = action;
            this.receivedAtMs = receivedAtMs;
        }
    }
}
