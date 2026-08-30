package com.fiskentra.app.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.fiskentra.app.model.WeatherSnapshot;
import com.fiskentra.app.model.ForecastDay;
import com.fiskentra.app.model.WeatherForecast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Retrieves current conditions from Open-Meteo and keeps a short local cache. */
public final class WeatherClient {
    public interface Listener {
        void onResult(WeatherSnapshot weather, String message);
    }

    public interface ForecastListener {
        void onResult(WeatherForecast forecast, String message);
    }

    private static final String PREFS = "fiskentra_weather_cache";
    private static final String KEY_WEATHER = "weather";
    private static final String KEY_LATITUDE = "latitude";
    private static final String KEY_LONGITUDE = "longitude";
    private static final String KEY_FETCHED_AT = "fetched_at";
    private static final String KEY_FORECAST = "forecast";
    private static final long CACHE_MAX_AGE_MS = 15L * 60L * 1_000L;
    private static final float CACHE_MAX_DISTANCE_METERS = 5_000f;
    private static final long FORECAST_CACHE_MAX_AGE_MS = 30L * 60L * 1_000L;
    private static final float FORECAST_CACHE_MAX_DISTANCE_METERS = 10_000f;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ConnectivityManager connectivityManager;
    private final SharedPreferences prefs;

    public WeatherClient(Context context) {
        Context application = context.getApplicationContext();
        connectivityManager = (ConnectivityManager) application
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void fetch(double latitude, double longitude, Listener listener) {
        WeatherSnapshot cached = cached(latitude, longitude);
        if (cached != null) {
            listener.onResult(cached, "Weather loaded from recent cache");
            return;
        }
        if (!hasValidatedInternet()) {
            listener.onResult(null, "Weather unavailable offline");
            return;
        }

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                // Another queued request may have populated the shared cache while this one waited.
                WeatherSnapshot queuedCache = cached(latitude, longitude);
                if (queuedCache != null) {
                    listener.onResult(queuedCache, "Weather loaded from recent cache");
                    return;
                }
                String endpoint = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast"
                                + "?latitude=%.6f&longitude=%.6f"
                                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,"
                                + "precipitation,weather_code,pressure_msl,wind_speed_10m,wind_direction_10m"
                                + "&temperature_unit=celsius&wind_speed_unit=kmh"
                                + "&precipitation_unit=mm&timeformat=unixtime&timezone=auto&forecast_days=1",
                        latitude, longitude);
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5_000);
                connection.setReadTimeout(5_000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "Fiskentra-Android/0.8");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    listener.onResult(null, "Open-Meteo HTTP " + status);
                    return;
                }
                JSONObject root = new JSONObject(read(connection.getInputStream()));
                JSONObject current = root.getJSONObject("current");
                long observedAt = current.optLong("time", System.currentTimeMillis() / 1_000L) * 1_000L;
                WeatherSnapshot weather = new WeatherSnapshot(
                        observedAt,
                        current.getDouble("temperature_2m"),
                        current.getDouble("apparent_temperature"),
                        current.getInt("relative_humidity_2m"),
                        current.getDouble("precipitation"),
                        current.getDouble("pressure_msl"),
                        current.getDouble("wind_speed_10m"),
                        current.getInt("wind_direction_10m"),
                        current.getInt("weather_code"),
                        root.optString("timezone", ""),
                        WeatherSnapshot.PROVIDER_OPEN_METEO);
                cache(latitude, longitude, weather);
                listener.onResult(weather, "Weather added from Open-Meteo");
            } catch (Exception error) {
                listener.onResult(null, "Weather service unavailable");
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    public void fetchForecast(double latitude, double longitude, boolean forceRefresh,
            ForecastListener listener) {
        WeatherForecast cached = forceRefresh ? null : cachedForecast(latitude, longitude);
        if (cached != null) {
            listener.onResult(cached, "Forecast loaded from recent cache");
            return;
        }
        if (!hasValidatedInternet()) {
            WeatherForecast stale = cachedForecast(latitude, longitude, Long.MAX_VALUE);
            listener.onResult(stale, stale == null
                    ? "Forecast unavailable offline" : "Offline · showing saved forecast");
            return;
        }

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                if (!forceRefresh) {
                    WeatherForecast queued = cachedForecast(latitude, longitude);
                    if (queued != null) {
                        listener.onResult(queued, "Forecast loaded from recent cache");
                        return;
                    }
                }
                String endpoint = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast"
                                + "?latitude=%.6f&longitude=%.6f"
                                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,"
                                + "precipitation,weather_code,pressure_msl,wind_speed_10m,wind_direction_10m"
                                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                                + "precipitation_probability_max,precipitation_sum,wind_speed_10m_max,"
                                + "wind_direction_10m_dominant,sunrise,sunset"
                                + "&temperature_unit=celsius&wind_speed_unit=kmh&precipitation_unit=mm"
                                + "&timezone=auto&forecast_days=7",
                        latitude, longitude);
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(7_000);
                connection.setReadTimeout(7_000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "Fiskentra-Android/0.8");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    listener.onResult(null, "Open-Meteo HTTP " + status);
                    return;
                }
                JSONObject root = new JSONObject(read(connection.getInputStream()));
                String timezone = root.optString("timezone", "");
                JSONObject current = root.getJSONObject("current");
                WeatherSnapshot currentWeather = new WeatherSnapshot(
                        System.currentTimeMillis(),
                        current.getDouble("temperature_2m"),
                        current.getDouble("apparent_temperature"),
                        current.getInt("relative_humidity_2m"),
                        current.getDouble("precipitation"),
                        current.getDouble("pressure_msl"),
                        current.getDouble("wind_speed_10m"),
                        current.getInt("wind_direction_10m"),
                        current.getInt("weather_code"),
                        timezone,
                        WeatherSnapshot.PROVIDER_OPEN_METEO);

                JSONObject daily = root.getJSONObject("daily");
                JSONArray dates = daily.getJSONArray("time");
                JSONArray codes = daily.getJSONArray("weather_code");
                JSONArray minimums = daily.getJSONArray("temperature_2m_min");
                JSONArray maximums = daily.getJSONArray("temperature_2m_max");
                JSONArray rainChances = daily.getJSONArray("precipitation_probability_max");
                JSONArray rainAmounts = daily.getJSONArray("precipitation_sum");
                JSONArray winds = daily.getJSONArray("wind_speed_10m_max");
                JSONArray windDirections = daily.getJSONArray("wind_direction_10m_dominant");
                JSONArray sunrises = daily.getJSONArray("sunrise");
                JSONArray sunsets = daily.getJSONArray("sunset");
                ArrayList<ForecastDay> days = new ArrayList<>();
                for (int i = 0; i < dates.length(); i++) {
                    days.add(new ForecastDay(
                            dates.getString(i), codes.optInt(i, -1), minimums.optDouble(i, 0d),
                            maximums.optDouble(i, 0d), rainChances.optInt(i, 0),
                            rainAmounts.optDouble(i, 0d), winds.optDouble(i, 0d),
                            windDirections.optInt(i, 0), sunrises.optString(i, ""),
                            sunsets.optString(i, "")));
                }
                WeatherForecast forecast = new WeatherForecast(
                        System.currentTimeMillis(), latitude, longitude, timezone, currentWeather, days);
                cacheForecast(forecast);
                cache(latitude, longitude, currentWeather);
                listener.onResult(forecast, "7-day forecast updated");
            } catch (Exception error) {
                WeatherForecast stale = cachedForecast(latitude, longitude, Long.MAX_VALUE);
                listener.onResult(stale, stale == null
                        ? "Weather service unavailable" : "Update failed · showing saved forecast");
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    public void close() {
        executor.shutdownNow();
    }

    private WeatherSnapshot cached(double latitude, double longitude) {
        long fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L);
        if (fetchedAt <= 0L || System.currentTimeMillis() - fetchedAt > CACHE_MAX_AGE_MS) return null;
        float[] distance = new float[1];
        android.location.Location.distanceBetween(
                latitude, longitude,
                Double.longBitsToDouble(prefs.getLong(KEY_LATITUDE, Double.doubleToLongBits(0d))),
                Double.longBitsToDouble(prefs.getLong(KEY_LONGITUDE, Double.doubleToLongBits(0d))),
                distance);
        if (distance[0] > CACHE_MAX_DISTANCE_METERS) return null;
        try {
            return WeatherSnapshot.fromJson(new JSONObject(prefs.getString(KEY_WEATHER, "")));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void cache(double latitude, double longitude, WeatherSnapshot weather) {
        try {
            prefs.edit()
                    .putString(KEY_WEATHER, weather.toJson().toString())
                    .putLong(KEY_LATITUDE, Double.doubleToRawLongBits(latitude))
                    .putLong(KEY_LONGITUDE, Double.doubleToRawLongBits(longitude))
                    .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) { }
    }

    private WeatherForecast cachedForecast(double latitude, double longitude) {
        return cachedForecast(latitude, longitude, FORECAST_CACHE_MAX_AGE_MS);
    }

    private WeatherForecast cachedForecast(double latitude, double longitude, long maxAgeMs) {
        try {
            WeatherForecast forecast = WeatherForecast.fromJson(
                    new JSONObject(prefs.getString(KEY_FORECAST, "")));
            if (forecast == null || System.currentTimeMillis() - forecast.fetchedAt > maxAgeMs) return null;
            float[] distance = new float[1];
            android.location.Location.distanceBetween(latitude, longitude,
                    forecast.latitude, forecast.longitude, distance);
            return distance[0] <= FORECAST_CACHE_MAX_DISTANCE_METERS ? forecast : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void cacheForecast(WeatherForecast forecast) {
        try {
            prefs.edit().putString(KEY_FORECAST, forecast.toJson().toString()).apply();
        } catch (Exception ignored) { }
    }

    private boolean hasValidatedInternet() {
        if (connectivityManager == null) return false;
        Network network = connectivityManager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private static String read(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2_048];
            int count;
            while ((count = source.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
