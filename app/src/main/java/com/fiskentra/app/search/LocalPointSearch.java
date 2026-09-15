package com.fiskentra.app.search;

import java.util.Locale;

/** No location, provider, connectivity or permission dependency. */
public final class LocalPointSearch {
    private LocalPointSearch() { }
    public static boolean matches(String query, String title, String type, String note) {
        String needle = value(query).trim().toLowerCase(Locale.ROOT);
        return (value(title) + " " + value(type) + " " + value(note)).toLowerCase(Locale.ROOT).contains(needle);
    }
    private static String value(String value) { return value == null ? "" : value; }
}
