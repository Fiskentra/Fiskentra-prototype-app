package com.fiskentra.app.model;

/** Latches distance bands so GPS jitter cannot repeat a spoken instruction. */
public final class NavigationCue {
    private int step=-1,band=4;
    private boolean arrived;
    public void reset(){step=-1;band=4;arrived=false;}
    public boolean advance(int nextStep,double distance,boolean atEnd) {
        if(arrived)return false;
        if(atEnd){arrived=true;return true;}
        int nextBand=distance<=25?0:distance<=100?1:distance<=300?2:3;
        if(nextStep==step && nextBand>=band)return false;
        step=nextStep;band=nextBand;return true;
    }
}
