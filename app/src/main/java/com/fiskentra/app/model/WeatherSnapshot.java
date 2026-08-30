package com.fiskentra.app.model;

import org.json.JSONObject;

import java.util.Locale;

/** Immutable weather conditions captured for one place and time. */
public final class WeatherSnapshot {
    public static final String PROVIDER_OPEN_METEO = "Open-Meteo";

    public final long observedAt;
    public final double temperatureC;
    public final double apparentTemperatureC;
    public final int humidityPercent;
    public final double precipitationMm;
    public final double pressureHpa;
    public final double windSpeedKmh;
    public final int windDirectionDegrees;
    public final int weatherCode;
    public final String timezone;
    public final String provider;

    public WeatherSnapshot(
            long observedAt,
            double temperatureC,
            double apparentTemperatureC,
            int humidityPercent,
            double precipitationMm,
            double pressureHpa,
            double windSpeedKmh,
            int windDirectionDegrees,
            int weatherCode,
            String timezone,
            String provider) {
        this.observedAt = observedAt;
        this.temperatureC = temperatureC;
        this.apparentTemperatureC = apparentTemperatureC;
        this.humidityPercent = humidityPercent;
        this.precipitationMm = precipitationMm;
        this.pressureHpa = pressureHpa;
        this.windSpeedKmh = windSpeedKmh;
        this.windDirectionDegrees = windDirectionDegrees;
        this.weatherCode = weatherCode;
        this.timezone = timezone == null ? "" : timezone;
        this.provider = provider == null || provider.trim().isEmpty()
                ? PROVIDER_OPEN_METEO : provider;
    }

    public JSONObject toJson() throws Exception {
        JSONObject value = new JSONObject();
        value.put("observed_at", observedAt);
        value.put("temperature_c", temperatureC);
        value.put("apparent_temperature_c", apparentTemperatureC);
        value.put("humidity_percent", humidityPercent);
        value.put("precipitation_mm", precipitationMm);
        value.put("pressure_hpa", pressureHpa);
        value.put("wind_speed_kmh", windSpeedKmh);
        value.put("wind_direction_degrees", windDirectionDegrees);
        value.put("weather_code", weatherCode);
        value.put("timezone", timezone);
        value.put("provider", provider);
        return value;
    }

    public static WeatherSnapshot fromJson(JSONObject value) {
        if (value == null) return null;
        try {
            return new WeatherSnapshot(
                    value.getLong("observed_at"),
                    value.getDouble("temperature_c"),
                    value.optDouble("apparent_temperature_c", value.getDouble("temperature_c")),
                    value.optInt("humidity_percent", 0),
                    value.optDouble("precipitation_mm", 0d),
                    value.optDouble("pressure_hpa", 0d),
                    value.optDouble("wind_speed_kmh", 0d),
                    value.optInt("wind_direction_degrees", 0),
                    value.optInt("weather_code", -1),
                    value.optString("timezone", ""),
                    value.optString("provider", PROVIDER_OPEN_METEO));
        } catch (Exception ignored) {
            return null;
        }
    }

    public String condition() {
        return conditionForCode(weatherCode);
    }

    public String symbol() {
        return symbolForCode(weatherCode);
    }

    public static String conditionForCode(int weatherCode) {
        switch (weatherCode) {
            case 0: return "Clear sky";
            case 1: return "Mainly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45:
            case 48: return "Fog";
            case 51:
            case 53:
            case 55: return "Drizzle";
            case 56:
            case 57: return "Freezing drizzle";
            case 61:
            case 63:
            case 65: return "Rain";
            case 66:
            case 67: return "Freezing rain";
            case 71:
            case 73:
            case 75:
            case 77: return "Snow";
            case 80:
            case 81:
            case 82: return "Rain showers";
            case 85:
            case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96:
            case 99: return "Thunderstorm with hail";
            default: return "Weather recorded";
        }
    }

    public static String symbolForCode(int weatherCode) {
        if (weatherCode == 0) return "☀";
        if (weatherCode == 1 || weatherCode == 2) return "⛅";
        if (weatherCode == 3) return "☁";
        if (weatherCode == 45 || weatherCode == 48) return "≋";
        if ((weatherCode >= 51 && weatherCode <= 67)
                || (weatherCode >= 80 && weatherCode <= 82)) return "☂";
        if ((weatherCode >= 71 && weatherCode <= 77)
                || weatherCode == 85 || weatherCode == 86) return "❄";
        if (weatherCode >= 95) return "ϟ";
        return "○";
    }

    public String windDirection() {
        String[] directions = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int normalized = ((windDirectionDegrees % 360) + 360) % 360;
        return directions[(int) Math.round(normalized / 45d) % directions.length];
    }

    public String compactSummary() {
        return String.format(Locale.getDefault(), "%s · %.0f°C · %s %.0f km/h",
                condition(), temperatureC, windDirection(), windSpeedKmh);
    }
}
