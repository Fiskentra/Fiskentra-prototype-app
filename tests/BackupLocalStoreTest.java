package com.fiskentra.app.data;

import android.content.SharedPreferences;
import com.fiskentra.app.backend.AccountBackupLedger;
import com.fiskentra.app.backend.BackupCodec;
import com.fiskentra.app.backend.BackupLocalStore;
import com.fiskentra.app.backend.CloudPointCodec;
import com.fiskentra.app.model.CatchDetails;
import com.fiskentra.app.model.FishingDay;
import com.fiskentra.app.model.SavedPoint;
import com.fiskentra.app.model.WeatherSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exercises the production capture/importer with original bytes, isolated stores and crash injection. */
public final class BackupLocalStoreTest {
    private static final String A = "11111111-1111-4111-8111-111111111111", B = "22222222-2222-4222-8222-222222222222";
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private interface Throwing { void run() throws Exception; }
    private static void fails(Throwing run, String label) throws Exception { try { run.run(); } catch (Exception expected) { checks++; return; } throw new AssertionError(label); }
    private static final class Disk implements PointLedger.Disk {
        String text; boolean failNext; Runnable afterWrite;
        public String read() { return text; }
        public void write(String value) throws IOException {
            if (failNext) { failNext = false; throw new IOException("simulated interruption"); }
            text = value; if (afterWrite != null) afterWrite.run();
        }
    }
    private static final class Prefs {
        final Map<String, Object> values = new HashMap<>(); boolean failCommit;
        final SharedPreferences prefs;
        Prefs() {
            prefs = (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SharedPreferences.class}, (proxy, method, args) -> {
                if (method.getName().equals("edit")) {
                    Map<String, Object> pending = new HashMap<>(values);
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SharedPreferences.Editor.class}, (editor, edit, input) -> {
                        String n = edit.getName();
                        if (n.startsWith("put")) { pending.put((String) input[0], input[1]); return editor; }
                        if (n.equals("remove")) { pending.remove(input[0]); return editor; }
                        if (n.equals("commit") || n.equals("apply")) {
                            if (failCommit) { failCommit = false; return n.equals("commit") ? false : null; }
                            values.clear(); values.putAll(pending); return n.equals("commit") ? true : null;
                        }
                        throw new UnsupportedOperationException(n);
                    });
                }
                if (method.getName().startsWith("get")) return values.getOrDefault(args[0], args[1]);
                throw new UnsupportedOperationException(method.getName());
            });
        }
    }
    private static final class Identity implements BackupLocalStore.PointIdentities {
        String signedIn = A; final Map<Long, String> owners = new HashMap<>(); Runnable onOwner;
        @Override public String owner(long id) { if (onOwner != null) { Runnable hook=onOwner; onOwner=null; hook.run(); } return owners.getOrDefault(id, ""); }
        @Override public boolean current(String owner) { return signedIn.equals(owner); }
        @Override public String prepare(String owner, SavedPoint point) {
            if (!owner(point.id).isEmpty() && !owner(point.id).equals(owner)) return "";
            owners.put(point.id, owner);
            if (point.eventId.startsWith("cloud:")) return point.eventId.split(":")[2];
            return UUID.nameUUIDFromBytes(point.eventId.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }
    private static final class Fixture {
        final File dir; final Disk pointDisk = new Disk(), backupDisk = new Disk();
        final PointLedger points = new PointLedger(pointDisk, "[]", "[]", "{}");
        final AccountBackupLedger ledger = new AccountBackupLedger(backupDisk);
        final Prefs tripPrefs = new Prefs(), trackPrefs = new Prefs();
        final FishingDayStore trips = new FishingDayStore(tripPrefs.prefs);
        final TrackStore tracks = new TrackStore(trackPrefs.prefs);
        final Identity identity = new Identity(); final BackupLocalStore store;
        Fixture() throws Exception {
            dir = Files.createTempDirectory(new File("build").toPath(), "backup-fixture-").toFile();
            store = new BackupLocalStore(dir, points, trips, tracks, ledger, identity);
        }
    }
    private static final WeatherSnapshot WEATHER = new WeatherSnapshot(100, 12.2, 10.1, 90, 0.2, 1013, 17.7, 270, 3, "Europe/Riga", "Open-Meteo");
    private static byte[] original() { return new byte[]{(byte) 137,80,78,71,13,10,26,10,0,0,1,9,(byte) 255,17,22,0,6}; }
    private static SavedPoint source(Fixture f) throws Exception {
        File photos = new File(f.dir, "catch_photos"); check(photos.mkdir(), "private photos folder created");
        File photo = new File(photos, "source.png"); Files.write(photo.toPath(), original());
        SavedPoint point = f.points.insert(new SavedPoint(1000, 56.15, 24.16, 1000, "Catch", "original Ω note", WEATHER,
                new CatchDetails("Pike", 62.4, 2.45, "spoon", "catch notes", false, photo.getAbsolutePath()),
                "Favorite place", "fish", 0xff123456, 24, "button-event-1", 2000, true, 1, "LOCATED", 999, 3.3, "gps"));
        JSONArray route = new JSONArray().put(new JSONObject().put("lat",56.1).put("lon",24.1).put("time",900).put("segment",false))
                .put(new JSONObject().put("lat",56.2).put("lon",24.2).put("time",1500).put("segment",true));
        f.tripPrefs.values.put("sessions", new JSONArray().put(new JSONObject().put("id",2000).put("started_at",900).put("ended_at",1900)
                .put("route",route).put("start_weather",WEATHER.toJson()).put("end_weather",WEATHER.toJson())).toString());
        f.trackPrefs.values.put("started_at",1100L); f.trackPrefs.values.put("active",true); f.trackPrefs.values.put("paused",true);
        f.trackPrefs.values.put("points",route.toString()); f.trackPrefs.values.put("start_weather",WEATHER.toJson().toString());
        return point;
    }
    public static void main(String[] args) throws Exception {
        new File("build").mkdirs();
        Fixture source = new Fixture(); SavedPoint originalPoint = source(source);
        BackupLocalStore.Snapshot snap = source.store.capture(A);
        check(snap.pointCount==1 && snap.tripCount==2 && snap.photoCount==1, "capture includes saved trip and active independent recording");
        check(snap.photoBytes==original().length, "original byte length captured");
        check(!snap.data.toString().contains(source.dir.getAbsolutePath()) && !snap.data.toString().contains("local_photo_path"), "no device path leaves phone");
        check(snap.data.getJSONArray("points").getJSONObject(0).has("trip_key"), "point association uses portable trip identity");
        check(BackupCodec.fingerprint(snap.data).equals(BackupCodec.fingerprint(source.store.capture(A).data)), "unchanged capture fingerprint stable");
        check(source.tracks.isActive() && source.tracks.isPaused(), "capture does not change active recording");

        Fixture target = new Fixture(); String backupId = UUID.randomUUID().toString();
        BackupLocalStore.RestoreResult restored = target.store.restore(A, backupId, snap.data, snap.photos);
        check(restored.complete && restored.points==1 && restored.trips==2 && restored.photos==1 && restored.conflicts==0, "fresh phone full restore complete");
        SavedPoint imported = target.points.snapshot().points.get(0);
        check(imported.timestamp==originalPoint.timestamp && imported.favorite && imported.title.equals(originalPoint.title)
                && imported.note.equals(originalPoint.note) && imported.locationAccuracy==originalPoint.locationAccuracy, "all point details retained");
        check(imported.catchDetails.lengthCm==62.4 && imported.catchDetails.weightKg==2.45, "canonical catch measurements retained");
        check(java.util.Arrays.equals(Files.readAllBytes(new File(imported.catchDetails.localPhotoPath).toPath()), original()), "original photo bytes restored without transcoding");
        check(imported.catchDetails.localPhotoPath.startsWith(new File(target.dir,"catch_photos").getCanonicalPath()), "photo path remapped to target private storage");
        check(imported.eventId.startsWith("cloud:"+A+":"), "portable point identity survives local/cloud restore");
        check(target.trips.findForBackup(imported.tripId)!=null, "point links restored local trip ID");
        check(target.trips.allForBackup().stream().allMatch(d -> !d.isActive()), "recovered tracks are archives, no autostart");
        check(target.trips.allForBackup().stream().anyMatch(d -> d.recoveredActive && d.recoveredPaused), "original active/pause status retained as metadata");
        check(!target.tracks.isActive(), "fresh phone current recording is untouched");
        check(target.trips.allForBackup().stream().allMatch(d -> d.route.get(1)[3]==1), "segment breaks retained");
        check(target.trips.allForBackup().stream().allMatch(d -> d.startWeather!=null && d.startWeather.windSpeedKmh==17.7), "trip weather retained");
        check(BackupCodec.fingerprint(target.store.capture(A).data).equals(BackupCodec.fingerprint(snap.data)), "cross-device round trip preserves portable snapshot exactly");
        BackupLocalStore.RestoreResult again = target.store.restore(A, backupId, snap.data, snap.photos);
        check(again.complete && target.points.snapshot().points.size()==1 && target.trips.allForBackup().size()==2, "repeated restore creates no duplicates");
        check(target.ledger.restorePendingCount(A,backupId)==0, "restore durable progress complete");

        Fixture pointFirst = new Fixture();
        JSONObject firstRow=snap.data.getJSONArray("points").getJSONObject(0);
        SavedPoint coreOnly=CloudPointCodec.decode(A,firstRow.getString("key"),firstRow.getJSONObject("point"),1);
        pointFirst.points.insert(coreOnly);
        BackupLocalStore.RestoreResult attach=pointFirst.store.restore(A,backupId,snap.data,snap.photos);
        check(attach.complete && pointFirst.points.snapshot().points.size()==1, "normal point restore followed by full restore enriches rather than duplicates");
        check(pointFirst.points.snapshot().points.get(0).tripId>0 && !pointFirst.points.snapshot().points.get(0).catchDetails.localPhotoPath.isEmpty(), "full restore attaches photo and trip to existing core point");
        check(BackupCodec.fingerprint(pointFirst.store.capture(A).data).equals(BackupCodec.fingerprint(snap.data)), "point-first restoration then new backup preserves every portable field");

        Fixture concurrent = new Fixture(); SavedPoint waiting=concurrent.points.insert(coreOnly);
        concurrent.identity.onOwner=()->concurrent.points.edit(waiting.id,p->p.withMetadata("Concurrent UI edit",p.type,p.note));
        BackupLocalStore.RestoreResult concurrentResult=concurrent.store.restore(A,backupId,snap.data,snap.photos);
        check(!concurrentResult.complete && concurrentResult.conflicts==1, "revision guard detects UI edit between comparison and attachment");
        check(concurrent.points.find(waiting.id).title.equals("Concurrent UI edit")
                && concurrent.points.find(waiting.id).tripId==0 && concurrent.points.find(waiting.id).catchDetails.localPhotoPath.isEmpty(), "late attachment cannot overwrite or partially enrich a newer UI edit");

        Fixture removed = new Fixture(); removed.store.restore(A,backupId,snap.data,snap.photos);
        long removedId=removed.points.snapshot().points.get(0).id;
        removed.points.edit(removedId,p->p.withCatchDetails(p.catchDetails.withLocalPhotoPath("")));
        check(!removed.store.restore(A,backupId,snap.data,snap.photos).complete
                && removed.points.find(removedId).catchDetails.localPhotoPath.isEmpty(), "explicit local photo removal is retained as conflict");
        Fixture lostFile=new Fixture(); lostFile.store.restore(A,backupId,snap.data,snap.photos);
        long lostId=lostFile.points.snapshot().points.get(0).id;
        File lostPhoto=new File(lostFile.points.find(lostId).catchDetails.localPhotoPath);
        check(lostPhoto.delete(), "fixture photo bytes removed while record reference survives");
        check(lostFile.store.restore(A,backupId,snap.data,snap.photos).complete
                && java.util.Arrays.equals(Files.readAllBytes(new File(lostFile.points.find(lostId).catchDetails.localPhotoPath).toPath()), original()), "missing photo bytes can be repaired without undoing explicit removals");

        Fixture sourceDeletion=new Fixture(); SavedPoint originalIdentity=source(sourceDeletion);
        BackupLocalStore.Snapshot beforeDeletion=sourceDeletion.store.capture(A);
        sourceDeletion.points.delete(originalIdentity.id);
        BackupLocalStore.RestoreResult originalDeleted=sourceDeletion.store.restore(A,UUID.randomUUID().toString(),beforeDeletion.data,beforeDeletion.photos);
        check(!originalDeleted.complete && originalDeleted.conflicts==1 && sourceDeletion.points.snapshot().points.isEmpty(), "original Flic event deletion cannot resurrect under a different cloud event identity");

        Fixture newerSource=new Fixture(); SavedPoint beforeChange=source(newerSource);
        BackupLocalStore.Snapshot oldBackup=newerSource.store.capture(A);
        Fixture oldTarget=new Fixture(); oldTarget.store.restore(A,UUID.randomUUID().toString(),oldBackup.data,oldBackup.photos);
        File newPhoto=new File(newerSource.dir,"catch_photos/new.png"); byte[] newBytes=java.util.Arrays.copyOf(original(),original().length+7); newBytes[newBytes.length-1]=42; Files.write(newPhoto.toPath(),newBytes);
        newerSource.points.edit(beforeChange.id,p->p.withMetadata("Newer cloud title",p.type,p.note).withCatchDetails(p.catchDetails.withLocalPhotoPath(newPhoto.getAbsolutePath())));
        BackupLocalStore.Snapshot newBackup=newerSource.store.capture(A);
        String oldTargetDisk=oldTarget.pointDisk.text;
        BackupLocalStore.RestoreResult oldVsNew=oldTarget.store.restore(A,UUID.randomUUID().toString(),newBackup.data,newBackup.photos);
        check(!oldVsNew.complete && oldVsNew.conflicts==1 && oldTarget.pointDisk.text.equals(oldTargetDisk), "different snapshot preserves divergent existing records with honest partial result");
        Fixture newestPhone=new Fixture();
        check(newestPhone.store.restore(A,UUID.randomUUID().toString(),newBackup.data,newBackup.photos).complete, "latest snapshot fully restores on fresh phone");
        SavedPoint newestPoint=newestPhone.points.snapshot().points.get(0);
        check(newestPoint.title.equals("Newer cloud title") && java.util.Arrays.equals(Files.readAllBytes(new File(newestPoint.catchDetails.localPhotoPath).toPath()),newBytes), "new snapshot contains updated metadata and original changed image bytes");
        check(BackupCodec.fingerprint(newestPhone.store.capture(A).data).equals(BackupCodec.fingerprint(newBackup.data)), "latest snapshot round trip retains photo links and geometry");

        List<double[]> preciseRoute=new ArrayList<>();
        preciseRoute.add(new double[]{56.1234567890123,24.8765432109876,1798800000123d});
        preciseRoute.add(new double[]{56.1234567890124,24.8765432109875,1798800000123.25d,1});
        List<double[]> precisionRoundTrip=BackupCodec.route(new JSONArray(BackupCodec.route(preciseRoute).toString()));
        for(int i=0;i<preciseRoute.size();i++) for(int axis=0;axis<3;axis++)
            check(Double.doubleToLongBits(preciseRoute.get(i)[axis])==Double.doubleToLongBits(precisionRoundTrip.get(i)[axis]), "route coordinate/time double precision preserved");
        check(precisionRoundTrip.get(0)[3]==0 && precisionRoundTrip.get(1)[3]==1, "legacy three-value route and explicit segment flag retain semantics");

        target.points.edit(imported.id,p->p.withMetadata("Local edited title",p.type,p.note));
        String editedDisk = target.pointDisk.text;
        BackupLocalStore.RestoreResult conflict = target.store.restore(A,backupId,snap.data,snap.photos);
        check(!conflict.complete && conflict.conflicts==1, "divergent local point prevents false success");
        check(target.pointDisk.text.equals(editedDisk), "restore never overwrites local edit");
        check(target.ledger.restorePendingCount(A,backupId)==1, "conflicting restore remains resumable");

        Fixture missing = new Fixture();
        fails(()->missing.store.restore(A,backupId,snap.data,new HashMap<>()), "missing photo fails before import");
        check(missing.points.snapshot().points.isEmpty() && missing.trips.allForBackup().isEmpty(), "missing photo causes no partial records");
        File corrupt = new File(missing.dir,"bad.png"); Files.write(corrupt.toPath(),new byte[]{1,2,3});
        Map<String,File> bad = new HashMap<>(); bad.put(snap.photos.keySet().iterator().next(),corrupt);
        fails(()->missing.store.restore(A,backupId,snap.data,bad), "hash corruption fails before import");
        check(missing.points.snapshot().points.isEmpty(), "corrupt bytes never imported");
        JSONObject wrongOwner = new JSONObject(snap.data.toString()).put("owner_id",B);
        fails(()->missing.store.restore(A,backupId,wrongOwner,snap.photos), "wrong account manifest rejected");
        JSONObject wrongSchema = new JSONObject(snap.data.toString()).put("schema",2);
        fails(()->missing.store.restore(A,backupId,wrongSchema,snap.photos), "future format fails closed");

        Fixture privateSource = new Fixture(); SavedPoint privatePoint = source(privateSource);
        privateSource.identity.owners.put(privatePoint.id,A); privateSource.identity.signedIn=B;
        BackupLocalStore.Snapshot other = privateSource.store.capture(B);
        check(other.pointCount==0 && other.tripCount==0 && other.photoCount==0 && other.excludedForeign==3, "foreign point excludes linked trip and active track without leaking photos");
        target.identity.signedIn=B;
        BackupLocalStore.Snapshot otherAfterRestore = target.store.capture(B);
        check(otherAfterRestore.pointCount==0 && otherAfterRestore.tripCount==0 && otherAfterRestore.photoCount==0, "embedded restored identities prevent account switch adoption");
        fails(()->target.store.restore(A,backupId,snap.data,snap.photos), "account switch pauses A restore");

        Fixture outside = new Fixture(); SavedPoint outsidePoint = source(outside);
        File privateJson = new File(outside.dir,"auth.json"); Files.writeString(privateJson.toPath(),"private-placeholder");
        outside.points.edit(outsidePoint.id,p->p.withCatchDetails(p.catchDetails.withLocalPhotoPath(privateJson.getAbsolutePath())));
        fails(()->outside.store.capture(A), "tampered photo path cannot upload other private files");
        File fakePhoto = new File(outside.dir,"catch_photos/fake.jpg"); Files.writeString(fakePhoto.toPath(),"private-json-is-not-an-image");
        outside.points.edit(outsidePoint.id,p->p.withCatchDetails(p.catchDetails.withLocalPhotoPath(fakePhoto.getAbsolutePath())));
        fails(()->outside.store.capture(A), "non-image bytes in photo folder rejected");

        Fixture malformed = new Fixture(); source(malformed);
        malformed.tripPrefs.values.put("sessions","[{\"id\":1}, {\"id\":2}]");
        fails(()->malformed.store.capture(A), "malformed trip store fails instead of publishing partial backup");
        malformed.tripPrefs.values.put("sessions","[]"); malformed.trackPrefs.values.put("points","[{broken");
        fails(()->malformed.store.capture(A), "malformed active route fails closed");

        Fixture interrupted = new Fixture();
        interrupted.pointDisk.afterWrite=()->interrupted.backupDisk.failNext=true;
        fails(()->interrupted.store.restore(A,backupId,snap.data,snap.photos), "interruption after local insert before receipt reported");
        check(interrupted.points.snapshot().points.size()==1, "record survives journal-ack interruption");
        interrupted.identity.signedIn=B;
        BackupLocalStore.Snapshot gapPrivacy=interrupted.store.capture(B);
        check(gapPrivacy.pointCount==0 && gapPrivacy.tripCount==0 && gapPrivacy.photoCount==0, "embedded identities protect crash gap before ownership ACK");
        interrupted.identity.signedIn=A;
        interrupted.pointDisk.afterWrite=null; interrupted.backupDisk.failNext=false;
        check(interrupted.store.restore(A,backupId,snap.data,snap.photos).complete, "crash retry resumes safely");
        check(interrupted.points.snapshot().points.size()==1 && interrupted.trips.allForBackup().size()==2, "crash retry does not duplicate imports");

        Fixture deleted = new Fixture();
        deleted.store.restore(A,backupId,snap.data,snap.photos);
        long deletedId=deleted.points.snapshot().points.get(0).id; deleted.points.delete(deletedId);
        BackupLocalStore.RestoreResult deletion = deleted.store.restore(A,backupId,snap.data,snap.photos);
        check(!deletion.complete && deletion.conflicts==1 && deleted.points.snapshot().points.isEmpty(), "local deletion receipt is not resurrected");

        Fixture shared=new Fixture(); SavedPoint sharedFirst=source(shared);
        String sharedPath=sharedFirst.catchDetails.localPhotoPath;
        SavedPoint sharedSecond=shared.points.insert(new SavedPoint(4000,56.2,24.2,4000,"Catch","",null,
                sharedFirst.catchDetails,"Second image reference"));
        File photoRoot=new File(shared.dir,"catch_photos"); File sharedFile=new File(sharedPath);
        shared.points.edit(sharedFirst.id,p->p.withCatchDetails(p.catchDetails.withLocalPhotoPath("")));
        check(!shared.points.deleteUnreferencedPhoto(photoRoot,sharedPath) && sharedFile.isFile(), "removing first of two photo references retains original bytes");
        shared.pointDisk.failNext=true;
        fails(()->shared.points.edit(sharedSecond.id,p->p.withCatchDetails(p.catchDetails.withLocalPhotoPath(""))), "disk failure leaves photo metadata committed state unchanged");
        check(!shared.points.deleteUnreferencedPhoto(photoRoot,sharedPath) && sharedFile.isFile(), "failed metadata commit cannot cause photo deletion");
        shared.points.edit(sharedSecond.id,p->p.withCatchDetails(p.catchDetails.withLocalPhotoPath("")));
        check(shared.points.deleteUnreferencedPhoto(photoRoot,sharedPath) && !sharedFile.exists(), "last durable reference removal permits cleanup");
        File outsideFile=new File(shared.dir,"unrelated-private-file"); Files.writeString(outsideFile.toPath(),"private");
        check(!shared.points.deleteUnreferencedPhoto(photoRoot,outsideFile.getAbsolutePath()) && outsideFile.isFile(), "photo cleanup cannot delete an outside private file");

        Fixture racing=new Fixture(); SavedPoint racingSource=source(racing);
        String racingPath=racingSource.catchDetails.localPhotoPath;
        racing.points.edit(racingSource.id,p->p.withCatchDetails(p.catchDetails.withLocalPhotoPath("")));
        java.util.concurrent.CountDownLatch cleanupStarted=new java.util.concurrent.CountDownLatch(1);
        boolean[] cleanupResult={true};
        Thread cleaner=new Thread(()->{cleanupStarted.countDown();cleanupResult[0]=racing.points.deleteUnreferencedPhoto(new File(racing.dir,"catch_photos"),racingPath);});
        synchronized(racing.points) {
            cleaner.start(); cleanupStarted.await();
            racing.points.insert(new SavedPoint(5000,56.3,24.3,5000,"Catch","",null,racingSource.catchDetails,"Concurrent restored reference"));
        }
        cleaner.join();
        check(!cleanupResult[0] && new File(racingPath).isFile(), "cleanup and concurrent insertion use the same record lock");
        System.out.println("BackupLocalStore: "+checks+" checks passed");
    }
}
