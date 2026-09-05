package com.fiskentra.app.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Validation shared by the native editor and persistence boundary. */
public final class PointMetadata {
    public static final int MAX_TITLE_LENGTH = 80;
    public static final int MAX_NOTE_LENGTH = 500;
    public static final List<String> TYPES = Collections.unmodifiableList(Arrays.asList(
            "Catch", "Waypoint", "Tackle change", "Camp", "Hazard"));

    private PointMetadata() { }

    public static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public static boolean isAllowedType(String value) {
        return TYPES.contains(value);
    }

    public static String validate(String title, String type, String note) {
        if (!isAllowedType(type)) return "Choose a supported point type.";
        if (clean(title).length() > MAX_TITLE_LENGTH) {
            return "Name can contain up to " + MAX_TITLE_LENGTH + " characters.";
        }
        if (clean(note).length() > MAX_NOTE_LENGTH) {
            return "Note can contain up to " + MAX_NOTE_LENGTH + " characters.";
        }
        return "";
    }
}
