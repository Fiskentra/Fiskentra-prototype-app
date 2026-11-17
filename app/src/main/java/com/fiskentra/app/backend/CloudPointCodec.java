package com.fiskentra.app.backend;

import com.fiskentra.app.data.PointLedger;
import com.fiskentra.app.model.SavedPoint;
import com.fiskentra.app.model.CatchDetails;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Portable point details only: photos, trip geometry and device paths are not a cloud backup. */
public final class CloudPointCodec {
    private CloudPointCodec() {}
    public static JSONObject encode(SavedPoint point) throws Exception {
        JSONObject payload = PointLedger.encode(point);
        payload.remove("id"); payload.remove("eventId"); payload.remove("revision"); payload.remove("tripId");
        JSONObject details = payload.optJSONObject("catch_details");
        if (details != null) details.remove("local_photo_path");
        return payload;
    }
    public static String eventId(String owner, String remoteId) { return "cloud:" + owner + ":" + remoteId; }
    public static SavedPoint merge(SavedPoint local, SavedPoint incoming) {
        CatchDetails details = incoming.catchDetails;
        if (local.catchDetails != null && !local.catchDetails.localPhotoPath.isEmpty()) {
            details = details == null ? local.catchDetails : details.withLocalPhotoPath(local.catchDetails.localPhotoPath);
        }
        return new SavedPoint(local.id, incoming.latitude, incoming.longitude, local.timestamp, incoming.type, incoming.note,
                incoming.weather, details, incoming.title, incoming.symbol, incoming.color, incoming.size,
                local.eventId, local.tripId, incoming.favorite, local.revision, incoming.locationState,
                incoming.locatedAtUtc, incoming.locationAccuracy, incoming.locationSource);
    }
    public static SavedPoint decode(String owner, String remoteId, JSONObject payload, long localId) throws Exception {
        JSONObject copy = new JSONObject(payload.toString());
        if (!copy.has("time") || copy.getLong("time") <= 0) throw new IllegalArgumentException("Invalid cloud timestamp");
        if (copy.has("lat") != copy.has("lon")) throw new IllegalArgumentException("Incomplete cloud position");
        if (copy.has("lat") && (!Double.isFinite(copy.getDouble("lat")) || !Double.isFinite(copy.getDouble("lon"))
                || Math.abs(copy.getDouble("lat")) > 90 || Math.abs(copy.getDouble("lon")) > 180))
            throw new IllegalArgumentException("Invalid cloud position");
        JSONObject details = copy.optJSONObject("catch_details");
        if (details != null) details.remove("local_photo_path");
        copy.put("id", localId).put("eventId", eventId(owner, remoteId)).put("revision", 1).put("tripId", 0);
        return PointLedger.decode(copy);
    }
    /** Stable comparisons are independent of JSON object key order. */
    public static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>(); java.util.Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next()); Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) { if (out.length() > 1) out.append(','); out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))); }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) { if (i > 0) out.append(','); out.append(canonical(array.get(i))); }
            return out.append(']').toString();
        }
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof String) return JSONObject.quote((String) value);
        String wrapped = new JSONArray().put(value).toString();
        return wrapped.substring(1, wrapped.length() - 1);
    }
}
