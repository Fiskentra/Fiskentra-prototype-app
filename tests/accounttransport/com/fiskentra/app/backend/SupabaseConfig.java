package com.fiskentra.app.backend;

/** Fake endpoint only. The test never reads BuildConfig, local properties or real credentials. */
public final class SupabaseConfig {
    public static final String PROJECT_REF = "fiskentra-unit-test";
    public static boolean configured = true;
    public static String endpoint = "https://" + PROJECT_REF + ".supabase.co";
    private SupabaseConfig() { }
    public static boolean isConfigured() { return configured; }
    public static String url() { return endpoint; }
    public static String publishableKey() { return "fixture-publishable-value"; }
}
