package com.fiskentra.app.navigation;

import android.content.*;
import android.os.*;
import com.fiskentra.app.BuildConfig;
import com.fiskentra.app.model.RoadRoute;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** One request at a time, bounded responses and a persisted route; no location polling on the server. */
public final class RoutingClient {
    public interface Callback { void result(RoadRoute route,String error); }
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private final SharedPreferences cache;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile int generation;
    private volatile HttpURLConnection active;
    private boolean closed;
    public RoutingClient(Context context) { cache=context.getSharedPreferences("road_route_cache",0); }
    public void restore(String profile,double lat,double lon,Callback callback) {
        int token=++generation;
        IO.execute(()->{
            RoadRoute route=null;
            try {
                if(profile.equals(cache.getString("profile","")) && Double.toString(lat).equals(cache.getString("lat","")) && Double.toString(lon).equals(cache.getString("lon","")))
                    route=ValhallaParser.parse(cache.getString("response",""),profile,lat,lon);
            } catch(Exception ignored) {}
            deliver(token,route,"",callback);
        });
    }
    public void request(double startLat,double startLon,double targetLat,double targetLon,String profile,Callback callback) {
        int token=++generation;
        IO.execute(()->{
            if(token!=generation) return;
            HttpURLConnection connection=null;
            try {
                JSONObject request=new JSONObject().put("locations",new JSONArray()
                        .put(new JSONObject().put("lat",startLat).put("lon",startLon).put("search_cutoff",1000))
                        .put(new JSONObject().put("lat",targetLat).put("lon",targetLon).put("search_cutoff",1000)))
                        .put("costing","pedestrian".equals(profile)?"pedestrian":"auto").put("units","kilometers").put("language","en-US");
                URL url=new URL(BuildConfig.ROUTING_URL);
                if(!"https".equals(url.getProtocol())) throw new IOException("HTTPS required");
                connection=(HttpURLConnection)url.openConnection(); active=connection;
                connection.setConnectTimeout(12000); connection.setReadTimeout(20000); connection.setRequestMethod("POST");
                connection.setInstanceFollowRedirects(false); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json"); connection.setRequestProperty("Accept","application/json");
                connection.setRequestProperty("X-Client-Id","com.fiskentra.app.prototype"); connection.setRequestProperty("User-Agent","Fiskentra/"+BuildConfig.VERSION_NAME+" Android");
                byte[] payload=request.toString().getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(payload.length);
                try(OutputStream out=connection.getOutputStream()) { out.write(payload); }
                int status=connection.getResponseCode();
                if(status!=200) { deliver(token,null,status==429?"Routing busy · retry shortly":status==400?"No route found for this travel mode":"Routing service unavailable",callback); return; }
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream in=connection.getInputStream()) { byte[] buffer=new byte[8192]; int count;
                    while((count=in.read(buffer))!=-1) { if(bytes.size()+count>4_000_000) throw new IOException("Route too large"); bytes.write(buffer,0,count); }
                }
                String json=bytes.toString("UTF-8"); RoadRoute route=ValhallaParser.parse(json,profile,targetLat,targetLon);
                if(token==generation) cache.edit().putString("response",json).putString("profile",profile).putString("lat",Double.toString(targetLat)).putString("lon",Double.toString(targetLon)).apply();
                deliver(token,route,"",callback);
            } catch(Exception error) { deliver(token,null,"Could not build route · check internet or choose another point",callback); }
            finally { if(connection!=null) connection.disconnect(); active=null; }
        });
    }
    private void deliver(int token,RoadRoute route,String error,Callback callback) { main.post(()->{if(!closed&&token==generation)callback.result(route,error);}); }
    public void cancel() { generation++; HttpURLConnection connection=active; if(connection!=null)connection.disconnect(); }
    public void close() { closed=true; cancel(); }
}
