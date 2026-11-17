import com.fiskentra.app.backend.BackendProjectSwitch;
import com.fiskentra.app.data.PointLedger;
import com.fiskentra.app.model.SavedPoint;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BackendProjectSwitchTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("backend-switch-");
        Path file = root.resolve("points.json");
        try {
            PointLedger ledger = new PointLedger(new PointLedger.AtomicDisk(file.toFile()), "[]", "[]", "{}");
            SavedPoint live = ledger.insert(new SavedPoint(1, 12, 34, 1, "Catch", "keep"));
            PointLedger.Operation upload = ledger.claim(0);
            ledger.finish(upload.operationId, true, "", "", 0);
            SavedPoint gone = ledger.insert(new SavedPoint(2, 12, 34, 2, "Catch", "deleted"));
            upload = ledger.claim(0); ledger.finish(upload.operationId, true, "", "", 0);
            ledger.delete(gone.id);
            BackendProjectSwitch.requeue(ledger);
            check(ledger.find(gone.id) == null && ledger.isDeleted(gone.id));
            check(ledger.find(live.id).note.equals("keep") && ledger.find(live.id).timestamp == 1);
            check(ledger.pendingCount() == 1);
            ledger = new PointLedger(new PointLedger.AtomicDisk(file.toFile()), "[]", "[]", "{}");
            BackendProjectSwitch.requeue(ledger); // Interrupted migration is safe to repeat.
            check(ledger.pendingCount() == 1);
            PointLedger.Operation next = ledger.claim(0);
            check(next.kind.equals("upsert") && next.pointId == live.id);
            System.out.println("PASS backend switch: metadata, tombstones, reopen and retry");
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(root); }
    }
}
