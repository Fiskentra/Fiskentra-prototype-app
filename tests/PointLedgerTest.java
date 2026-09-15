import com.fiskentra.app.data.PointLedger;
import com.fiskentra.app.model.SavedPoint;
import com.fiskentra.app.model.CatchDetails;
import com.fiskentra.app.model.CapturePolicy;
import com.fiskentra.app.model.WeatherSnapshot;
import com.fiskentra.app.backend.SyncResponsePolicy;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** T01-T03: real close/reopen, interruption boundaries, ordered operations and immutable events. */
public final class PointLedgerTest {
    static int assertions;
    static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    static SavedPoint capture(String event, long time) {
        return new SavedPoint(time, Double.NaN, Double.NaN, time, "Catch", "reeds", null, null, "Pike",
                "fish", 0xFF33AA55, 22, event, 77, true, 1, "UNLOCATED", 0, Double.NaN, "");
    }
    static PointLedger open(Path file) { return new PointLedger(new PointLedger.AtomicDisk(file.toFile()), "[]", "[]", "{}"); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("fiskentra-ledger-test-");
        try {
            Path file = root.resolve("capture.json"); PointLedger store = open(file);
            SavedPoint one = store.insert(capture("flic:a:1", 1000));
            check(!one.hasLocation(), "no GPS stays unlocated");
            check(!PointLedger.encode(one).has("lat"), "absent coordinate omitted, never zero");
            check(store.pendingCount() == 0, "unlocated events kept locally, not sent to coordinate API");
            store = open(file);
            check(store.snapshot().points.size() == 1 && store.find(one.id).timestamp == 1000, "reopen keeps event/time");
            check(store.insert(capture("flic:a:1", 9000)).id == one.id && store.snapshot().points.size() == 1, "duplicate callback preserves original time");
            SavedPoint two = store.insert(capture("flic:a:2", 1000));
            check(two.id != one.id && store.snapshot().points.size() == 2, "distinct same-time intentional presses not merged");
            check(!CapturePolicy.canAttach(1000, 22000, 90_000_000_000L, 91_000_000_000L), "late fix requires confirmation");
            check(CapturePolicy.canAttach(1000, 1000, 90_000_000_000L, 91_000_000_000L), "contemporaneous fix accepted");
            check(!CapturePolicy.canAttach(1000, 1000, 90_000_000_000L, 121_000_000_000L), "31 second stale rejected");
            check(!CapturePolicy.canAttach(1000, 1000, 90_000_000_000L, 1_000L), "reboot monotonic mismatch rejected");
            store.edit(one.id, p -> p.withLocation(56.1,24.2,22000,12,"confirmed_later_gps"));
            store = open(file); one = store.find(one.id);
            check(one.timestamp == 1000 && one.locatedAtUtc == 22000, "manual confirmation separates fix time from event time");
            check(one.favorite && one.tripId == 77 && one.eventId.equals("flic:a:1"), "location copy preserves metadata");
            SavedPoint base = one; List<SavedPoint> old = store.snapshot().points;
            store.edit(one.id, p -> p.withMetadata("Renamed","Catch","note").withAppearance("Renamed","flag",0xFFAA00FF,26)
                    .withCatchDetails(new CatchDetails("Pike",62,2.4,"spoon","notes",true,"/private/photo.jpg")));
            SavedPoint enriched = store.find(one.id);
            check(old.contains(base) && !old.contains(enriched), "snapshot remains unchanged after edit");
            check(enriched.favorite && enriched.tripId == 77 && enriched.locatedAtUtc == 22000, "all copy methods preserve capture metadata");
            check(enriched.revision == base.revision + 1, "one data edit increments one revision");
            store = open(file);
            check(store.find(one.id).catchDetails.localPhotoPath.equals("/private/photo.jpg"), "photo and catch persist across reopen");
            // Never-uploaded delete completes locally and is crash-atomic with the tombstone.
            store.delete(one.id); store = open(file);
            check(store.find(one.id) == null && store.isDeleted(one.id), "offline delete survived reopen");
            check(store.claim(Long.MAX_VALUE) == null, "never-sent delete needs no network request");
            check(store.edit(one.id, p -> p.withWeather(null)) == null, "late weather cannot restore deleted point");
            check(store.insert(capture("flic:a:1",1000)) == null, "event receipt prevents duplicate resurrection");
            check(store.delete(one.id), "repeat local deletion idempotent");

            file = root.resolve("ordering.json"); store = open(file);
            SavedPoint road = store.insert(capture("b", 2000).withLocation(1,2,2000,5,"gps"));
            PointLedger.Operation first = store.claim(0); check(first != null && first.attempts == 1, "claim persisted before send");
            store.edit(road.id, p -> p.withMetadata("new",p.type,p.note));
            store.delete(road.id);
            check(store.claim(Long.MAX_VALUE) == null, "delete waits for in-flight upsert");
            store = open(file);
            PointLedger.Operation recovered = store.claim(0);
            check(recovered.operationId.equals(first.operationId) && recovered.attempts == 2, "reopen retries same interrupted operation");
            store.finish(recovered.operationId, true, "", "uploaded", 0);
            PointLedger.Operation deletion = store.claim(0);
            check(deletion.kind.equals("delete") && deletion.pointId == road.id, "delete follows upload; unsent edit skipped");
            store.finish(deletion.operationId, false, "transient", "offline",1000);
            check(store.pendingCount() == 1 && store.status(road.id).equals("delete_failed"), "failed deletion counts in queue");
            check(store.claim(1001) == null, "bounded backoff prevents immediate retry");
            store = open(file); check(store.pendingCount() == 1, "failed delete survives process restart");
            store.retry(); deletion = store.claim(1001);
            check(deletion != null && deletion.kind.equals("delete"), "explicit retry handles delete");
            store.finish(deletion.operationId,false,"auth","Access not confirmed",1001);
            check(store.status(road.id).equals("auth") && store.claim(Long.MAX_VALUE) == null, "zero row/RLS denial stays actionable");
            store.retry(); deletion = store.claim(0); store.finish(deletion.operationId,true,"","deleted",2000);
            store = open(file); check(store.pendingCount() == 0 && store.find(road.id) == null, "confirmed delete stays deleted after restart");
            check(store.delete(road.id) && store.pendingCount() == 0, "confirmed repeat delete needs no blind request");
            check(PointLedger.backoff(99) <= 15*60_000L, "retry capped");
            check(!SyncResponsePolicy.uploaded(409), "actual transport rejects conflict as success");
            check(!SyncResponsePolicy.deleted(200,0), "empty RLS-filtered response does not prove deletion");
            check(SyncResponsePolicy.deleted(200,1), "represented deletion accepted");
            check(SyncResponsePolicy.errorCategory("HTTP 403").equals("auth"), "access failure separate from connectivity");
            check(SyncResponsePolicy.errorCategory("HTTP 429").equals("transient"), "rate limit retries with backoff");
            check(SyncResponsePolicy.errorCategory("HTTP 503").equals("transient"), "temporary server error retries");

            // A later edit cannot be incorrectly marked synced by an earlier upload callback.
            store = open(root.resolve("revision.json")); road = store.insert(capture("c",3000).withLocation(1,2,3000,5,"gps"));
            first = store.claim(0); store.edit(road.id,p -> p.withFavorite(false));
            store.finish(first.operationId,true,"","core uploaded",0);
            check(!store.status(road.id).equals("synced"), "old receipt cannot confirm newer revision");
            first = store.claim(0); store.finish(first.operationId,false,"permanent","HTTP 409",0);
            check(store.status(road.id).equals("failed"), "409 remains failure");

            String legacy = "[{\"id\":42,\"lat\":56,\"lon\":24,\"time\":150,\"type\":\"Future custom type\",\"note\":\"note\",\"title\":\"Title\",\"symbol\":\"fish\",\"color\":-1,\"size\":28,\"catch_details\":{\"species\":\"Pike\",\"local_photo_path\":\"/keep.jpg\"}},{\"id\":43,\"lat\":56,\"lon\":24,\"time\":200}]";
            String trips = "[{\"id\":9,\"started_at\":100,\"ended_at\":200,\"route\":[{\"lat\":56,\"lon\":24,\"time\":110,\"segment\":true}]},{\"id\":10,\"started_at\":200,\"ended_at\":300}]";
            file = root.resolve("migration.json"); store = new PointLedger(new PointLedger.AtomicDisk(file.toFile()),legacy,trips,"{}");
            check(store.find(42).tripId == 9 && store.find(43).tripId == 10, "half-open trip intervals assign boundary correctly");
            check(store.find(42).type.equals("Future custom type") && store.find(43).title.isEmpty(), "unknown type/missing fields safe");
            check(store.find(42).title.equals("Title") && store.find(42).color == -1 && store.find(42).catchDetails.localPhotoPath.equals("/keep.jpg"), "migration retains legacy fields/photo");
            check(new JSONObject(Files.readString(file)).getJSONObject("legacyBackup").getString("trips").equals(trips), "exact archive/segment backup retained");
            store = new PointLedger(new PointLedger.AtomicDisk(file.toFile()),"[]","[]","{}");
            check(store.find(42) != null && store.snapshot().points.size() == 2, "repeated migration does not replace data");
            PointLedger overlap = new PointLedger(new PointLedger.AtomicDisk(root.resolve("overlap.json").toFile()),legacy,"[{\"id\":1,\"started_at\":100,\"ended_at\":300},{\"id\":2,\"started_at\":120,\"ended_at\":250}]","{}");
            check(overlap.find(42).tripId == 0, "ambiguous legacy trip left unassigned");
            PointLedger deletedLegacy = new PointLedger(new PointLedger.AtomicDisk(root.resolve("delete-migration.json").toFile()),legacy,trips,"{\"42_state\":\"delete_failed\"}");
            check(deletedLegacy.find(42)==null && deletedLegacy.isDeleted(42), "legacy failed deletion migrates to tombstone, not reupload");
            check(deletedLegacy.claim(0).kind.equals("delete"), "legacy delete retry remains ordered first");
            // Actual disk still contains the previous transaction if a write fails before replacement.
            file = root.resolve("failure.json"); final PointLedger.AtomicDisk disk = new PointLedger.AtomicDisk(file.toFile());
            PointLedger seed = open(file); SavedPoint seedPoint = seed.insert(capture("z",4000));
            PointLedger fails = new PointLedger(new PointLedger.Disk() { public String read() throws java.io.IOException { return disk.read(); } public void write(String value) throws java.io.IOException { throw new java.io.IOException("ENOSPC"); } },"[]","[]","{}");
            boolean failed=false; try { fails.delete(seedPoint.id); } catch (IllegalStateException expected) { failed=true; }
            check(failed && fails.find(seedPoint.id)!=null, "disk failure has no false delete acknowledgement");
            check(open(file).find(seedPoint.id)!=null, "failed mutation did not change durable state");
            failed=false; try { fails.insert(capture("zz",5000)); } catch (IllegalStateException expected) { failed=true; }
            check(failed && fails.findEvent("zz")==null, "capture write failure not visible as saved");
            Path mid = root.resolve("migration-failure.json"); final PointLedger.AtomicDisk midDisk = new PointLedger.AtomicDisk(mid.toFile());
            failed=false; try { new PointLedger(new PointLedger.Disk() { public String read(){return null;} public void write(String value)throws java.io.IOException { Files.writeString(root.resolve("migration-failure.json.new"),value); throw new java.io.IOException("process ended before rename"); } },legacy,trips,"{}"); } catch (IllegalStateException expected) { failed=true; }
            check(failed && !Files.exists(mid), "migration did not publish partial data");
            check(new PointLedger(midDisk,legacy,trips,"{}").find(42)!=null, "migration restart uses intact legacy source");
            System.out.println("PASS PointLedger T01/T02/T03: " + assertions + " assertions (real filesystem reopen)");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(root)) { paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {try { Files.deleteIfExists(p); } catch(Exception ignored){} }); }
        }
    }
}
