package com.fiskentra.app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable reverse view of a recorded track. Missing geometry never becomes an edge. */
public final class BacktrackRoute {
    public static final class Point {
        public final double latitude, longitude;
        public Point(double latitude, double longitude) {
            if (!FieldNavigation.validCoordinate(latitude, longitude)) throw new IllegalArgumentException("Invalid track coordinate");
            this.latitude = latitude; this.longitude = longitude;
        }
    }
    public static final class Segment {
        public final String id;
        public final List<Point> points;
        private final double[] cumulative;
        public Segment(String id, List<Point> points) {
            if (id == null || points == null || points.size() < 2) throw new IllegalArgumentException("Track segment needs two coordinates");
            this.id = id; this.points = Collections.unmodifiableList(new ArrayList<>(points));
            cumulative = new double[points.size()];
            for (int i = 1; i < points.size(); i++) cumulative[i] = cumulative[i - 1] + distance(points.get(i - 1), points.get(i));
            if (length() < .1) throw new IllegalArgumentException("Track segment has no distance");
        }
        public double length() { return cumulative[cumulative.length - 1]; }
        public double along(int index) { return cumulative[index]; }
    }
    public final String id;
    public final long revision, capturedAtUtc;
    public final List<Segment> segments;
    public BacktrackRoute(String id, long revision, long capturedAtUtc, List<Segment> segments) {
        if (id == null || id.isEmpty() || segments == null || segments.isEmpty()) throw new IllegalArgumentException("No usable recorded segment");
        this.id = id; this.revision = revision; this.capturedAtUtc = capturedAtUtc;
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
    }
    public static BacktrackRoute fromTrack(String id, long revision, long capturedAtUtc, List<double[]> original) {
        List<List<Point>> forward = new ArrayList<>(); List<Point> current = new ArrayList<>();
        double[] previous = null;
        for (double[] value : original) {
            if (value == null || value.length < 2 || !FieldNavigation.validCoordinate(value[0], value[1])) {
                if (!current.isEmpty()) forward.add(current); current = new ArrayList<>(); previous = null; continue;
            }
            if (previous != null && FieldNavigation.segmentBreak(previous, value)) {
                if (!current.isEmpty()) forward.add(current); current = new ArrayList<>();
            }
            Point point = new Point(value[0], value[1]);
            if (current.isEmpty() || distance(current.get(current.size() - 1), point) > .05) current.add(point);
            previous = value;
        }
        if (!current.isEmpty()) forward.add(current);
        List<Segment> reverse = new ArrayList<>();
        for (int i = forward.size() - 1; i >= 0; i--) {
            List<Point> part = forward.get(i); if (part.size() < 2) continue;
            Collections.reverse(part); reverse.add(new Segment(id + ":" + i, part));
        }
        return new BacktrackRoute(id, revision, capturedAtUtc, reverse);
    }
    public boolean hasGaps() { return segments.size() > 1; }
    public double knownRemaining(int segment, double along) {
        if (segment < 0 || segment >= segments.size()) return knownLength();
        double result = Math.max(0, segments.get(segment).length() - along);
        for (int i = segment + 1; i < segments.size(); i++) result += segments.get(i).length();
        return result;
    }
    public double knownLength() { double result = 0; for (Segment part : segments) result += part.length(); return result; }
    /** Defensive renderer/export copy, with the break flag on the first point of each new segment. */
    public List<double[]> geometry() {
        List<double[]> out = new ArrayList<>();
        for (Segment segment : segments) for (int i = 0; i < segment.points.size(); i++) {
            Point p = segment.points.get(i); out.add(new double[]{p.latitude, p.longitude, capturedAtUtc, i == 0 ? 1 : 0});
        }
        return out;
    }
    public Point start() { Segment last = segments.get(segments.size() - 1); return last.points.get(last.points.size() - 1); }
    static double distance(Point a, Point b) { return FieldNavigation.distance(a.latitude, a.longitude, b.latitude, b.longitude); }
}
