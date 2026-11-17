package com.fiskentra.app;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.fiskentra.app.backend.PointSyncQueue;
import com.fiskentra.app.flic.FiskentraFlic2Manager;
import com.fiskentra.app.location.HazardAlerts;
import com.fiskentra.app.offline.OfflineMapController;

import io.flic.flic2libandroid.Flic2Manager;

public final class FiskentraApplication extends Application {
    private FiskentraFlic2Manager flicManager;
    private OfflineMapController offlineMapController;
    private com.fiskentra.app.backend.SupabaseAuthManager authManager;
    private PointSyncQueue pointSyncQueue;
    private com.fiskentra.app.backend.AccountBackupManager backupManager;
    private com.fiskentra.app.location.ConnectionAlerts connectionAlerts;
    private HazardAlerts hazardAlerts;

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(AppLanguage.english(base));
    }

    @Override public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) timber.log.Timber.plant(new timber.log.Timber.DebugTree());
        Flic2Manager.initAndGetInstance(getApplicationContext(), new Handler(Looper.getMainLooper()));
        flicManager = new FiskentraFlic2Manager(this);

        android.content.SharedPreferences backend = getSharedPreferences("fiskentra_backend", MODE_PRIVATE);
        String backendUrl = com.fiskentra.app.backend.SupabaseConfig.url();
        if (com.fiskentra.app.backend.CloudDataPolicy.allowsPersonalDataRequests()
                && !backendUrl.equals(backend.getString("url", ""))) {
            com.fiskentra.app.backend.BackendProjectSwitch.requeue(
                    new com.fiskentra.app.data.PointStore(this).ledger());
            if (!backend.edit().putString("url", backendUrl).commit()) {
                throw new IllegalStateException("Could not persist backend migration");
            }
        }
        PointSyncQueue.get(this);
        com.fiskentra.app.backend.AccountBackupManager.get(this);
    }

    public FiskentraFlic2Manager getFlicManager() {
        return flicManager;
    }

    public synchronized com.fiskentra.app.backend.SupabaseAuthManager getAuthManager() {
        if (authManager == null) authManager = new com.fiskentra.app.backend.SupabaseAuthManager(this);
        return authManager;
    }

    public synchronized PointSyncQueue getPointSyncQueue() {
        if (pointSyncQueue == null) pointSyncQueue = new PointSyncQueue(this);
        return pointSyncQueue;
    }
    public synchronized com.fiskentra.app.backend.AccountBackupManager getBackupManager() {
        if (backupManager == null) backupManager = new com.fiskentra.app.backend.AccountBackupManager(this);
        return backupManager;
    }

    public OfflineMapController getOfflineMapController() { if(offlineMapController==null)offlineMapController=new OfflineMapController(this);return offlineMapController; }

    public synchronized com.fiskentra.app.location.ConnectionAlerts getConnectionAlerts() {
        if (connectionAlerts == null) connectionAlerts = new com.fiskentra.app.location.ConnectionAlerts(this);
        return connectionAlerts;
    }

    public synchronized HazardAlerts getHazardAlerts() {
        if (hazardAlerts == null) hazardAlerts = new HazardAlerts(this);
        return hazardAlerts;
    }
}
