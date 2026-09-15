package com.fiskentra.app.debug;

import java.util.Arrays;

/** Bounded timing storage. Dropped reports are instrumentation loss, not a dropped-frame count. */
public final class FrameTimingStats {
    private final long[]samples=new long[24000];
    private int count,firstDraw,invalid,overflow,droppedReports;
    public void add(long totalNanos,boolean isFirstDraw,int dropped){
        droppedReports+=Math.max(0,dropped);
        if(isFirstDraw){firstDraw++;return;}
        if(totalNanos<=0){invalid++;return;}
        if(count==samples.length){overflow++;return;}
        samples[count++]=totalNanos;
    }
    public long[]rawNanos(){return Arrays.copyOf(samples,count);}
    public Summary summary(){return new Summary(rawNanos(),firstDraw,invalid,overflow,droppedReports);}
    public static final class Summary {
        public final int count,firstDrawExcluded,invalid,overflow,droppedReports,over50;
        public final double p50Ms,p95Ms,maxMs,over50Percent;
        public final boolean completeSample;
        private Summary(long[]values,int firstDraw,int invalid,int overflow,int dropped){
            count=values.length;firstDrawExcluded=firstDraw;this.invalid=invalid;this.overflow=overflow;droppedReports=dropped;
            Arrays.sort(values);int slow=0;for(long value:values)if(value>50000000L)slow++;over50=slow;
            p50Ms=percentile(values,.50);p95Ms=percentile(values,.95);maxMs=count==0?Double.NaN:values[count-1]/1000000d;
            over50Percent=count==0?Double.NaN:slow*100d/count;
            completeSample=count>=120&&invalid==0&&overflow==0&&dropped==0;
        }
        private static double percentile(long[]sorted,double p){return sorted.length==0?Double.NaN:sorted[Math.max(0,(int)Math.ceil(sorted.length*p)-1)]/1000000d;}
        public boolean meetsStandardBudget(double refreshRate){return completeSample&&Math.abs(refreshRate-60)<1&&p95Ms<=32&&over50Percent<=5;}
    }
}
