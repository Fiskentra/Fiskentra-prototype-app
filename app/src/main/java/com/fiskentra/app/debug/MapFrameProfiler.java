package com.fiskentra.app.debug;

import android.app.Activity;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import android.view.FrameMetrics;
import android.view.Window;
import com.fiskentra.app.BuildConfig;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Debug-only Window timing collector; no redraw loop and no persistent data-store access. */
public final class MapFrameProfiler implements AutoCloseable {
    public static final String TAG="FiskentraMapQA";
    private final Activity activity;
    private HandlerThread thread;
    private Handler handler;
    private Window.OnFrameMetricsAvailableListener listener;
    private volatile boolean running;
    private FrameTimingStats stats;
    private long startedNanos,startedUtc,javaStart,nativeStart;
    private String label;
    private float refreshRate;

    public MapFrameProfiler(Activity activity){this.activity=activity;}
    /** UI thread. Start after fixture/style preparation and warm-up, immediately before gestures. */
    public void start(String label){
        if(!BuildConfig.DEBUG)return;
        stop();this.label=label==null?"unspecified":label.substring(0,Math.min(80,label.length()));
        stats=new FrameTimingStats();thread=new HandlerThread("FiskentraMapMetrics");thread.start();handler=new Handler(thread.getLooper());
        startedNanos=SystemClock.elapsedRealtimeNanos();startedUtc=System.currentTimeMillis();
        javaStart=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();nativeStart=Debug.getNativeHeapAllocatedSize();
        refreshRate=activity.getWindow().getDecorView().getDisplay()==null?0:activity.getWindow().getDecorView().getDisplay().getRefreshRate();
        FrameTimingStats capture=stats;running=true;
        listener=(window,metrics,dropped)->capture.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION),metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME)==1,dropped);
        activity.getWindow().addOnFrameMetricsAvailableListener(listener,handler);
        Log.i(TAG,"START "+this.label);
    }
    /** UI thread. Writing/aggregation occur on the collector thread after queued frame callbacks. */
    public void stop(){
        if(!BuildConfig.DEBUG||!running)return;running=false;
        activity.getWindow().removeOnFrameMetricsAvailableListener(listener);listener=null;
        long finished=SystemClock.elapsedRealtimeNanos();
        FrameTimingStats capture=stats;HandlerThread finishingThread=thread;String finishingLabel=label;
        long startTime=startedNanos,utc=startedUtc,javaBefore=javaStart,nativeBefore=nativeStart;float hz=refreshRate;
        handler.post(()->{try{writeReport(capture,finishingLabel,startTime,finished,utc,hz,javaBefore,nativeBefore);}finally{finishingThread.quitSafely();}});
        thread=null;handler=null;
    }
    @Override public void close(){stop();}
    private void writeReport(FrameTimingStats capture,String label,long start,long finish,long utc,float hz,long javaBefore,long nativeBefore){
        try{
            FrameTimingStats.Summary s=capture.summary();JSONObject report=new JSONObject();
            report.put("schema",1).put("label",label).put("version_name",BuildConfig.VERSION_NAME).put("version_code",BuildConfig.VERSION_CODE)
                    .put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("android",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT)
                    .put("refresh_hz",hz).put("started_utc_ms",utc).put("duration_seconds",(finish-start)/1e9)
                    .put("sample_count",s.count).put("first_draw_excluded",s.firstDrawExcluded).put("invalid_samples",s.invalid)
                    .put("overflow",s.overflow).put("dropped_reports",s.droppedReports).put("over_50ms",s.over50)
                    .put("p50_ms",finite(s.p50Ms)).put("p95_ms",finite(s.p95Ms)).put("max_ms",finite(s.maxMs)).put("over_50ms_percent",finite(s.over50Percent))
                    .put("complete_sample",s.completeSample).put("standard_60hz_frame_budget",s.meetsStandardBudget(hz)?"PASS":!s.completeSample||Math.abs(hz-60)>=1?"NOT RUN":"FAIL")
                    .put("java_heap_before",javaBefore).put("java_heap_after",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                    .put("native_heap_before",nativeBefore).put("native_heap_after",Debug.getNativeHeapAllocatedSize())
                    .put("scope","Android Window TOTAL_DURATION, first draw excluded; this does not measure GPU presentation, action latency, retained objects, battery, or independently prove absence of ANR/crash.");
            File directory=new File(activity.getFilesDir(),"qa");if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException("Cannot create QA directory");
            StringBuilder csv=new StringBuilder("sample,total_duration_ns\n");long[]raw=capture.rawNanos();for(int i=0;i<raw.length;i++)csv.append(i).append(',').append(raw[i]).append('\n');
            atomic(new File(directory,"map-profile.csv"),csv.toString());atomic(new File(directory,"map-profile.json"),report.toString(2));
            Log.i(TAG,"REPORT "+report);
        }catch(Exception failure){Log.e(TAG,"Profile report could not be saved",failure);}
    }
    private static Object finite(double value){return Double.isFinite(value)?value:JSONObject.NULL;}
    private static void atomic(File path,String value)throws Exception{
        AtomicFile file=new AtomicFile(path);FileOutputStream stream=null;
        try{stream=file.startWrite();stream.write(value.getBytes(StandardCharsets.UTF_8));file.finishWrite(stream);}catch(Exception failure){if(stream!=null)file.failWrite(stream);throw failure;}
    }
}
