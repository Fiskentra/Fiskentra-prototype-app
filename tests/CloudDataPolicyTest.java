import com.fiskentra.app.backend.CloudDataPolicy;
import com.fiskentra.app.data.PointLedger;
import com.fiskentra.app.model.CatchDetails;
import com.fiskentra.app.model.SavedPoint;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Account sync never enables the legacy device API or consumes its retained outbox. */
public final class CloudDataPolicyTest {
    private static int assertions;
    private static int opened;
    private static void check(boolean ok, String label) {
        assertions++;
        if (!ok) throw new AssertionError(label);
    }
    private static PointLedger open(Path file) {
        return new PointLedger(new PointLedger.AtomicDisk(file.toFile()), "[]", "[]", "{}");
    }
    private static SavedPoint point(String event, long timestamp) {
        return new SavedPoint(timestamp, 56.1, 24.2, timestamp, "Catch", "note", null,
                new CatchDetails("Pike", 62, 2.4, "spoon", "notes", true, "/private/photo.jpg"),
                "My place", "fish", 0xFF33AA55, 22, event, 77, true, 1,
                "LOCATED", timestamp, 5, "gps");
    }
    public static void main(String[] args) throws Exception {
        check(!CloudDataPolicy.isLocalOnlyBeta() && CloudDataPolicy.accountSyncAvailable(), "authenticated account sync is available");
        check(!CloudDataPolicy.allowsPersonalDataRequests(), "credentials/sign-in cannot enable legacy anonymous transfers");
        URLStreamHandler spy = new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                opened++;
                return new HttpURLConnection(url) {
                    public void connect() { }
                    public void disconnect() { }
                    public boolean usingProxy() { return false; }
                };
            }
        };
        for (String operation : new String[] {"read", "insert", "update", "delete", "photo-upload", "photo-read", "photo-delete"}) {
            URL url = new URL(null, "https://example.invalid/" + operation, spy);
            boolean blocked = false;
            try { CloudDataPolicy.openConnection(url); }
            catch (IOException expected) { blocked = true; }
            check(blocked, operation + " denied before opening a connection");
        }
        check(opened == 0, "no personal-data URL handler was invoked");

        String owner = "11111111-1111-4111-8111-111111111111";
        String other = "22222222-2222-4222-8222-222222222222";
        String host = com.fiskentra.app.backend.SupabaseConfig.PROJECT_REF + ".supabase.co";
        String sha = "a".repeat(64);
        for (String path : new String[]{"/rest/v1/account_points", "/rest/v1/rpc/sync_account_point",
                "/rest/v1/account_backups", "/rest/v1/rpc/commit_account_backup",
                "/storage/v1/bucket/fiskentra-backups-v1", "/storage/v1/object/fiskentra-backups-v1/" + owner + "/" + sha,
                "/storage/v1/object/authenticated/fiskentra-backups-v1/" + owner + "/" + sha}) {
            int beforeOpened = opened;
            CloudDataPolicy.openAccountConnection(new URL(null, "https://" + host + path, spy), owner, "fixture-bearer");
            check(opened == beforeOpened + 1, "approved account backup endpoint opens exactly once: " + path);
        }
        for (String unsafe : new String[]{"http://" + host + "/rest/v1/account_backups",
                "https://other.invalid/rest/v1/account_backups", "https://user@" + host + "/rest/v1/account_backups",
                "https://" + host + ":444/rest/v1/account_backups", "https://" + host + "/rest/v1/saved_points",
                "https://" + host + "/storage/v1/object/public/fiskentra-backups-v1/" + owner + "/" + sha,
                "https://" + host + "/storage/v1/object/fiskentra-backups-v1/" + other + "/" + sha,
                "https://" + host + "/storage/v1/object/fiskentra-backups-v1/" + owner + "/../" + sha,
                "https://" + host + "/storage/v1/object/fiskentra-backups-v1/" + owner + "/" + sha + "?token=fixture",
                "https://" + host + "/storage/v1/object/fiskentra-backups-v1/" + owner + "/not-a-hash",
                "https://" + host + "/rest/v1/account_backups#fragment"}) {
            int beforeOpened = opened; boolean blocked = false;
            try { CloudDataPolicy.openAccountConnection(new URL(null, unsafe, spy), owner, "fixture-bearer"); }
            catch (IOException expected) { blocked = true; }
            check(blocked && opened == beforeOpened, "unsafe project, owner or object path rejected before HTTP");
        }

        Path root = Files.createTempDirectory("fiskentra-local-beta-test-");
        try {
            Path file = root.resolve("records.json");
            PointLedger ledger = open(file);
            SavedPoint previous = ledger.insert(point("previous-cloud", 1000));
            PointLedger.Operation upload = ledger.claim(0);
            ledger.finish(upload.operationId, true, "", "previous version uploaded", 0);
            ledger.delete(previous.id); // An old server copy may remain: preserve deletion intent.
            SavedPoint local = ledger.insert(point("new-local", 2000));
            ledger.edit(local.id, p -> p.withMetadata("Renamed", p.type, "edited locally"));
            String before = Files.readString(file);
            int pending = ledger.pendingCount();
            check(pending == 2, "old remote delete and new local upsert are retained");
            for (int i = 0; i < 200; i++) {
                check(CloudDataPolicy.claimForTransfer(ledger, Long.MAX_VALUE) == null,
                        "manual/worker/network retry cannot claim an operation");
            }
            check(before.equals(Files.readString(file)), "disabled transfer never changes receipts or operations");
            ledger = open(file);
            check(ledger.pendingCount() == pending, "outbox survives process restart");
            check(ledger.isDeleted(previous.id) && ledger.find(previous.id) == null,
                    "old cloud point stays locally deleted with its tombstone");
            SavedPoint restored = ledger.find(local.id);
            check("Renamed".equals(restored.title) && "edited locally".equals(restored.note),
                    "local edits survive restart");
            check(restored.favorite && restored.tripId == 77 && restored.eventId.equals("new-local"),
                    "point ownership-independent metadata is retained");
            check(restored.catchDetails.localPhotoPath.equals("/private/photo.jpg"), "photo reference is retained");
            ledger.delete(local.id);
            ledger = open(file);
            check(ledger.find(local.id) == null && ledger.isDeleted(local.id),
                    "never-uploaded local delete survives restart");
            check(ledger.pendingCount() == 1, "unsent upsert is superseded; old server deletion intent remains");
            check(CloudDataPolicy.claimForTransfer(ledger, Long.MAX_VALUE) == null,
                    "restart and local deletion still cannot trigger transfer");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException error) { throw new RuntimeException(error); }
                });
            }
        }
        System.out.println("CloudDataPolicyTest: " + assertions + " checks passed; opened connections=" + opened);
    }
}
