package com.fiskentra.app.model;

import java.time.*;
import java.util.*;

/** One immutable predicate for marker geometry, list, clusters and count. */
public final class MapFilterPolicy {
    public static final String[] TYPES={"Catch","Waypoint","Camp","Hazard","Tackle change","Other"};
    public final String scope,tripId;
    public final long fromUtc,toUtc;
    public final Set<String> types;
    public final boolean favorite;
    public MapFilterPolicy(String scope,String tripId,long from,long to,Collection<String> types,boolean favorite) {
        this.scope=scope;this.tripId=tripId==null?"":tripId;fromUtc=from;toUtc=to;
        this.types=Collections.unmodifiableSet(new HashSet<>(types));this.favorite=favorite;
    }
    public static MapFilterPolicy all() { return new MapFilterPolicy("all","",0,Long.MAX_VALUE,Arrays.asList(TYPES),false); }
    public boolean active() { return !"all".equals(scope)||types.size()!=TYPES.length||favorite; }
    public boolean matches(SavedPoint point,String currentTrip) {
        if(!types.contains(category(point.type))||favorite&&!point.favorite)return false;
        if("current".equals(scope))return currentTrip!=null&&!currentTrip.isEmpty()&&!"0".equals(currentTrip)&&currentTrip.equals(Long.toString(point.tripId));
        if("trip".equals(scope))return !tripId.isEmpty()&&tripId.equals(Long.toString(point.tripId));
        return !"dates".equals(scope)||point.timestamp>=fromUtc&&point.timestamp<toUtc;
    }
    public static String category(String type) { for(String t:TYPES)if(t.equals(type))return t;return "Other"; }
    public static long[] dates(LocalDate first,LocalDate last,ZoneId zone) {
        if(last.isBefore(first))throw new IllegalArgumentException("Date order");
        return new long[]{first.atStartOfDay(zone).toInstant().toEpochMilli(),last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()};
    }
    public List<SavedPoint> apply(List<SavedPoint> points,String currentTrip) {
        ArrayList<SavedPoint> filtered=new ArrayList<>();for(SavedPoint p:points)if(matches(p,currentTrip))filtered.add(p);
        return Collections.unmodifiableList(filtered);
    }
}
