package com.fiskentra.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.fiskentra.app.FiskentraApplication;
import com.fiskentra.app.MainActivity;
import com.fiskentra.app.R;
import com.fiskentra.app.backend.PointSyncQueue;
import com.fiskentra.app.data.PointStore;
import com.fiskentra.app.data.TrackStore;
import com.fiskentra.app.flic.FiskentraFlic2Manager;
import com.fiskentra.app.location.FiskentraLocationManager;
import com.fiskentra.app.model.SavedPoint;
import com.fiskentra.app.weather.WeatherClient;


/** Keeps Flic 2, GPS, local point capture and active trip recording alive screen-off. */
public final class FiskentraFlicService extends Service implements
        FiskentraFlic2Manager.Listener, FiskentraLocationManager.Listener {

    private static final int NOTIFICATION_ID = 620;
    private static final String CHANNEL_ID = "fiskentra_field_button";
    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService CAPTURE_IO = java.util.concurrent.Executors.newSingleThreadExecutor();

    private FiskentraFlic2Manager flicManager;
    private FiskentraLocationManager locationManager;
    private PointStore pointStore;
    private TrackStore trackStore;
    private PointSyncQueue syncQueue;
    private WeatherClient weatherClient;
    private NotificationManager notificationManager;
    private Location lastLocation;
    private boolean buttonConnected;
    private final Runnable signalTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            com.fiskentra.app.location.ConnectionAlerts.get(FiskentraFlicService.this).update(
                    locationManager.hasPermission() && locationManager.isEnabled() && FiskentraLocationManager.isFresh(lastLocation),
                    buttonConnected, flicManager.pairedButtonCount() > 0);
            handler.postDelayed(this, 3000);
        }
    };
    private final PointSyncQueue.Observer syncObserver = (pointId, state, message, pending) -> {
        if (PointSyncQueue.STATE_SYNCED.equals(state)) {
            updateNotification(pending == 0
                    ? getString(R.string.data_no_cloud_operations)
                    : "Point synced · " + pending + " still queued");
        } else if (PointSyncQueue.STATE_FAILED.equals(state)) {
            updateNotification("Point saved locally · automatic retry queued");
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, FiskentraFlicService.class);
        context.startForegroundService(intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, FiskentraFlicService.class));
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
        trackStore = new TrackStore(this);
        syncQueue = PointSyncQueue.get(this);
        weatherClient = new WeatherClient(this);
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

        flicManager.addListener(this);
        syncQueue.addObserver(syncObserver);
        running = true;
        handler.post(signalTick);
        if (flicManager.hasPermissions()) flicManager.attachAndConnectPairedButtons();
        locationManager.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (locationManager != null) locationManager.stop();
        if (flicManager != null) flicManager.removeListener(this);
        if (syncQueue != null) syncQueue.removeObserver(syncObserver);
        if (weatherClient != null) weatherClient.close();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onLocation(Location location) {
        lastLocation = location;
        Location copy = new Location(location);
        CAPTURE_IO.execute(() -> {
            if (trackStore != null && trackStore.add(copy))
                updateNotification("Trip recording in background · " + trackStore.points().size() + " route points");
        });
    }

    @Override public void onAction(FiskentraFlic2Manager.Action action) {
        onCapture(action, java.util.UUID.randomUUID().toString(), System.currentTimeMillis());
    }

    @Override public void onCapture(FiskentraFlic2Manager.Action action, String eventId, long occurredAtUtc) {
        if (action == FiskentraFlic2Manager.Action.TRACK_TOGGLE) {
            CAPTURE_IO.execute(() -> {
                try {
                    String message = trackStore.toggleRecording(eventId, occurredAtUtc);
                    updateNotification(message);
                    handler.post(() -> flicManager.reportActionResult(action, true, message));
                } catch (RuntimeException error) {
                    handler.post(() -> flicManager.reportActionResult(action, false, getString(R.string.data_save_failed)));
                }
            });
            return;
        }
        Location last = lastLocation != null ? lastLocation : locationManager.getLastLocation();
        Location capturedFix = last == null ? null : new Location(last);
        CAPTURE_IO.execute(() -> {
            try {
                SavedPoint point = pointStore.capture(eventId, occurredAtUtc, pointStore.tripIdAt(occurredAtUtc), pointType(action),
                        "Captured by Flic 2", capturedFix);
                if (point == null) return; // An already-deleted event receipt; never recreate it.
                String message = point.hasLocation() ? getString(R.string.data_saved_device) : getString(R.string.data_no_location);
                updateNotification(message);
                handler.post(() -> flicManager.reportActionResult(action, true, message));
                syncQueue.enqueue(point);
                if (point.hasLocation()) try { enrichWeatherThenSync(point, pointType(action)); }
                catch (RuntimeException ignored) { /* The event is already durable; enrichment is optional. */ }
            } catch (RuntimeException error) {
                String message = getString(R.string.data_save_failed);
                updateNotification(message);
                handler.post(() -> flicManager.reportActionResult(action, false, message));
            }
        });
    }

    @Override public void onStatus(String status) {
        updateNotification(status);
    }

    @Override public void onButtonChanged(String name, String address, boolean connected) {
        buttonConnected = connected;
        com.fiskentra.app.location.ConnectionAlerts.get(this).bluetooth(connected);
        updateNotification(connected
                ? name + " connected · background capture active"
                : name + " reconnecting…");
    }

    @Override public void onStaleEventIgnored() {
        updateNotification("Old queued Flic press ignored safely");
    }

    private void enrichWeatherThenSync(SavedPoint point, String type) {
        weatherClient.fetch(point.latitude, point.longitude, (weather, weatherMessage) -> {
            if (!running) return;
            SavedPoint enriched = pointStore.find(point.id);
            if (enriched == null) return;
            if (weather != null) {
                SavedPoint stored;
                try { stored = pointStore.updateWeather(point.id, weather); }
                catch (RuntimeException failure) { syncQueue.enqueue(enriched); return; }
                if (stored == null) return;
                enriched = stored;
                updateNotification(type + " saved · " + weather.compactSummary());
            }

            syncQueue.enqueue(enriched);
            updateNotification(syncQueue.hasValidatedInternet()
                    ? type + " saved · queued for cloud sync"
                    : type + " saved locally · automatic retry queued");
        });
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Fiskentra field activity", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps Flic 2 capture and active trip recording available while locked");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private void enterForeground(String status) {
        Notification notification = notification(status);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int types = locationManager.hasPermission() && locationManager.isEnabled()
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0;
            if (flicManager != null && flicManager.hasPermissions()
                    && flicManager.pairedButtonCount() > 0) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            }
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
                .setContentTitle(trackStore != null && trackStore.isActive()
                        ? (trackStore.isPaused() ? "Fiskentra trip paused" : "Fiskentra trip recording") : "Fiskentra field button active")
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

}
