package com.fiskentra.app.model;

import java.util.ArrayList;
import java.util.List;

/** Builds display-only history segments without joining recording pauses or invalid fixes. */
public final class PastTripGeometry {
    private PastTripGeometry() { }
    public static boolean hasLine(List<double[]> route) {
        if (route == null) return false;
        double[] previous = null;
        for (double[] point : route) {
            if (valid(point)) {
                if (previous != null && !FieldNavigation.segmentBreak(previous, point)) return true;
                previous = point;
            } else previous = null;
        }
        return false;
    }

    public static List<List<double[]>> parts(List<double[]> route, int targetPointCount) {
        List<List<double[]>> result = new ArrayList<>();
        if (route == null) return result;
        List<double[]> part = new ArrayList<>();
        double[] previous = null;
        int validCount = 0;
        for (double[] point : route) {
            boolean valid = valid(point);
            if (!valid || previous != null && FieldNavigation.segmentBreak(previous, point)) {
                if (part.size() >= 2) { result.add(part); validCount += part.size(); }
                part = new ArrayList<>();
            }
            if (valid) part.add(point);
            previous = valid ? point : null;
        }
        if (part.size() >= 2) { result.add(part); validCount += part.size(); }
        int stride = Math.max(1, (int) Math.ceil(validCount / (double) Math.max(2, targetPointCount)));
        if (stride > 1) for (int index = 0; index < result.size(); index++) {
            List<double[]> source = result.get(index), sampled = new ArrayList<>();
            for (int i = 0; i < source.size() - 1; i += stride) sampled.add(source.get(i));
            sampled.add(source.get(source.size() - 1));
            result.set(index, sampled);
        }
        return result;
    }

    private static boolean valid(double[] point) {
        return point != null && point.length >= 2 && FieldNavigation.validCoordinate(point[0], point[1]);
    }
}
