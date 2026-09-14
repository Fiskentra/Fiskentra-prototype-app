package com.fiskentra.app.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

public final class FiskentraLocationManager implements LocationListener {
    public interface Listener {
        void onLocation(Location location);
    }

    private final Context context;
    private final LocationManager manager;
    private final Listener listener;
    private Location lastLocation;
    private boolean started;

    public static boolean isFresh(Location location) {
        if (location == null || location.getElapsedRealtimeNanos() <= 0) return false;
        long age = android.os.SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        return age >= 0 && age <= 30_000_000_000L;
    }

    public boolean isEnabled() { return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER); }

    public FiskentraLocationManager(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (!hasPermission() || started) return;
        started = true;
        try {
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!manager.getAllProviders().contains(provider)) continue;
                Location cached = manager.getLastKnownLocation(provider);
                if (isFresh(cached)) onLocationChanged(cached);
                manager.requestLocationUpdates(provider, 2500L, 0f, this);
            }
        } catch (SecurityException ignored) {
            stop();
        }
    }

    public void stop() {
        manager.removeUpdates(this);
        started = false;
    }

    public Location getLastLocation() {
        return lastLocation;
    }

    @Override public void onLocationChanged(Location location) {
        if (!isFresh(location)) return;
        if (isFresh(lastLocation) && lastLocation.hasAccuracy() && location.hasAccuracy()
                && location.getAccuracy() > Math.max(25, lastLocation.getAccuracy() * 2)) return;
        lastLocation = location;
        listener.onLocation(location);
    }

    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
}
