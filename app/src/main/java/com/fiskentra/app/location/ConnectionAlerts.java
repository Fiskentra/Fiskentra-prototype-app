package com.fiskentra.app.location;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.os.Build;
import android.widget.Toast;
import com.fiskentra.app.MainActivity;

public final class ConnectionAlerts {
    private final Context context;
    private Boolean gps, internet;
    private boolean bluetooth;
    private static ConnectionAlerts instance;
    public static synchronized ConnectionAlerts get(Context context) { if (instance == null) instance = new ConnectionAlerts(context.getApplicationContext()); return instance; }
    private final NotificationManager notifications;
    private ConnectionAlerts(Context context) {
        this.context = context;
        notifications = context.getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("field_alerts", "Field connection alerts", NotificationManager.IMPORTANCE_DEFAULT));
    }
    public String update(boolean freshGps, boolean connected, boolean paired) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        boolean online = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        if (Boolean.TRUE.equals(gps) && !freshGps) loss(51, "GPS signal lost", "Waiting for a fresh location. Points can still be placed on the map.");
        if (Boolean.TRUE.equals(internet) && !online) loss(52, "Internet connection lost", "Downloaded maps and local points remain available.");
        if (freshGps) notifications.cancel(51);
        if (online) notifications.cancel(52);
        gps = freshGps; internet = online;
        bluetooth(connected);
        return describe(freshGps, connected, paired);
    }
    public String describe(boolean freshGps, boolean connected, boolean paired) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        boolean online = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        return "GPS · " + (freshGps ? "ready" : "unavailable") + "   Internet · " + (online ? "online" : "offline")
                + "\nFlic · " + (connected ? "connected" : paired ? "disconnected" : "not paired");
    }
    public void bluetooth(boolean connected) {
        if (bluetooth && !connected) loss(53, "Flic Bluetooth connection lost", "You can continue saving points directly on the map.");
        bluetooth = connected;
        if (connected) notifications.cancel(53);
    }
    private void loss(int id, String title, String message) {
        Toast.makeText(context, title, Toast.LENGTH_LONG).show();
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        notifications.notify(id, new Notification.Builder(context, "field_alerts").setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title).setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message)).setContentIntent(open).setAutoCancel(true).build());
    }
}
