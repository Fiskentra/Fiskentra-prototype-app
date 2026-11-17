package com.fiskentra.app.location;

import android.location.Location;
import android.os.SystemClock;

import com.fiskentra.app.model.FieldNavigation;
import com.fiskentra.app.model.LocationQualityPolicy;
import com.fiskentra.app.model.MapHazard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tracks approaches from both map and background location updates. */
public final class HazardProximityMonitor {
    private static final int REARM_DISTANCE_METERS = MapHazard.WARNING_DISTANCE_METERS + 30;

    private final Set<String> nearby = new HashSet<>();
    private List<MapHazard> lastHazards = Collections.emptyList();
    private long lastFixNanos;

    public List<MapHazard> update(Location fix, List<MapHazard> hazards) {
        if (fix == null) return Collections.emptyList();
        return update(fix.getLatitude(), fix.getLongitude(), fix.hasAccuracy() ? fix.getAccuracy() : Double.NaN,
                fix.getElapsedRealtimeNanos(), SystemClock.elapsedRealtimeNanos(), hazards);
    }

    public synchronized List<MapHazard> update(double latitude, double longitude, double accuracyMeters,
            long fixNanos, long nowNanos, List<MapHazard> hazards) {
        if (fixNanos <= lastFixNanos || !LocationQualityPolicy.guidance(fixNanos, nowNanos, accuracyMeters)
                || !FieldNavigation.validCoordinate(latitude, longitude)) return Collections.emptyList();
        lastFixNanos = fixNanos;
        if (hazards != lastHazards) {
            // An immutable store snapshot changes only when the user saves or deletes a hazard.
            Set<String> ids = new HashSet<>();
            for (MapHazard hazard : hazards) ids.add(hazard.id);
            nearby.retainAll(ids);
            lastHazards = hazards;
        }
        List<MapHazard> approaching = Collections.emptyList();
        for (MapHazard hazard : hazards) {
            double distance = hazard.distanceMeters(latitude, longitude);
            if (distance >= REARM_DISTANCE_METERS) {
                nearby.remove(hazard.id);
            } else if (distance <= MapHazard.WARNING_DISTANCE_METERS && nearby.add(hazard.id)) {
                if (approaching.isEmpty()) approaching = new ArrayList<>();
                approaching.add(hazard);
            }
        }
        return approaching;
    }
}
