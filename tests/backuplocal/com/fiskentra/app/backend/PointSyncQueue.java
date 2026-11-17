package com.fiskentra.app.backend;
import android.content.Context;
import com.fiskentra.app.model.SavedPoint;
/** Only the unused Android convenience constructor is linked in the JVM fixture. */
public final class PointSyncQueue {
    public static PointSyncQueue get(Context context) { throw new UnsupportedOperationException(); }
    public String prepareBackupPointId(String owner, SavedPoint point) { throw new UnsupportedOperationException(); }
    public String backupOwnerOf(long id) { throw new UnsupportedOperationException(); }
    public boolean isBackupAccountCurrent(String owner) { throw new UnsupportedOperationException(); }
}
