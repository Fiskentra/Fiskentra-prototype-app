package com.fiskentra.app.weather;

import com.fiskentra.app.model.ForecastDay;

/** Transparent weather-only fishing estimate. It deliberately makes no catch guarantee. */
public final class FishingAdvisor {
    public static final String[] SPECIES = {"Pike", "Perch", "Zander", "Trout", "Carp"};

    public static final class Assessment {
        public final int score;
        public final String label;
        public final String reason;

        Assessment(int score, String label, String reason) {
            this.score = score;
            this.label = label;
            this.reason = reason;
        }
    }

    private FishingAdvisor() { }

    public static Assessment assess(ForecastDay day, String species) {
        double idealLow = 8d;
        double idealHigh = 20d;
        int score = 70;
        boolean lowLightBonus = false;

        if ("Pike".equals(species)) {
            idealLow = 5d; idealHigh = 16d; lowLightBonus = true;
        } else if ("Perch".equals(species)) {
            idealLow = 12d; idealHigh = 24d;
        } else if ("Zander".equals(species)) {
            idealLow = 8d; idealHigh = 20d; lowLightBonus = true;
        } else if ("Trout".equals(species)) {
            idealLow = 4d; idealHigh = 16d;
        } else if ("Carp".equals(species)) {
            idealLow = 16d; idealHigh = 30d;
        }

        double temperature = day.meanTemperatureC();
        double distance = temperature < idealLow ? idealLow - temperature
                : temperature > idealHigh ? temperature - idealHigh : 0d;
        score -= Math.min(35, Math.round(distance * 4f));

        if (day.maxWindSpeedKmh >= 8d && day.maxWindSpeedKmh <= 25d) score += 8;
        if (day.maxWindSpeedKmh > 35d) score -= 22;
        else if (day.maxWindSpeedKmh > 28d) score -= 10;

        boolean cloudyOrWet = day.weatherCode >= 2 && day.weatherCode != 45 && day.weatherCode != 48;
        if (lowLightBonus && cloudyOrWet) score += 9;
        if (day.precipitationProbabilityPercent >= 35 && day.precipitationProbabilityPercent <= 75) score += 4;
        if (day.precipitationMm > 12d || day.weatherCode >= 95) score -= 24;
        if ("Trout".equals(species) && temperature > 20d) score -= 18;
        if ("Carp".equals(species) && temperature < 10d) score -= 15;

        score = Math.max(10, Math.min(95, score));
        String label = score >= 80 ? "Excellent" : score >= 65 ? "Good"
                : score >= 45 ? "Fair" : "Poor";
        String reason;
        if (day.maxWindSpeedKmh > 35d || day.weatherCode >= 95) {
            reason = "Strong wind or storms reduce comfort and safety.";
        } else if (distance <= 1d && lowLightBonus && cloudyOrWet) {
            reason = "Suitable air temperature with useful low-light conditions.";
        } else if (distance <= 1d) {
            reason = "Air temperature and wind are near this profile's preferred range.";
        } else if (temperature < idealLow) {
            reason = "Air temperature is colder than this profile's preferred range.";
        } else {
            reason = "Air temperature is warmer than this profile's preferred range.";
        }
        return new Assessment(score, label, reason);
    }
}
