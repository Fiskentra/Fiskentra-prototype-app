package com.fiskentra.app.export;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Builds a standards-based GPX document without Android or network dependencies. */
public final class GpxExporter {
    public static final class Waypoint {
        public final double latitude;
        public final double longitude;
        public final long timestamp;
        public final String name;
        public final String type;
        public final String description;

        public Waypoint(double latitude, double longitude, long timestamp,
                        String name, String type, String description) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.timestamp = timestamp;
            this.name = name;
            this.type = type;
            this.description = description;
        }
    }

    private GpxExporter() { }

    public static String create(String tripName, long startedAt, long endedAt,
                                List<double[]> track, List<Waypoint> waypoints) {
        String name = clean(tripName).isEmpty() ? "Fiskentra trip" : clean(tripName);
        List<double[]> validTrack = new ArrayList<>();
        if (track != null) {
            for (double[] point : track) {
                if (point != null && point.length >= 3
                        && validCoordinate(point[0], point[1])
                        && inRange((long) point[2], startedAt, endedAt)) {
                    validTrack.add(new double[]{point[0], point[1], point[2]});
                }
            }
        }
        validTrack.sort(Comparator.comparingDouble(point -> point[2]));

        List<Waypoint> validWaypoints = new ArrayList<>();
        if (waypoints != null) {
            for (Waypoint point : waypoints) {
                if (point != null && validCoordinate(point.latitude, point.longitude)
                        && inRange(point.timestamp, startedAt, endedAt)) {
                    validWaypoints.add(point);
                }
            }
        }
        validWaypoints.sort(Comparator.comparingLong(point -> point.timestamp));

        StringBuilder xml = new StringBuilder(2048);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"Fiskentra Android\" ")
                .append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
                .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
                .append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 ")
                .append("http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
                .append("  <metadata><name>").append(escape(name)).append("</name>");
        if (startedAt > 0L) xml.append("<time>").append(time(startedAt)).append("</time>");
        xml.append("</metadata>\n");

        for (Waypoint point : validWaypoints) {
            xml.append("  <wpt lat=\"").append(coordinate(point.latitude))
                    .append("\" lon=\"").append(coordinate(point.longitude)).append("\">\n")
                    .append("    <time>").append(time(point.timestamp)).append("</time>\n")
                    .append("    <name>").append(escape(label(point))).append("</name>\n")
                    .append("    <type>").append(escape(clean(point.type))).append("</type>\n");
            if (!clean(point.description).isEmpty()) {
                xml.append("    <desc>").append(escape(clean(point.description))).append("</desc>\n");
            }
            xml.append("  </wpt>\n");
        }

        if (!validTrack.isEmpty()) {
            xml.append("  <trk><name>").append(escape(name)).append("</name><trkseg>\n");
            for (double[] point : validTrack) {
                xml.append("    <trkpt lat=\"").append(coordinate(point[0]))
                        .append("\" lon=\"").append(coordinate(point[1])).append("\">")
                        .append("<time>").append(time((long) point[2])).append("</time></trkpt>\n");
            }
            xml.append("  </trkseg></trk>\n");
        }
        return xml.append("</gpx>\n").toString();
    }

    private static boolean validCoordinate(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90d && latitude <= 90d
                && longitude >= -180d && longitude <= 180d;
    }

    private static boolean inRange(long timestamp, long startedAt, long endedAt) {
        if (timestamp <= 0L) return false;
        if (startedAt > 0L && timestamp < startedAt) return false;
        return endedAt <= 0L || timestamp <= endedAt;
    }

    private static String label(Waypoint point) {
        String name = clean(point.name);
        if (!name.isEmpty()) return name;
        String type = clean(point.type);
        return type.isEmpty() ? "Saved point" : type;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String coordinate(double value) {
        return String.format(Locale.US, "%.7f", value);
    }

    private static String time(long timestamp) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timestamp));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
