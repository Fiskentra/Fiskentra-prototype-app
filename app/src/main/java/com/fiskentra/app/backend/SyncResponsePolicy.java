package com.fiskentra.app.backend;

import java.util.Locale;

/** Pure HTTP/result policy shared with the actual REST transport. */
public final class SyncResponsePolicy {
    private SyncResponsePolicy() { }
    public static boolean uploaded(int status) { return status >= 200 && status < 300; }
    public static boolean deleted(int status, int representedRows) { return uploaded(status) && representedRows > 0; }
    public static String errorCategory(String message) {
        String value = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (value.contains("401") || value.contains("403") || value.contains("rls")
                || value.contains("access not confirmed") || value.contains("42501")) return "auth";
        if (value.contains("http 400") || value.contains("http 404") || value.contains("http 409")
                || value.contains("not configured") || value.contains("location required")) return "permanent";
        return "transient";
    }
}
