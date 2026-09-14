import com.fiskentra.app.model.RoadRoute;
import com.fiskentra.app.navigation.ValhallaParser;
import java.nio.file.*;
import org.json.*;

public final class ValhallaParserTest {
    static int checks;
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); checks++; }
    static RoadRoute parse(String json) throws Exception { return ValhallaParser.parse(json,"auto",52.529407,13.397634); }
    static void reject(JSONObject json,String message) throws Exception {
        boolean rejected=false;
        try { parse(json.toString()); } catch(IllegalArgumentException | JSONException expected) { rejected=true; }
        check(rejected,message);
    }
    public static void main(String[] args) throws Exception {
        String driving=Files.readString(Path.of(args[0],"berlin-driving.json"));
        String walking=Files.readString(Path.of(args[0],"berlin-walking.json"));
        RoadRoute car=parse(driving),walk=ValhallaParser.parse(walking,"pedestrian",52.529407,13.397634);
        check(Math.abs(car.meters-1890)<1,"kilometers converted to meters");
        check(Math.abs(car.seconds-267.052)<.01,"duration in seconds");
        check(car.shape.size()==133,"complete polyline6 geometry");
        check(Math.abs(car.shape.get(0).lat-52.517037)<.001,"latitude precision");
        check(Math.abs(car.shape.get(0).lon-13.38886)<.001,"longitude precision");
        check(car.steps.size()==3 && car.steps.get(1).type==10,"right turn parsed");
        check(car.steps.get(1).instruction.contains("Torstraße"),"Unicode street name");
        check(car.steps.get(1).street.equals("Torstraße"),"separate street label");
        check(car.steps.get(1).maneuverTitle().equals("Turn right"),"short maneuver title");
        check(new RoadRoute.Step(0,1,15,"Turn left onto Access road.",4,"Access road",0).maneuverTitle().equals("Turn left"),"left turn title");
        check(new RoadRoute.Step(0,1,26,"Enter the roundabout.",4,"",3).maneuverTitle().equals("Take exit 3"),"roundabout exit title");
        check(new RoadRoute.Step(0,1,12,"Make a U-turn.",4).maneuverTitle().equals("Make a U-turn"),"U-turn title");
        check(walk.steps.get(1).street!=null,"unnamed paths supported");
        check(car.destinationGap()<30,"snapped destination");
        RoadRoute.Coordinate start=car.shape.get(0),end=car.shape.get(car.shape.size()-1);
        check(Math.abs(car.progress(start.lat,start.lon,Double.NaN).remainingSeconds-car.seconds)<.1,"maneuver times agree with summary");
        check(car.progress(end.lat,end.lon,Double.NaN).atEnd,"real geometry arrival");
        check(walk.seconds>car.seconds*4 && walk.profile.equals("pedestrian"),"walking uses its own duration");
        JSONObject bad=new JSONObject(driving); bad.getJSONObject("trip").put("status",1); reject(bad,"non-success response");
        bad=new JSONObject(driving); bad.getJSONObject("trip").put("units","miles"); reject(bad,"unexpected units");
        bad=new JSONObject(driving); bad.getJSONObject("trip").getJSONArray("legs").put(new JSONObject()); reject(bad,"unexpected extra leg");
        bad=new JSONObject(driving); bad.getJSONObject("trip").getJSONArray("legs").getJSONObject(0).getJSONArray("maneuvers").getJSONObject(0).put("end_shape_index",9999); reject(bad,"out-of-range maneuver");
        bad=new JSONObject(driving); bad.getJSONObject("trip").remove("summary"); reject(bad,"missing duration and distance");
        bad=new JSONObject(driving); bad.getJSONObject("trip").getJSONArray("legs").getJSONObject(0).put("shape","_"); reject(bad,"truncated geometry");
        System.out.println("Valhalla parser: "+checks+" checks passed");
    }
}
