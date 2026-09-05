package com.fiskentra.app;

import android.app.Activity;
import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import com.fiskentra.app.ui.DesignScreens;
import com.fiskentra.app.ui.MapTilerMapView;
import com.fiskentra.app.model.*;
import java.util.*;

/** Debug-only visual fixture. Does not read or change the user's account or saved data. */
public final class DesignPreviewActivity extends Activity implements DesignScreens.Host {
    private DesignScreens design;
    private final DesignScreens.State state=new DesignScreens.State();
    private LinearLayout root;
    private String route;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(DesignScreens.BG);getWindow().setNavigationBarColor(DesignScreens.BG);
        state.authMode=getIntent().getStringExtra("mode");if(state.authMode==null)state.authMode="signin";
        state.setupStep=getIntent().getIntExtra("step",0);
        state.gps=state.gpsEnabled=state.bluetooth=state.notifications=state.connected=true;
        state.singleTest=state.doubleTest=state.holdTest=state.setupStep==3;
        state.device="Flic 2";state.email="alex@example.com";state.name="Alex";
        if(!getIntent().getBooleanExtra("empty",false))loadFixture();
        design=new DesignScreens(this,this);
        design.edit(new SavedPoint(1,55.9421,37.5802,System.currentTimeMillis(),"Catch","",null,new CatchDetails("Pike",68,3.4,"Wobbler 110 mm","",true,"")));
        go(getIntent().getStringExtra("screen")==null?"profile":getIntent().getStringExtra("screen"));
    }
    private void loadFixture(){
        long now=System.currentTimeMillis();
        WeatherSnapshot weather=new WeatherSnapshot(now,17,16,74,0.2,1012,8,225,2,"Europe/Berlin","Preview fixture");
        List<ForecastDay> days=new ArrayList<>();
        Calendar day=Calendar.getInstance();java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US);
        for(int i=0;i<5;i++){String date=format.format(day.getTime());days.add(new ForecastDay(date,i%2==0?2:3,11+i,19+i,20+i*8,0.2+i,8+i*2,225,date+"T06:20",date+"T20:10"));day.add(Calendar.DATE,1);}
        state.forecast=new WeatherForecast(now,55.9421,37.5802,"Europe/Berlin",weather,days);
        state.tripStarted=now-(4*60+18)*60000L;state.tripStopped=now;state.distanceKm=8.6;
        state.location=new Location("preview");state.location.setLatitude(55.9421);state.location.setLongitude(37.5802);state.location.setAccuracy(4);state.location.setTime(now);
        state.points=new ArrayList<>();String[] lures={"Wobbler","Spinner","Wobbler","Jig",""};
        for(int i=0;i<5;i++)state.points.add(new SavedPoint(i+1,55.9421+i*.00012,37.5802+i*.00013,state.tripStarted+(i*43+10)*60000L,"Catch","Preview fixture",weather,new CatchDetails("Pike",40+i*7,.8+i*.65,lures[i],"",true,"")));
        // An older catch proves that summary aggregation excludes other trips.
        state.points.add(new SavedPoint(99,55.94,37.58,state.tripStarted-60000L,"Catch","Outside preview trip",weather,new CatchDetails("Perch",20,.3,"Bait","",true,"")));
    }
    public DesignScreens.State read(){return state;}
    public void go(String screen){if(screen.startsWith("auth:")){state.authMode=screen.substring(5);screen="profile";}route=screen;root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(DesignScreens.BG);root.addView(design.create(screen),new LinearLayout.LayoutParams(-1,0,1));if(!screen.equals("profile")&&!screen.equals("flicSetup")&&!screen.equals("onboarding")&&!screen.equals("profileSetup")&&!screen.equals("flicRequired"))root.addView(design.navigation(screen));setContentView(root);root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});root.requestApplyInsets();}
    public void command(String action){if(action.equals("setupNext")){state.setupStep=Math.min(3,state.setupStep+1);go(route);}else if(action.equals("setupBack")){state.setupStep=Math.max(0,state.setupStep-1);go(route);}else if(action.equals("metric")||action.equals("imperial")){state.imperial=action.equals("imperial");go(route);}else Toast.makeText(this,"Visual fixture only — no user data changed",Toast.LENGTH_SHORT).show();}
    public void species(String species){state.species=species;go(route);}
    public MapTilerMapView map(){MapTilerMapView map=new MapTilerMapView(this);map.setData(state.location,state.points,Collections.emptyList(),null);return map;}
    public void point(SavedPoint p,String action){}
    public void trip(FishingDay day){go("tripSummary");}
    public void saveCatch(SavedPoint p,CatchDetails d){}
    public String syncLabel(SavedPoint p){return "Preview";}
    public int syncColor(SavedPoint p){return DesignScreens.MUTED;}
    public void auth(boolean signup,boolean recover,EditText name,EditText email,EditText password,EditText confirm){if(!email.getText().toString().contains("@")){email.setError("Enter a valid email address");return;}command("auth");}
    public View legacy(String screen){TextView t=new TextView(this);t.setText("Debug visual fixture");return t;}
    @Override public void onBackPressed(){finish();}
}
