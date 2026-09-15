package com.fiskentra.app.navigation;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import com.fiskentra.app.model.RoadRoute;
import com.fiskentra.app.model.NavigationCue;
import java.util.Locale;

/** Foreground, opt-in instructions; one cue per maneuver/distance band. */
public final class NavigationVoice {
    public interface Listener { void unavailable(); }
    private final TextToSpeech speech;
    private final Listener listener;
    private boolean enabled,ready,closed,failed,active;
    private final NavigationCue cues=new NavigationCue();
    public NavigationVoice(Context context,Listener listener) {
        this.listener=listener;
        speech=new TextToSpeech(context.getApplicationContext(),status->new android.os.Handler(android.os.Looper.getMainLooper()).post(()->initialize(status)));
    }
    private void initialize(int status) {
        if(closed)return;
        int language=status==TextToSpeech.SUCCESS?speech.setLanguage(Locale.US):TextToSpeech.LANG_NOT_SUPPORTED;
        ready=language>=0; failed=!ready;
        if(ready)speech.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
        if(failed&&enabled){enabled=false;listener.unavailable();}
    }
    public void setEnabled(boolean value) { enabled=value; cues.reset(); if(!value)speech.stop(); else if(failed){enabled=false;listener.unavailable();} }
    public void reset() { cues.reset(); speech.stop(); }
    public void pause() { speech.stop(); }
    public void setActive(boolean value) { active=value;if(!value)speech.stop(); }
    public void announce(String message) {
        if(!enabled||!ready||closed||!active||message==null||message.isEmpty())return;
        if(speech.speak(message,TextToSpeech.QUEUE_FLUSH,null,"fiskentra-local-guidance")==TextToSpeech.ERROR){enabled=false;listener.unavailable();}
    }
    public void update(RoadRoute route,RoadRoute.Progress progress,boolean arrived) {
        if(!enabled||!ready||closed||!active)return;
        int band=progress.toTurn<=25?0:progress.toTurn<=100?1:progress.toTurn<=300?2:3;
        String cue=arrived?"arrived":progress.nextStep+":"+band;
        if(!cues.advance(progress.nextStep,progress.toTurn,arrived))return;
        String message=arrived?(route.destinationGap()>30?"End of mapped route. Your point is off the road.":"You have arrived."):
            (band==0?"Now. ":"In "+Math.round(progress.toTurn)+" meters. ")+route.steps.get(progress.nextStep).instruction;
        if(speech.speak(message,TextToSpeech.QUEUE_FLUSH,null,"fiskentra-"+cue)==TextToSpeech.ERROR){enabled=false;listener.unavailable();}
    }
    public void close() {closed=true;speech.stop();speech.shutdown();}
}
