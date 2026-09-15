package com.fiskentra.app.navigation;

import com.fiskentra.app.model.BacktrackRoute;
import com.fiskentra.app.model.BacktrackProgress;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Versioned persistence format; stores the chosen snapshot rather than a mutable TrackStore pointer. */
public final class BacktrackCodec {
    private BacktrackCodec() { }
    public static String key(BacktrackRoute route) { return route.id + ":" + route.revision + ":" + route.capturedAtUtc; }
    public static String encodeProgress(BacktrackRoute route, BacktrackProgress.Snapshot state) throws Exception {
        return new JSONObject().put("schema", 1).put("snapshot", key(route)).put("status", state.status.name()).put("segment", state.segmentIndex)
                .put("edge", state.edgeIndex).put("fraction", state.fraction).put("along", state.alongMeters).toString();
    }
    public static BacktrackProgress restoreProgress(BacktrackProgress fallback, String value) throws Exception {
        if (value == null || value.isEmpty()) return fallback;
        JSONObject state = new JSONObject(value);
        if (state.getInt("schema") != 1 || !key(fallback.route).equals(state.getString("snapshot"))) return fallback;
        return BacktrackProgress.restore(fallback.route, state.getInt("segment"), state.getInt("edge"), state.getDouble("fraction"), state.getDouble("along"), BacktrackProgress.Status.valueOf(state.getString("status")));
    }
    public static String encode(BacktrackRoute route, BacktrackProgress.Snapshot state) throws Exception {
        JSONArray segments = new JSONArray();
        for (BacktrackRoute.Segment segment : route.segments) {
            JSONArray geometry = new JSONArray();
            for (BacktrackRoute.Point point : segment.points) geometry.put(new JSONArray().put(point.latitude).put(point.longitude));
            segments.put(new JSONObject().put("id", segment.id).put("points", geometry));
        }
        return new JSONObject().put("schema", 1).put("id", route.id).put("revision", route.revision).put("captured_at", route.capturedAtUtc)
                .put("segments", segments).put("status", state.status.name()).put("segment", state.segmentIndex)
                .put("edge", state.edgeIndex).put("fraction", state.fraction).put("along", state.alongMeters).toString();
    }
    public static BacktrackProgress decode(String value) throws Exception {
        if (value == null || value.isEmpty()) return null;
        JSONObject json = new JSONObject(value);
        if (json.getInt("schema") != 1) throw new IllegalArgumentException("Unknown backtrack version");
        JSONArray segments = json.getJSONArray("segments"); List<BacktrackRoute.Segment> parts = new ArrayList<>(); int count = 0;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject part = segments.getJSONObject(i); JSONArray coords = part.getJSONArray("points"); List<BacktrackRoute.Point> points = new ArrayList<>();
            for (int j = 0; j < coords.length(); j++) {
                if (++count > 250000) throw new IllegalArgumentException("Backtrack too large");
                JSONArray point = coords.getJSONArray(j); points.add(new BacktrackRoute.Point(point.getDouble(0), point.getDouble(1)));
            }
            parts.add(new BacktrackRoute.Segment(part.getString("id"), points));
        }
        BacktrackRoute route = new BacktrackRoute(json.getString("id"), json.getLong("revision"), json.getLong("captured_at"), parts);
        return BacktrackProgress.restore(route, json.getInt("segment"), json.getInt("edge"), json.getDouble("fraction"), json.getDouble("along"), BacktrackProgress.Status.valueOf(json.getString("status")));
    }
}
