package com.fiskentra.app.model;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Optional details attached to a saved Catch point. */
public final class CatchDetails {
    public final String species;
    public final double lengthCm;
    public final double weightKg;
    public final String lure;
    public final String notes;
    public final boolean released;
    public final String localPhotoPath;

    public CatchDetails(String species, double lengthCm, double weightKg, String lure,
            String notes, boolean released, String localPhotoPath) {
        this.species = clean(species);
        this.lengthCm = Math.max(0d, lengthCm);
        this.weightKg = Math.max(0d, weightKg);
        this.lure = clean(lure);
        this.notes = clean(notes);
        this.released = released;
        this.localPhotoPath = clean(localPhotoPath);
    }

    public CatchDetails withLocalPhotoPath(String path) {
        return new CatchDetails(species, lengthCm, weightKg, lure, notes, released, path);
    }

    public JSONObject toJson() throws Exception {
        JSONObject value = toCloudJson();
        if (!localPhotoPath.isEmpty()) value.put("local_photo_path", localPhotoPath);
        return value;
    }

    /** Device file paths are intentionally excluded from cloud data. */
    public JSONObject toCloudJson() throws Exception {
        JSONObject value = new JSONObject();
        value.put("species", species);
        value.put("length_cm", lengthCm);
        value.put("weight_kg", weightKg);
        value.put("lure", lure);
        value.put("notes", notes);
        value.put("released", released);
        return value;
    }

    public static CatchDetails fromJson(JSONObject value) {
        if (value == null) return null;
        return new CatchDetails(
                value.optString("species", ""),
                value.optDouble("length_cm", 0d),
                value.optDouble("weight_kg", 0d),
                value.optString("lure", ""),
                value.optString("notes", ""),
                value.optBoolean("released", false),
                value.optString("local_photo_path", ""));
    }

    public String summary() {
        List<String> parts = new ArrayList<>();
        parts.add(species.isEmpty() ? "Unidentified catch" : species);
        if (lengthCm > 0d) parts.add(String.format(Locale.getDefault(), "%.1f cm", lengthCm));
        if (weightKg > 0d) parts.add(String.format(Locale.getDefault(), "%.2f kg", weightKg));
        parts.add(released ? "Released" : "Kept");
        return join(parts, " · ");
    }

    public String secondarySummary() {
        List<String> parts = new ArrayList<>();
        if (!lure.isEmpty()) parts.add("Lure: " + lure);
        if (!notes.isEmpty()) parts.add(notes);
        return join(parts, " · ");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String join(List<String> values, String separator) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) continue;
            if (out.length() > 0) out.append(separator);
            out.append(value);
        }
        return out.toString();
    }
}
