package org.maplibre.android.offline;
public class OfflineRegionError {
    public static final String REASON_CONNECTION="connection";final String reason,message;
    public OfflineRegionError(String reason,String message){this.reason=reason;this.message=message;}
    public String getReason(){return reason;}public String getMessage(){return message;}
}
