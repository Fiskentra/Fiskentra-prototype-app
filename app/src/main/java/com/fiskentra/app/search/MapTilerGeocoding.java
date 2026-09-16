package com.fiskentra.app.search;

import com.fiskentra.app.model.FieldNavigation;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Native HTTP geocoder. No provider responses, names, keys or request URLs are persisted or logged. */
public final class MapTilerGeocoding implements PlaceSearchProvider {
    public static final String ENDPOINT = "https://api.maptiler.com/geocoding/";
    private static final String ATTRIBUTION = "<a href=\"https://www.maptiler.com/copyright/\">© MapTiler</a> · <a href=\"https://www.openstreetmap.org/copyright\">© OpenStreetMap contributors</a>";
    private final String endpoint, key;
    public MapTilerGeocoding(String key) { this(ENDPOINT, key); }
    public MapTilerGeocoding(String endpoint, String key) { this.endpoint = endpoint; this.key = key == null ? "" : key.trim(); }
    public static String encodedQuery(String query) {
        try { return URLEncoder.encode(query, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }
    public String requestUrl(Request request) throws Failure {
        if (key.isEmpty() || endpoint == null || !endpoint.startsWith("https://")) throw new Failure(Error.CONFIGURATION);
        StringBuilder value = new StringBuilder(endpoint.endsWith("/") ? endpoint : endpoint + "/");
        value.append(encodedQuery(request.query)).append(".json?key=").append(encodedQuery(key)).append("&limit=10&autocomplete=true&language=en");
        if (FieldNavigation.validCoordinate(request.centerLatitude, request.centerLongitude)) value.append("&proximity=").append(request.centerLongitude).append(',').append(request.centerLatitude);
        double[] bounds = request.bounds();
        if (bounds != null) value.append("&bbox=").append(bounds[0]).append(',').append(bounds[1]).append(',').append(bounds[2]).append(',').append(bounds[3]);
        return value.toString();
    }
    @Override public List<PlaceResult> search(Request request, Cancellation cancellation) throws Failure {
        if (cancellation.cancelled()) throw new Failure(Error.CANCELLED);
        HttpURLConnection connection = null;
        try {
            URL url = new URL(requestUrl(request));
            if (url.getUserInfo() != null || !"https".equals(url.getProtocol())) throw new Failure(Error.CONFIGURATION);
            connection = (HttpURLConnection) url.openConnection(); HttpURLConnection active = connection;
            cancellation.onCancel(active::disconnect);
            connection.setConnectTimeout(4000); connection.setReadTimeout(6000); connection.setRequestMethod("GET"); connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false); connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("User-Agent", "Fiskentra Android map search");
            long deadline = System.nanoTime() + 10_000_000_000L;
            int status = connection.getResponseCode();
            if (status != 200) throw httpFailure(status, connection.getHeaderField("Retry-After"), System.currentTimeMillis());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (cancellation.cancelled()) throw new Failure(Error.CANCELLED);
                    if (System.nanoTime() > deadline) throw new Failure(Error.TIMEOUT);
                    if (bytes.size() + count > 1_000_000) throw new Failure(Error.MALFORMED);
                    bytes.write(buffer, 0, count);
                }
            }
            if (cancellation.cancelled()) throw new Failure(Error.CANCELLED);
            return parse(bytes.toString("UTF-8"), System.currentTimeMillis());
        } catch (Failure error) { throw error; }
        catch (SocketTimeoutException error) { throw new Failure(Error.TIMEOUT); }
        catch (UnknownHostException | ConnectException error) { throw new Failure(Error.OFFLINE); }
        catch (IOException error) { throw new Failure(cancellation.cancelled() ? Error.CANCELLED : Error.SERVICE); }
        finally { cancellation.onCancel(null); if (connection != null) connection.disconnect(); }
    }
    public static Failure httpFailure(int status, String retryAfter, long now) {
        if (status == 401 || status == 403 || status == 400) return new Failure(Error.CONFIGURATION);
        if (status == 429) return new Failure(Error.RATE_LIMIT, retryMillis(retryAfter, now));
        return new Failure(Error.SERVICE);
    }
    public static long retryMillis(String value, long now) {
        long millis = 30000;
        if (value != null) try { millis = Math.multiplyExact(Long.parseLong(value.trim()), 1000); }
        catch (Exception ignored) { try { millis = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now; } catch (Exception ignoredAgain) { } }
        return Math.max(1000, Math.min(24 * 60 * 60 * 1000L, millis));
    }
    public static List<PlaceResult> parse(String payload, long fetchedAt) throws Failure {
        try {
            JSONObject root = new JSONObject(payload);
            if (!"FeatureCollection".equals(root.optString("type"))) throw new Failure(Error.MALFORMED);
            JSONArray features = root.getJSONArray("features"); List<PlaceResult> results = new ArrayList<>();
            String attribution = root.optString("attribution", ATTRIBUTION);
            if (attribution.trim().isEmpty()) attribution = ATTRIBUTION;
            for (int i = 0; i < features.length() && results.size() < 10; i++) {
                try {
                    JSONObject feature = features.getJSONObject(i), geometry = feature.getJSONObject("geometry");
                    if (!"Point".equals(geometry.optString("type"))) continue;
                    JSONArray coordinate = geometry.getJSONArray("coordinates");
                    double lon = coordinate.getDouble(0), lat = coordinate.getDouble(1);
                    JSONArray types = feature.optJSONArray("place_type"); String type = types == null ? "" : types.optString(0, "");
                    String name = feature.optString("text", "").trim();
                    String context = feature.optString("place_name", "").trim();
                    String house = feature.optString("address", "").trim();
                    if ("address".equals(type)) {
                        // Keep the provider's local street/house order when it supplies a formatted address.
                        if (!context.isEmpty()) name = context.split(",", 2)[0].trim();
                        if (!house.isEmpty() && !java.util.regex.Pattern.compile("(?i)(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(house) + "(?![\\p{L}\\p{N}])").matcher(name).find()) name = (name + " " + house).trim();
                        // MapTiler also labels residential street names as address results.
                        if (house.isEmpty()) type = "road";
                    }
                    if (name.isEmpty()) name = context;
                    if (context.startsWith(name + ", ")) context = context.substring(name.length() + 2); else if (context.equals(name)) context = "";
                    JSONObject properties = feature.optJSONObject("properties");
                    if (properties != null) { String kind = properties.optString("kind", ""); if (!kind.isEmpty()) type += " · " + kind; }
                    double[] bounds = null; JSONArray box = feature.optJSONArray("bbox");
                    if (box != null && box.length() == 4) bounds = new double[]{box.optDouble(0), box.optDouble(1), box.optDouble(2), box.optDouble(3)};
                    results.add(new PlaceResult("maptiler", feature.getString("id"), name, context, type, lat, lon, bounds, attribution, false, fetchedAt));
                } catch (Exception invalidFeature) { /* A broken feature cannot become a marker at 0,0. */ }
            }
            if (features.length() > 0 && results.isEmpty()) throw new Failure(Error.MALFORMED);
            return Collections.unmodifiableList(results);
        } catch (Failure failure) { throw failure; } catch (Exception error) { throw new Failure(Error.MALFORMED); }
    }
}
