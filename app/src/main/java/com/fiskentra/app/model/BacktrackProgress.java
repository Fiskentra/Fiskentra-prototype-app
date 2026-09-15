package com.fiskentra.app.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Foreground, network-free guidance. Progress only searches a bounded section of the chosen segment. */
public final class BacktrackProgress {
    public enum Status { CHOOSE_SEGMENT, APPROACH, TRACKING, GPS_PAUSED, OFF_ROUTE, TRACK_GAP, ARRIVED, STOPPED }
    public static final class Fix {
        public final double latitude, longitude, accuracy;
        public final long monotonicMillis;
        public final boolean fresh;
        public Fix(double lat, double lon, double accuracy, long monotonicMillis, boolean fresh) {
            latitude = lat; longitude = lon; this.accuracy = accuracy; this.monotonicMillis = monotonicMillis; this.fresh = fresh;
        }
        public boolean usable() { return fresh && monotonicMillis >= 0 && FieldNavigation.validCoordinate(latitude, longitude) && Double.isFinite(accuracy) && accuracy >= 0 && accuracy <= 50; }
    }
    public static final class Candidate {
        public final int segmentIndex, edgeIndex;
        public final double fraction, alongMeters, distanceMeters;
        public final BacktrackRoute.Point point;
        Candidate(int segment, int edge, double fraction, double along, double distance, BacktrackRoute.Point point) {
            segmentIndex = segment; edgeIndex = edge; this.fraction = fraction; alongMeters = along; distanceMeters = distance; this.point = point;
        }
    }
    public static final class Snapshot {
        public final Status status;
        public final int segmentIndex, edgeIndex;
        public final double fraction, alongMeters, knownRemainingMeters, distanceToTrackMeters, bearing;
        public final BacktrackRoute.Point nextPoint;
        public final boolean remainingHasGap;
        Snapshot(Status status, int segment, int edge, double fraction, double along, double remaining, double off, double bearing, BacktrackRoute.Point next, boolean gaps) {
            this.status = status; segmentIndex = segment; edgeIndex = edge; this.fraction = fraction; alongMeters = along;
            knownRemainingMeters = remaining; distanceToTrackMeters = off; this.bearing = bearing; nextPoint = next; remainingHasGap = gaps;
        }
    }
    public final BacktrackRoute route;
    private Status status = Status.CHOOSE_SEGMENT;
    private int segment = -1, edge, offCount, recoveredCount, arrivalCount;
    private double fraction, along, distance = Double.NaN, bearing = Double.NaN;
    private long lastFix = -1;
    public BacktrackProgress(BacktrackRoute route) { this.route = route; }
    public static BacktrackProgress restore(BacktrackRoute route, int segment, int edge, double fraction, double along, Status status) {
        BacktrackProgress engine = new BacktrackProgress(route);
        if (segment < 0) return engine;
        if (segment >= route.segments.size() || edge < 0 || edge >= route.segments.get(segment).points.size() - 1 || !Double.isFinite(along) || !Double.isFinite(fraction) || fraction < 0 || fraction > 1 || along < 0 || along > route.segments.get(segment).length() + .1) throw new IllegalArgumentException("Invalid saved progress");
        BacktrackRoute.Segment part = route.segments.get(segment);
        double projected = part.along(edge) + fraction * (part.along(edge + 1) - part.along(edge));
        if (Math.abs(projected - along) > .25) throw new IllegalArgumentException("Inconsistent saved progress");
        engine.segment = segment; engine.edge = edge; engine.fraction = fraction; engine.along = along;
        engine.status = status == Status.ARRIVED || status == Status.STOPPED || status == Status.TRACK_GAP ? status : Status.GPS_PAUSED;
        return engine;
    }
    /** Candidates are grouped by route position, so a crossing offers explicit, distinct choices. */
    public List<Candidate> startCandidates(Fix fix) {
        List<Candidate> projected = new ArrayList<>();
        if (fix == null || !fix.usable()) return projected;
        for (int s = 0; s < route.segments.size(); s++) {
            BacktrackRoute.Segment part = route.segments.get(s);
            for (int e = 0; e < part.points.size() - 1; e++) projected.add(project(fix, s, e, 0, part.length()));
        }
        projected.sort(Comparator.comparingDouble(c -> c.distanceMeters));
        List<Candidate> out = new ArrayList<>();
        if (projected.isEmpty()) return out;
        double cutoff = projected.get(0).distanceMeters + Math.max(12, fix.accuracy);
        for (Candidate value : projected) {
            if (value.distanceMeters > cutoff) break;
            boolean duplicate = false;
            for (Candidate old : out) if (old.segmentIndex == value.segmentIndex && Math.abs(old.alongMeters - value.alongMeters) < 60) duplicate = true;
            if (!duplicate) out.add(value);
            if (out.size() == 12) break;
        }
        return out;
    }
    public Snapshot select(Candidate candidate, Fix fix) {
        if (candidate == null || fix == null || !fix.usable()) return snapshot();
        if (candidate.segmentIndex < 0 || candidate.segmentIndex >= route.segments.size()) throw new IllegalArgumentException("Unknown segment");
        BacktrackRoute.Segment part = route.segments.get(candidate.segmentIndex);
        if (candidate.edgeIndex < 0 || candidate.edgeIndex >= part.points.size() - 1) throw new IllegalArgumentException("Unknown edge");
        Candidate verified = project(fix, candidate.segmentIndex, candidate.edgeIndex, 0, part.length());
        segment = verified.segmentIndex; accept(verified); lastFix = fix.monotonicMillis;
        offCount = recoveredCount = arrivalCount = 0;
        status = distance > deviationThreshold(fix.accuracy) ? Status.APPROACH : Status.TRACKING;
        updateBearing(fix); return snapshot();
    }
    /** A gap requires a separate user action AND a fresh position near the next recorded segment. */
    public boolean continueAfterGap(Fix fix) {
        if (status != Status.TRACK_GAP || segment + 1 >= route.segments.size() || fix == null || !fix.usable()) return false;
        BacktrackRoute.Segment next = route.segments.get(segment + 1);
        Candidate best = null;
        // A gap resumes at the beginning, never by silently skipping a later portion.
        for (int e = 0; e < next.points.size() - 1 && next.along(e) <= 30; e++) {
            Candidate c = project(fix, segment + 1, e, 0, Math.min(30, next.length()));
            if (best == null || c.distanceMeters < best.distanceMeters) best = c;
        }
        if (best == null || best.distanceMeters > deviationThreshold(fix.accuracy)) return false;
        select(best, fix); return true;
    }
    public Snapshot update(Fix fix) {
        if (segment < 0 || status == Status.ARRIVED || status == Status.STOPPED || status == Status.TRACK_GAP) return snapshot();
        if (fix == null || !fix.usable()) { status = Status.GPS_PAUSED; offCount = recoveredCount = arrivalCount = 0; return snapshot(); }
        if (fix.monotonicMillis <= lastFix) return snapshot();
        double elapsed = lastFix < 0 ? 0 : Math.min(10, (fix.monotonicMillis - lastFix) / 1000d); lastFix = fix.monotonicMillis;
        BacktrackRoute.Segment part = route.segments.get(segment);
        double low = Math.max(0, along - 20), high = Math.min(part.length(), along + Math.max(35, Math.min(150, 12 * elapsed + fix.accuracy)));
        Candidate best = null; double score = Double.POSITIVE_INFINITY;
        for (int e = Math.max(0, edge - 8); e < part.points.size() - 1; e++) {
            if (part.along(e) > high) break;
            if (part.along(e + 1) < low) continue;
            Candidate c = project(fix, segment, e, low, high);
            double candidateScore = c.distanceMeters + Math.abs(c.alongMeters - along) * .04;
            if (candidateScore < score) { score = candidateScore; best = c; }
        }
        if (best == null) return snapshot();
        distance = best.distanceMeters;
        double threshold = deviationThreshold(fix.accuracy);
        if (distance > threshold) {
            offCount++; recoveredCount = 0; arrivalCount = 0;
            if (offCount >= 3) status = Status.OFF_ROUTE;
            else if (status != Status.OFF_ROUTE) status = Status.APPROACH;
        } else {
            offCount = 0; recoveredCount++;
            if (status != Status.OFF_ROUTE || (distance <= Math.max(20, fix.accuracy) && recoveredCount >= 2)) {
                if (best.alongMeters >= along) accept(best);
                status = Status.TRACKING;
                BacktrackRoute.Point end = part.points.get(part.points.size() - 1);
                double endDistance = FieldNavigation.distance(fix.latitude, fix.longitude, end.latitude, end.longitude);
                if (fix.accuracy <= 25 && part.length() - along <= 20 && endDistance <= 20) arrivalCount++; else arrivalCount = 0;
                if (arrivalCount >= 2) status = segment + 1 < route.segments.size() ? Status.TRACK_GAP : Status.ARRIVED;
            }
        }
        updateBearing(fix); return snapshot();
    }
    public Snapshot stop() { status = Status.STOPPED; return snapshot(); }
    public Snapshot snapshot() {
        BacktrackRoute.Point next = segment < 0 ? route.segments.get(0).points.get(0) : route.segments.get(segment).points.get(Math.min(edge + 1, route.segments.get(segment).points.size() - 1));
        boolean directional = status == Status.TRACKING || status == Status.APPROACH || status == Status.OFF_ROUTE;
        return new Snapshot(status, segment, edge, fraction, along, route.knownRemaining(segment, along), distance, directional ? bearing : Double.NaN, next, segment < route.segments.size() - 1);
    }
    public static double deviationThreshold(double accuracy) { return Math.max(30, 2 * accuracy); }
    private void accept(Candidate value) { edge = value.edgeIndex; fraction = value.fraction; along = value.alongMeters; distance = value.distanceMeters; }
    private void updateBearing(Fix fix) { BacktrackRoute.Point next = snapshot().nextPoint; bearing = FieldNavigation.bearing(fix.latitude, fix.longitude, next.latitude, next.longitude); }
    private Candidate project(Fix fix, int s, int e, double minAlong, double maxAlong) {
        BacktrackRoute.Segment part = route.segments.get(s); BacktrackRoute.Point a = part.points.get(e), b = part.points.get(e + 1);
        double scale = Math.cos(Math.toRadians(fix.latitude)), units = 111195;
        double ax = delta(a.longitude - fix.longitude) * scale * units, ay = (a.latitude - fix.latitude) * units;
        double bx = delta(b.longitude - fix.longitude) * scale * units, by = (b.latitude - fix.latitude) * units;
        double dx = bx - ax, dy = by - ay, squared = dx * dx + dy * dy, length = part.along(e + 1) - part.along(e);
        double low = Math.max(0, (minAlong - part.along(e)) / length), high = Math.min(1, (maxAlong - part.along(e)) / length);
        double t = squared == 0 ? 0 : -(ax * dx + ay * dy) / squared; t = Math.max(low, Math.min(high, t));
        double lon = delta(a.longitude + t * delta(b.longitude - a.longitude));
        return new Candidate(s, e, t, part.along(e) + t * length, Math.hypot(ax + t * dx, ay + t * dy), new BacktrackRoute.Point(a.latitude + t * (b.latitude - a.latitude), lon));
    }
    private static double delta(double value) { return ((value + 540) % 360 + 360) % 360 - 180; }
}
