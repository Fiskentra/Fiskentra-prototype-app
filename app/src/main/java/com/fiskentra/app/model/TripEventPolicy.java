package com.fiskentra.app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Resolve the displayed recording's sessions, then use durable point ownership. */
public final class TripEventPolicy {
    private TripEventPolicy() { }

    public static List<SavedPoint> forTrack(List<SavedPoint> points, List<FishingDay> sessions,
            long start, long end) {
        if (start <= 0 || end <= start) return Collections.emptyList();
        Set<Long> ids = new HashSet<>();
        // Flic may start recording before a FishingDay exists.
        ids.add(start);
        for (FishingDay session : sessions) {
            long sessionEnd = session.isActive() ? Long.MAX_VALUE : session.endedAt;
            if (session.startedAt < end && sessionEnd > start) ids.add(session.id);
        }
        ArrayList<SavedPoint> result = new ArrayList<>();
        for (SavedPoint point : points) if (point.tripId > 0 && ids.contains(point.tripId)) result.add(point);
        return Collections.unmodifiableList(result);
    }
}
