package com.fiskentra.app.backend;

import com.fiskentra.app.data.PointLedger;
import com.fiskentra.app.model.FishingDay;
import com.fiskentra.app.model.SavedPoint;
import com.fiskentra.app.model.WeatherSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Portable, lossless user records. Device paths, numeric IDs and callback receipts never leave the phone. */
public final class BackupCodec {
    private BackupCodec() { }

    public static JSONObject point(SavedPoint point) throws Exception {
        JSONObject value = PointLedger.encode(point);
        value.remove("id"); value.remove("eventId"); value.remove("tripId"); value.remove("revision");
        JSONObject details = value.optJSONObject("catch_details");
        if (details != null) details.remove("local_photo_path");
        validatePoint(value);
        return value;
    }

    public static SavedPoint point(String owner, String key, JSONObject value, long localId,
            long tripId, String photoPath) throws Exception {
        uuid(owner); uuid(key); validatePoint(value);
        JSONObject copy = new JSONObject(value.toString());
        JSONObject details = copy.optJSONObject("catch_details");
        if (details != null) {
            details.remove("local_photo_path");
            if (photoPath != null && !photoPath.isEmpty()) details.put("local_photo_path", photoPath);
        } else if (photoPath != null && !photoPath.isEmpty()) throw new IllegalArgumentException("Photo has no catch details");
        copy.put("id", localId).put("eventId", CloudPointCodec.eventId(owner, key)).put("tripId", tripId).put("revision", 1);
        return PointLedger.decode(copy);
    }

    public static void validatePoint(JSONObject value) throws Exception {
        if (value.has("local_photo_path") || value.has("id") || value.has("eventId")
                || value.has("tripId") || value.has("revision")) throw new IllegalArgumentException("Device-only point data in backup");
        if (value.getLong("time") <= 0 || value.has("lat") != value.has("lon")) throw new IllegalArgumentException("Invalid point time/location");
        if (value.has("lat")) coordinate(value.getDouble("lat"), value.getDouble("lon"));
        if (value.has("locationAccuracy") && (!Double.isFinite(value.getDouble("locationAccuracy"))
                || value.getDouble("locationAccuracy") < 0)) throw new IllegalArgumentException("Invalid point accuracy");
        if (value.has("weather")) weather(value.getJSONObject("weather"));
        if (value.has("catch_details")) {
            JSONObject details = value.getJSONObject("catch_details");
            if (details.has("local_photo_path")) throw new IllegalArgumentException("Device photo path in backup");
            for (String key : new String[]{"length_cm", "weight_kg"}) {
                double number = details.optDouble(key, 0);
                if (!Double.isFinite(number) || number < 0) throw new IllegalArgumentException("Invalid catch measurement");
            }
        }
    }

    public static JSONObject trip(FishingDay day) throws Exception {
        boolean wasActive = day.isActive() || day.recoveredActive;
        JSONObject result = new JSONObject().put("started_at", day.startedAt)
                .put("ended_at", wasActive ? 0 : day.endedAt).put("was_active", wasActive)
                .put("was_paused", day.recoveredPaused).put("route", route(day.route));
        if (day.startWeather != null) result.put("start_weather", day.startWeather.toJson());
        if (day.endWeather != null) result.put("end_weather", day.endWeather.toJson());
        trip(result);
        return result;
    }

    public static FishingDay trip(JSONObject value) throws Exception {
        long start = value.getLong("started_at"), end = value.getLong("ended_at");
        boolean active = value.getBoolean("was_active"), paused = value.optBoolean("was_paused");
        if (start <= 0 || end < 0 || (end != 0 && end < start) || active != (end == 0))
            throw new IllegalArgumentException("Invalid trip time or activity");
        List<double[]> route = route(value.getJSONArray("route"));
        return new FishingDay(1, start, end, value.has("start_weather") ? weather(value.getJSONObject("start_weather")) : null,
                value.has("end_weather") ? weather(value.getJSONObject("end_weather")) : null,
                route, "", active, paused);
    }

    public static JSONArray route(List<double[]> points) throws Exception {
        JSONArray result = new JSONArray();
        for (double[] p : points) {
            if (p == null || p.length < 3 || !Double.isFinite(p[2]) || p[2] <= 0
                    || (p.length > 3 && p[3] != 0 && p[3] != 1)) throw new IllegalArgumentException("Invalid route sample");
            coordinate(p[0], p[1]);
            result.put(new JSONObject().put("lat", p[0]).put("lon", p[1]).put("time", p[2]).put("segment", p.length > 3 && p[3] == 1));
        }
        return result;
    }

    public static List<double[]> route(JSONArray points) throws Exception {
        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < points.length(); i++) {
            JSONObject p = points.getJSONObject(i);
            double lat = p.getDouble("lat"), lon = p.getDouble("lon"), time = p.getDouble("time");
            coordinate(lat, lon);
            if (!Double.isFinite(time) || time <= 0) throw new IllegalArgumentException("Invalid route time");
            result.add(new double[]{lat, lon, time, p.getBoolean("segment") ? 1 : 0});
        }
        return result;
    }

    public static String digest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024]; int length;
            while ((length = input.read(buffer)) != -1) hash.update(buffer, 0, length);
        }
        return hex(hash.digest());
    }

    /** Header validation prevents a tampered photo path from uploading private JSON/key files. */
    public static void photoFormat(File file) throws Exception {
        byte[] bytes = new byte[64]; int count;
        try (FileInputStream input = new FileInputStream(file)) { count = input.read(bytes); }
        boolean jpeg = count >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
        boolean png = count >= 8 && (bytes[0] & 255) == 137 && ascii(bytes, 1, 3).equals("PNG")
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10;
        boolean gif = count >= 6 && (ascii(bytes, 0, 6).equals("GIF87a") || ascii(bytes, 0, 6).equals("GIF89a"));
        boolean webp = count >= 12 && ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 4).equals("WEBP");
        boolean bmp = count >= 2 && ascii(bytes, 0, 2).equals("BM");
        boolean tiff = count >= 4 && ((ascii(bytes, 0, 2).equals("II") && bytes[2] == 42 && bytes[3] == 0)
                || (ascii(bytes, 0, 2).equals("MM") && bytes[2] == 0 && bytes[3] == 42));
        boolean heif = false;
        if (count >= 12 && ascii(bytes, 4, 4).equals("ftyp")) {
            for (int i = 8; i + 4 <= count; i += 4) {
                String brand = ascii(bytes, i, 4);
                if (brand.equals("heic") || brand.equals("heix") || brand.equals("hevc") || brand.equals("hevx")
                        || brand.equals("mif1") || brand.equals("msf1") || brand.equals("avif") || brand.equals("avis")) heif = true;
            }
        }
        if (!(jpeg || png || gif || webp || bmp || tiff || heif)) throw new IllegalArgumentException("A photo has an unsupported or invalid image header");
    }
    private static String ascii(byte[] data, int start, int length) { return new String(data, start, length, java.nio.charset.StandardCharsets.US_ASCII); }

    public static String fingerprint(JSONObject value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(CloudPointCodec.canonical(value)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    public static void hash(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid content digest"); }
    public static void uuid(String value) { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Invalid portable identity"); }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString(); }
    private static void coordinate(double lat, double lon) { if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new IllegalArgumentException("Invalid coordinate"); }
    private static WeatherSnapshot weather(JSONObject row) throws Exception {
        WeatherSnapshot weather = WeatherSnapshot.fromJson(row);
        if (weather == null || weather.observedAt <= 0) throw new IllegalArgumentException("Invalid weather");
        weather.toJson(); return weather;
    }
}
