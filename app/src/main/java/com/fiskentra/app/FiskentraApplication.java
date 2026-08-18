package com.fiskentra.app;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import io.flic.flic2libandroid.Flic2Manager;

public final class FiskentraApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Flic2Manager.initAndGetInstance(getApplicationContext(), new Handler(Looper.getMainLooper()));
    }
}
