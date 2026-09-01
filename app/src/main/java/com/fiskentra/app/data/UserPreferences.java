package com.fiskentra.app.data;

import android.content.Context;
import android.content.SharedPreferences;

/** Device-local display and interaction preferences. */
public final class UserPreferences {
    public static final String UNIT_METRIC = "metric";
    public static final String UNIT_IMPERIAL = "imperial";

    private static final String PREFS = "fiskentra_user_preferences";
    private static final String KEY_UNIT_SYSTEM = "unit_system";
    private static final String KEY_DEFAULT_WEATHER_PAGE = "default_weather_page";
    private static final String KEY_CONFIRM_DELETE = "confirm_delete";

    private final SharedPreferences prefs;

    public UserPreferences(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean usesImperialUnits() {
        return UNIT_IMPERIAL.equals(prefs.getString(KEY_UNIT_SYSTEM, UNIT_METRIC));
    }

    public void setUsesImperialUnits(boolean imperial) {
        prefs.edit().putString(KEY_UNIT_SYSTEM, imperial ? UNIT_IMPERIAL : UNIT_METRIC).apply();
    }

    public boolean defaultWeatherPage() {
        return prefs.getBoolean(KEY_DEFAULT_WEATHER_PAGE, false);
    }

    public void setDefaultWeatherPage(boolean weather) {
        prefs.edit().putBoolean(KEY_DEFAULT_WEATHER_PAGE, weather).apply();
    }

    public boolean confirmDelete() {
        return prefs.getBoolean(KEY_CONFIRM_DELETE, true);
    }

    public void setConfirmDelete(boolean confirm) {
        prefs.edit().putBoolean(KEY_CONFIRM_DELETE, confirm).apply();
    }
}
