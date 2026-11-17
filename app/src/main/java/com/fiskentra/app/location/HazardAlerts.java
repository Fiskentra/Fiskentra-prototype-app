package com.fiskentra.app.location;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;

import com.fiskentra.app.FiskentraApplication;
import com.fiskentra.app.MainActivity;
import com.fiskentra.app.R;
import com.fiskentra.app.data.MapHazardStore;
import com.fiskentra.app.model.MapHazard;

import java.util.List;

/** Delivers the same alert from the activity and the screen-off location service. */
public final class HazardAlerts {
    public static final String ACTION_OPEN_MAP = "com.fiskentra.app.action.HAZARD_MAP";
    private static final String LEGACY_CHANNEL_ID = "personal_hazards";
    private static final String PREVIOUS_CHANNEL_ID = "personal_hazards_v2";
    private static final String CHANNEL_ID = "personal_hazards_v3";
    private static final int NOTIFICATION_ID = 621;
    private static final long[] VIBRATION_TIMINGS_MS = {0, 110, 75, 110, 75, 110, 420, 170};

    private final Context context;
    private final NotificationManager notifications;
    private final MapHazardStore store;
    private final HazardProximityMonitor monitor = new HazardProximityMonitor();

    public static HazardAlerts get(Context context) {
        return ((FiskentraApplication) context.getApplicationContext()).getHazardAlerts();
    }

    public HazardAlerts(Context context) {
        this.context = context.getApplicationContext();
        store = MapHazardStore.get(this.context);
        notifications = this.context.getSystemService(NotificationManager.class);
        registerAlertChannel();
    }

    private void registerAlertChannel() {
        // Android keeps the vibration pattern of an existing notification channel.
        NotificationChannel legacy = notifications.getNotificationChannel(LEGACY_CHANNEL_ID);
        NotificationChannel previous = notifications.getNotificationChannel(PREVIOUS_CHANNEL_ID);
        NotificationChannel settings = previous == null ? legacy : previous;
        if (notifications.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.hazard_notification_channel),
                    settings == null ? NotificationManager.IMPORTANCE_HIGH : settings.getImportance());
            channel.setVibrationPattern(VIBRATION_TIMINGS_MS);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            if (settings == null) {
                channel.setSound(null, null);
                channel.setShowBadge(false);
            } else {
                channel.enableVibration(settings.shouldVibrate());
                channel.setSound(settings.getSound(), settings.getAudioAttributes());
                channel.setShowBadge(settings.canShowBadge());
                channel.enableLights(settings.shouldShowLights());
                channel.setLightColor(settings.getLightColor());
            }
            notifications.createNotificationChannel(channel);
        }
        if (previous != null) notifications.deleteNotificationChannel(PREVIOUS_CHANNEL_ID);
        if (legacy != null) notifications.deleteNotificationChannel(LEGACY_CHANNEL_ID);
    }

    public boolean canNotify() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationChannel channel = notifications.getNotificationChannel(CHANNEL_ID);
        return notifications.areNotificationsEnabled() && channel != null
                && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    public boolean isMonitoringNeeded() {
        return !store.snapshot().isEmpty() && hasPreciseLocation() && canNotify();
    }

    private boolean hasPreciseLocation() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void onLocation(Location fix) {
        List<MapHazard> hazards = store.snapshot();
        if (hazards.isEmpty() || !hasPreciseLocation() || !canNotify()) return;
        List<MapHazard> nearby = monitor.update(fix, hazards);
        if (nearby.isEmpty()) return;

        String name = nearby.get(0).name;
        int others = nearby.size() - 1;
        String message = others == 0 ? name : context.getResources().getQuantityString(
                R.plurals.hazard_notification_nearby, others, name, others);
        Intent openMap = new Intent(context, MainActivity.class).setAction(ACTION_OPEN_MAP);
        PendingIntent open = PendingIntent.getActivity(context, NOTIFICATION_ID, openMap,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        notifications.notify(NOTIFICATION_ID, new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_fiskentra)
                .setContentTitle(context.getString(R.string.hazard_notification_title))
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_EVENT)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }
}
