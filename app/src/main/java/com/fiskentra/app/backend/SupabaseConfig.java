package com.fiskentra.app.backend;

import com.fiskentra.app.BuildConfig;

/**
 * Public client configuration for Fiskentra's Supabase backend.
 *
 * The publishable key is intentionally supplied through local.properties so a
 * developer can rotate it without editing source. Never put a service-role or
 * sb_secret key in an Android application.
 */
public final class SupabaseConfig {
    public static final String PROJECT_REF = "dwlbefpmwzmhutlvqfmu";

    private SupabaseConfig() {}

    public static String url() {
        return BuildConfig.SUPABASE_URL;
    }

    public static String publishableKey() {
        return BuildConfig.SUPABASE_PUBLISHABLE_KEY;
    }

    public static boolean isConfigured() {
        return !url().trim().isEmpty() && !publishableKey().trim().isEmpty();
    }
}
