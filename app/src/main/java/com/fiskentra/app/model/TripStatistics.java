package com.fiskentra.app.model;

import java.util.*;

/** Pure calculations for recorded catches; never predicts or invents fishing activity. */
public final class TripStatistics {
    private TripStatistics() {}
    public static boolean includes(long timestamp,long start,long end) {
        return start>0 && end>=start && timestamp>=start && timestamp<=end;
    }
    public static int[] hourlyBuckets(List<Long> timestamps,TimeZone zone) {
        int[] counts=new int[6];Calendar time=Calendar.getInstance(zone,Locale.US);
        for(long timestamp:timestamps){time.setTimeInMillis(timestamp);counts[time.get(Calendar.HOUR_OF_DAY)/4]++;}
        return counts;
    }
    public static LinkedHashMap<String,Integer> lureCounts(List<String> lures) {
        Map<String,Integer> counts=new TreeMap<>();
        for(String lure:lures){String label=lure==null||lure.trim().isEmpty()?"Not recorded":lure.trim();counts.put(label,counts.getOrDefault(label,0)+1);}
        List<Map.Entry<String,Integer>> sorted=new ArrayList<>(counts.entrySet());
        sorted.sort((left,right)->{int count=Integer.compare(right.getValue(),left.getValue());return count!=0?count:left.getKey().compareTo(right.getKey());});
        LinkedHashMap<String,Integer> result=new LinkedHashMap<>();int others=0;
        for(int i=0;i<sorted.size();i++){Map.Entry<String,Integer> entry=sorted.get(i);if(i<3)result.put(entry.getKey(),entry.getValue());else others+=entry.getValue();}
        if(others>0)result.merge("Other lures",others,Integer::sum);return result;
    }
}
