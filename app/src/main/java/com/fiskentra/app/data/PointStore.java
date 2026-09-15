package com.fiskentra.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import com.fiskentra.app.location.FiskentraLocationManager;
import com.fiskentra.app.model.SavedPoint;
import com.fiskentra.app.model.WeatherSnapshot;
import com.fiskentra.app.model.CatchDetails;
import com.fiskentra.app.model.FishingDay;
import com.fiskentra.app.model.PointMetadata;
import org.json.JSONObject;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Android adapter around the process-wide durable ledger. Reads use an immutable cached snapshot. */
public final class PointStore {
    private static final Map<String, PointLedger> LEDGERS = new HashMap<>();
    private final PointLedger ledger;
    private final Context context;
    private final SharedPreferences appearance;
    public PointStore(Context context) {
        this.context = context.getApplicationContext();
        appearance = context.getSharedPreferences("field_map", Context.MODE_PRIVATE);
        File file = new File(this.context.getFilesDir(), "map-data-v1.json");
        synchronized (LEDGERS) {
            PointLedger loaded = LEDGERS.get(file.getAbsolutePath());
            if (loaded == null) {
                String legacy = context.getSharedPreferences("fiskentra_points", 0).getString("saved_points", "[]");
                String trips = context.getSharedPreferences("fiskentra_fishing_days", 0).getString("sessions", "[]");
                String sync = new JSONObject(context.getSharedPreferences("fiskentra_point_sync_status", 0).getAll()).toString();
                loaded = new PointLedger(new AndroidLedgerDisk(file), legacy, trips, sync);
                LEDGERS.put(file.getAbsolutePath(), loaded);
            }
            ledger = loaded;
        }
    }
    public PointLedger ledger() { return ledger; }
    public PointLedger.Snapshot snapshot() { return ledger.snapshot(); }
    public List<SavedPoint> all() { return snapshot().points; }
    public SavedPoint find(long id) { return ledger.find(id); }
    public long currentTripId() {
        return tripIdAt(System.currentTimeMillis());
    }
    public long tripIdAt(long occurredAtUtc) {
        long match = 0; int matches = 0;
        for (FishingDay day : new FishingDayStore(context).all())
            if (occurredAtUtc >= day.startedAt && (day.endedAt == 0 || occurredAtUtc < day.endedAt)) { match = day.id; matches++; }
        if (matches > 0) return matches == 1 ? match : 0;
        TrackStore track = new TrackStore(context);
        return track.startedAt() > 0 && occurredAtUtc >= track.startedAt()
                && (track.isActive() || occurredAtUtc < track.stoppedAt()) ? track.startedAt() : 0;
    }
    public SavedPoint add(double lat, double lon, String type, String note) {
        if (!com.fiskentra.app.model.FieldNavigation.validCoordinate(lat, lon)) throw new IllegalArgumentException("Invalid coordinates");
        long now = System.currentTimeMillis();
        return ledger.insert(candidate(UUID.randomUUID().toString(), now, currentTripId(), type, note)
                .withLocation(lat, lon, now, Double.NaN, "manual"));
    }
    /** Missing fixes are durable immediately; later GPS callbacks never silently move an event. */
    public SavedPoint capture(String eventId, long occurredAtUtc, long tripId, String type,
            String note, Location fix) {
        SavedPoint point = candidate(eventId, occurredAtUtc, tripId, type, note);
        if (fix != null && com.fiskentra.app.model.CapturePolicy.canAttach(occurredAtUtc,
                fix.getTime(), fix.getElapsedRealtimeNanos(), android.os.SystemClock.elapsedRealtimeNanos()))
            point = point.withLocation(fix.getLatitude(), fix.getLongitude(), fix.getTime(),
                    fix.hasAccuracy() ? fix.getAccuracy() : Double.NaN, fix.getProvider());
        return ledger.insert(point);
    }
    private SavedPoint candidate(String eventId, long occurred, long trip, String type, String note) {
        return new SavedPoint(Math.max(1, occurred), Double.NaN, Double.NaN, occurred, type,
                note == null ? "" : note, null, null, "", appearance.getString(type + "_symbol", ""),
                appearance.getInt(type + "_color", 0), appearance.getInt(type + "_size", 18),
                eventId, trip, false, 1, "UNLOCATED", 0, Double.NaN, "");
    }
    public void delete(long id) { ledger.delete(id); }
    public SavedPoint updateWeather(long id, WeatherSnapshot weather) { return ledger.edit(id, p -> p.withWeather(weather)); }
    public SavedPoint updateCatchDetails(long id, CatchDetails details) { return ledger.edit(id, p -> p.withCatchDetails(details)); }
    public SavedPoint updateMetadata(long id, String title, String type, String note) {
        SavedPoint previous = find(id);
        String checkedType = previous != null && previous.type.equals(type) && !PointMetadata.isAllowedType(type) ? "Waypoint" : type;
        String error = PointMetadata.validate(title, checkedType, note);
        if (!error.isEmpty()) throw new IllegalArgumentException(error);
        return ledger.edit(id, p -> p.withMetadata(PointMetadata.clean(title), type, PointMetadata.clean(note)));
    }
    public SavedPoint style(long id, String title, String symbol, int color, int size) {
        return ledger.edit(id, p -> p.withAppearance(title, symbol, color, size));
    }
    public SavedPoint setFavorite(long id, boolean favorite) { return ledger.edit(id, p -> p.withFavorite(favorite)); }
    public SavedPoint setTripId(long id, long tripId) { return ledger.edit(id, p -> p.withTripId(tripId)); }
    public SavedPoint setLocation(long id, double lat, double lon) { return setLocation(id, lat, lon, System.currentTimeMillis(), Double.NaN, "manual"); }
    public SavedPoint setLocation(long id, double lat, double lon, long fixTime, double accuracy, String source) {
        return ledger.edit(id, p -> p.withLocation(lat, lon, fixTime, accuracy, source));
    }
}
