package com.fiskentra.app;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

/** This development release uses English throughout the app, including SDK UI and dates. */
final class AppLanguage {
    private AppLanguage() { }

    static Context english(Context context) {
        Locale.setDefault(Locale.ENGLISH);
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(Locale.ENGLISH);
        configuration.setLayoutDirection(Locale.ENGLISH);
        // Preserve the user's font scale, density, timezone and other device settings.
        return context.createConfigurationContext(configuration);
    }
}
