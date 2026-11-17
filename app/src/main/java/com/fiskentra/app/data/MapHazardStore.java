package com.fiskentra.app.data;

import android.content.Context;

import com.fiskentra.app.model.MapHazard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import timber.log.Timber;

/** Shared, device-local personal hazards. Reading a snapshot performs no disk I/O. */
public final class MapHazardStore {
    private static MapHazardStore instance;

    private final PointLedger.AtomicDisk disk;
    private List<MapHazard> hazards;

    private MapHazardStore(Context context) {
        disk = new PointLedger.AtomicDisk(new File(context.getNoBackupFilesDir(), "map-hazards-v1.json"));
        hazards = read();
    }

    public static synchronized MapHazardStore get(Context context) {
        if (instance == null) instance = new MapHazardStore(context.getApplicationContext());
        return instance;
    }

    public synchronized List<MapHazard> snapshot() {
        return hazards;
    }

    public synchronized MapHazard find(String id) {
        for (MapHazard hazard : hazards) {
            if (hazard.id.equals(id)) return hazard;
        }
        return null;
    }

    public synchronized MapHazard add(String name, double latitude, double longitude, int radiusMeters) {
        MapHazard hazard = new MapHazard(UUID.randomUUID().toString(), name, latitude, longitude, radiusMeters);
        List<MapHazard> updated = new ArrayList<>(hazards);
        updated.add(hazard);
        write(updated);
        return hazard;
    }

    public synchronized boolean delete(String id) {
        List<MapHazard> updated = new ArrayList<>(hazards);
        if (!updated.removeIf(hazard -> hazard.id.equals(id))) return false;
        write(updated);
        return true;
    }

    private List<MapHazard> read() {
        JSONArray rows;
        try {
            String stored = disk.read();
            if (stored == null) return Collections.emptyList();
            rows = new JSONArray(stored);
        } catch (IOException | JSONException error) {
            Timber.w("Could not read personal hazards");
            return Collections.emptyList();
        }
        List<MapHazard> loaded = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i);
                MapHazard hazard = new MapHazard(row.getString("id"), row.getString("name"),
                        row.getDouble("lat"), row.getDouble("lon"), row.getInt("radius_m"));
                if (ids.add(hazard.id)) loaded.add(hazard);
            } catch (JSONException | IllegalArgumentException error) {
                Timber.w("Skipped an invalid personal hazard");
            }
        }
        return Collections.unmodifiableList(loaded);
    }

    private void write(List<MapHazard> updated) {
        try {
            JSONArray rows = new JSONArray();
            for (MapHazard hazard : updated) {
                rows.put(new JSONObject().put("id", hazard.id).put("name", hazard.name)
                        .put("lat", hazard.latitude).put("lon", hazard.longitude)
                        .put("radius_m", hazard.radiusMeters));
            }
            disk.write(rows.toString());
        } catch (IOException | JSONException error) {
            throw new IllegalStateException("Could not save personal hazards", error);
        }
        hazards = Collections.unmodifiableList(updated);
    }
}
