package com.fiskentra.app.navigation;

import com.fiskentra.app.model.RoadRoute;
import org.json.*;
import java.util.*;

public final class ValhallaParser {
    private ValhallaParser() {}
    public static RoadRoute parse(String json,String profile,double lat,double lon) throws JSONException {
        JSONObject trip=new JSONObject(json).getJSONObject("trip");
        if(trip.getInt("status")!=0 || !"kilometers".equals(trip.getString("units"))) throw new IllegalArgumentException("Route unavailable");
        JSONArray legs=trip.getJSONArray("legs");
        // Requests contain only a start and destination; accepting unexpected legs would misalign steps.
        if(legs.length()!=1) throw new IllegalArgumentException("Unexpected route legs");
        JSONObject leg=legs.getJSONObject(0),summary=trip.getJSONObject("summary");
        List<RoadRoute.Coordinate> shape=RoadRoute.decodePolyline6(leg.getString("shape"));
        List<RoadRoute.Step> steps=new ArrayList<>(); JSONArray items=leg.getJSONArray("maneuvers");
        for(int i=0;i<items.length();i++) { JSONObject s=items.getJSONObject(i);
            JSONArray streets=s.optJSONArray("street_names");
            StringBuilder street=new StringBuilder();
            if(streets!=null)for(int j=0;j<streets.length();j++){if(j>0)street.append(" / ");street.append(streets.getString(j));}
            steps.add(new RoadRoute.Step(s.getInt("begin_shape_index"),s.getInt("end_shape_index"),s.getInt("type"),s.getString("instruction"),s.getDouble("time"),street.toString(),s.optInt("roundabout_exit_count",0)));
        }
        return new RoadRoute(shape,steps,summary.getDouble("length")*1000,summary.getDouble("time"),profile,lat,lon);
    }
}
