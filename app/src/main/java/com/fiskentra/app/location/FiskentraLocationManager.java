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
        if (!hasPermission()) return;
        Location cached = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (cached == null) cached = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        if (cached != null) onLocationChanged(cached);
        if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2500L, 3f, this);
        }
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, this);
        }
    }

    public void stop() {
        manager.removeUpdates(this);
    }

    public Location getLastLocation() {
        return lastLocation;
    }

    @Override public void onLocationChanged(Location location) {
        lastLocation = location;
        listener.onLocation(location);
    }

    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
}
