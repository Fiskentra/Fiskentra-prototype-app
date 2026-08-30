package com.fiskentra.app.model;

import org.json.JSONObject;

/** One local calendar day returned by the weather forecast provider. */
public final class ForecastDay {
    public final String date;
    public final int weatherCode;
    public final double minTemperatureC;
    public final double maxTemperatureC;
    public final int precipitationProbabilityPercent;
    public final double precipitationMm;
    public final double maxWindSpeedKmh;
    public final int dominantWindDirectionDegrees;
    public final String sunrise;
    public final String sunset;

    public ForecastDay(String date, int weatherCode, double minTemperatureC,
            double maxTemperatureC, int precipitationProbabilityPercent,
            double precipitationMm, double maxWindSpeedKmh,
            int dominantWindDirectionDegrees, String sunrise, String sunset) {
        this.date = date == null ? "" : date;
        this.weatherCode = weatherCode;
        this.minTemperatureC = minTemperatureC;
        this.maxTemperatureC = maxTemperatureC;
        this.precipitationProbabilityPercent = precipitationProbabilityPercent;
        this.precipitationMm = precipitationMm;
        this.maxWindSpeedKmh = maxWindSpeedKmh;
        this.dominantWindDirectionDegrees = dominantWindDirectionDegrees;
        this.sunrise = sunrise == null ? "" : sunrise;
        this.sunset = sunset == null ? "" : sunset;
    }

    public double meanTemperatureC() {
        return (minTemperatureC + maxTemperatureC) / 2d;
    }

    public String condition() {
        return WeatherSnapshot.conditionForCode(weatherCode);
    }

    public String symbol() {
        return WeatherSnapshot.symbolForCode(weatherCode);
    }

    public JSONObject toJson() throws Exception {
        JSONObject value = new JSONObject();
        value.put("date", date);
        value.put("weather_code", weatherCode);
        value.put("min_temperature_c", minTemperatureC);
        value.put("max_temperature_c", maxTemperatureC);
        value.put("precipitation_probability_percent", precipitationProbabilityPercent);
        value.put("precipitation_mm", precipitationMm);
        value.put("max_wind_speed_kmh", maxWindSpeedKmh);
        value.put("dominant_wind_direction_degrees", dominantWindDirectionDegrees);
        value.put("sunrise", sunrise);
        value.put("sunset", sunset);
        return value;
    }

    public static ForecastDay fromJson(JSONObject value) {
        if (value == null) return null;
        return new ForecastDay(
                value.optString("date", ""),
                value.optInt("weather_code", -1),
                value.optDouble("min_temperature_c", 0d),
                value.optDouble("max_temperature_c", 0d),
                value.optInt("precipitation_probability_percent", 0),
                value.optDouble("precipitation_mm", 0d),
                value.optDouble("max_wind_speed_kmh", 0d),
                value.optInt("dominant_wind_direction_degrees", 0),
                value.optString("sunrise", ""),
                value.optString("sunset", ""));
    }
}
