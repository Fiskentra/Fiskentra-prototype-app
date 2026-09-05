package com.fiskentra.app;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.fiskentra.app.backend.PointSyncQueue;
import com.fiskentra.app.flic.FiskentraFlic2Manager;
import com.fiskentra.app.offline.OfflineMapController;

import io.flic.flic2libandroid.Flic2Manager;

public final class FiskentraApplication extends Application {
    private FiskentraFlic2Manager flicManager;
    private OfflineMapController offlineMapController;

    @Override public void onCreate() {
        super.onCreate();
        Flic2Manager.initAndGetInstance(getApplicationContext(), new Handler(Looper.getMainLooper()));
        flicManager = new FiskentraFlic2Manager(this);
        offlineMapController = new OfflineMapController(this);
        PointSyncQueue.get(this);
    }

    public FiskentraFlic2Manager getFlicManager() {
        return flicManager;
    }

    public OfflineMapController getOfflineMapController() { return offlineMapController; }
}
