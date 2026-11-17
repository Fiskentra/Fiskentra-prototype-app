import com.fiskentra.app.backend.BackupFiles;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Original bytes and corruption boundaries, independent of Android, Supabase and image decoders. */
public final class BackupFilesTest {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void fails(Action action, String label) throws Exception {
        try { action.run(); throw new AssertionError(label); }
        catch (AssertionError error) { throw error; }
        catch (Exception expected) { check(true, label); }
    }
    private static File write(File root, String name, byte[] bytes) throws Exception {
        File file = new File(root, name); Files.write(file.toPath(), bytes); return file;
    }
    private static JSONObject clone(JSONObject value) { return new JSONObject(value.toString()); }
    private static String h(String symbol) { return symbol.repeat(64); }
    private static JSONObject synthetic(long bytes, long chunkBytes, int count) {
        JSONArray chunks = new JSONArray();
        for (int i = 0; i < count; i++) chunks.put(new JSONObject().put("sha256", h("a")).put("bytes", chunkBytes));
        return new JSONObject().put("sha256", h("b")).put("bytes", bytes).put("chunks", chunks);
    }
    private static JSONObject manifest(JSONObject snapshot, JSONObject photo, Map<String, File> inventory) {
        JSONArray blobs = new JSONArray();
        for (Map.Entry<String, File> entry : inventory.entrySet()) blobs.put(new JSONObject().put("sha256", entry.getKey()).put("bytes", entry.getValue().length()));
        return new JSONObject().put("schema", 1).put("source_id", UUID.randomUUID().toString()).put("captured_at", 1)
                .put("device_label", "Test phone").put("snapshot", clone(snapshot)).put("photos", new JSONArray().put(clone(photo)))
                .put("counts", new JSONObject().put("points", 3).put("trips", 2).put("photos", 1)).put("blobs", blobs);
    }

    public static void main(String[] args) throws Exception {
        File parent = new File(args.length == 0 ? "build/backup-files-tests" : args[0]);
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Cannot create test output");
        File root = Files.createTempDirectory(parent.toPath(), "integrity-").toFile();
        binaryReconstruction(root); manifests(root); metadataRead(root); stagedAccountBinding(root);
        System.out.println("BackupFiles: " + checks + " checks PASS");
    }

    private static void binaryReconstruction(File root) throws Exception {
        byte[] block = new byte[BackupFiles.CHUNK_BYTES]; new Random(20261107L).nextBytes(block);
        byte[] exif = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe1, 0, 22, 'E', 'x', 'i', 'f', 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0};
        System.arraycopy(exif, 0, block, 0, exif.length);
        for (int i = 0; i < 256; i++) block[128 + i] = (byte) i;
        byte[] original = new byte[block.length * 2 + 513];
        System.arraycopy(block, 0, original, 0, block.length); System.arraycopy(block, 0, original, block.length, block.length);
        for (int i = block.length * 2; i < original.length; i++) original[i] = (byte) (i % 251);
        File photo = write(root, "original-camera-bytes.jpg", original), chunks = new File(root, "chunks");
        Map<String, File> inventory = new LinkedHashMap<>();
        String sha = BackupFiles.hash(original); JSONObject descriptor = BackupFiles.chunk(photo, sha, chunks, inventory);
        check(BackupFiles.hash(photo).equals(sha) && descriptor.getString("sha256").equals(sha), "file and byte-array SHA agree for unmodified original camera bytes");
        check(descriptor.getLong("bytes") == original.length && descriptor.getJSONArray("chunks").length() == 3, "two full 4 MiB chunks and exact tail are preserved");
        check(descriptor.getJSONArray("chunks").getJSONObject(0).getLong("bytes") == BackupFiles.CHUNK_BYTES
                && descriptor.getJSONArray("chunks").getJSONObject(2).getLong("bytes") == 513, "chunk boundaries do not pad or truncate the tail");
        check(inventory.size() == 2 && descriptor.getJSONArray("chunks").getJSONObject(0).getString("sha256")
                .equals(descriptor.getJSONArray("chunks").getJSONObject(1).getString("sha256")), "identical chunks deduplicate without removing repeated bytes from the file");
        for (Map.Entry<String, File> entry : inventory.entrySet()) BackupFiles.verify(entry.getValue(), entry.getKey(), entry.getValue().length());
        File restored = new File(root, "restored-original.jpg");
        BackupFiles.assemble(descriptor, restored, (hash, bytes) -> inventory.get(hash));
        check(Arrays.equals(original, Files.readAllBytes(restored.toPath())), "reconstruction preserves every binary byte, EXIF header and byte values 0..255");
        check(BackupFiles.hash(restored).equals(sha) && Files.size(restored.toPath()) == original.length, "restored original has the exact size and SHA-256");
        final int[] calls = {0};
        BackupFiles.assemble(descriptor, restored, (hash, bytes) -> { calls[0]++; throw new Exception("network must not be used"); });
        check(calls[0] == 0, "a previously verified destination is reused without redownloading");

        byte[] old = "keep the existing destination".getBytes(StandardCharsets.UTF_8);
        File destination = write(root, "preserved.bin", old);
        fails(() -> BackupFiles.assemble(descriptor, destination, (hash, bytes) -> null), "a missing blob fails before publishing reconstructed data");
        check(Arrays.equals(old, Files.readAllBytes(destination.toPath())) && !new File(root, "preserved.bin.downloading").exists(), "missing-blob failure leaves the old destination and removes the partial file");
        File corrupt = write(root, "corrupt-blob", new byte[BackupFiles.CHUNK_BYTES]);
        fails(() -> BackupFiles.assemble(descriptor, destination, (hash, bytes) -> corrupt), "a corrupt blob of the correct size cannot pass checksum verification");
        check(Arrays.equals(old, Files.readAllBytes(destination.toPath())), "corrupt download does not replace existing data");
        int[] downloaded = {0};
        fails(() -> BackupFiles.assemble(descriptor, destination, (hash, bytes) -> {
            if (++downloaded[0] == 3) throw new Exception("simulated interrupted last-chunk request");
            return inventory.get(hash);
        }), "interruption after full chunks cannot publish a truncated photo");
        check(downloaded[0] == 3 && Arrays.equals(old, Files.readAllBytes(destination.toPath())), "mid-download failure preserves the original destination after writing a real partial file");

        JSONObject reordered = clone(descriptor); JSONArray sourceChunks = descriptor.getJSONArray("chunks");
        reordered.put("chunks", new JSONArray().put(sourceChunks.getJSONObject(2)).put(sourceChunks.getJSONObject(1)).put(sourceChunks.getJSONObject(0)));
        fails(() -> BackupFiles.assemble(reordered, destination, (hash, bytes) -> inventory.get(hash)), "valid individual hashes in the wrong order fail the whole-file SHA");
        check(Arrays.equals(old, Files.readAllBytes(destination.toPath())), "reordered chunk data cannot replace existing content");
        JSONObject wrongWhole = clone(descriptor).put("sha256", h("0"));
        fails(() -> BackupFiles.assemble(wrongWhole, destination, (hash, bytes) -> inventory.get(hash)), "whole-file integrity is checked independently of each chunk");
        check(Arrays.equals(old, Files.readAllBytes(destination.toPath())), "whole-file checksum failure retains old content");
        JSONObject wrongLength = clone(descriptor).put("bytes", original.length - 1L);
        fails(() -> BackupFiles.assemble(wrongLength, destination, (hash, bytes) -> inventory.get(hash)), "descriptor length inconsistent with chunks is rejected");

        byte[] changed = Arrays.copyOf(original, original.length); changed[123] ^= 1; File changedSource = write(root, "changed-source.jpg", changed);
        fails(() -> BackupFiles.chunk(changedSource, sha, new File(root, "changed-chunks"), new LinkedHashMap<>()), "source changes relative to the captured expected hash cannot be acknowledged");
        check(Arrays.equals(changed, Files.readAllBytes(changedSource.toPath())), "failed source consistency check never rewrites the user's photo");
        File small = write(root, "small-camera.bin", new byte[]{0, 1, (byte) 255, (byte) 128});
        Map<String, File> smallInventory = new LinkedHashMap<>();
        JSONObject smallDescriptor = BackupFiles.chunk(small, BackupFiles.hash(small), new File(root, "small-chunks"), smallInventory);
        File smallBlob = smallInventory.values().iterator().next(); Files.write(smallBlob.toPath(), new byte[]{3, 3, 3, 3});
        BackupFiles.chunk(small, BackupFiles.hash(small), smallBlob.getParentFile(), smallInventory);
        BackupFiles.verify(smallBlob, smallDescriptor.getJSONArray("chunks").getJSONObject(0).getString("sha256"), 4);
        check(Arrays.equals(Files.readAllBytes(small.toPath()), Files.readAllBytes(smallBlob.toPath())), "corrupt local staged chunks are reconstructed from the unchanged original");
        File empty = write(root, "empty-photo.jpg", new byte[0]);
        fails(() -> BackupFiles.chunk(empty, BackupFiles.hash(empty), chunks, inventory), "zero-byte source files cannot produce a false complete backup");
        fails(() -> BackupFiles.verify(photo, sha.toUpperCase(), original.length), "noncanonical hash identifiers are rejected");
        fails(() -> BackupFiles.verify(photo, sha, original.length + 1L), "wrong original length is rejected even with its correct hash");
    }

    private static void manifests(File root) throws Exception {
        Map<String, File> inventory = new LinkedHashMap<>(); File chunks = new File(root, "manifest-chunks");
        File snapshotFile = write(root, "snapshot.json", "{\"schema\":1,\"points\":[],\"trips\":[]}".getBytes(StandardCharsets.UTF_8));
        File photoFile = write(root, "photo.bin", new byte[]{(byte) 255, (byte) 216, 0, 1, 2, 3, 4, (byte) 217});
        JSONObject snapshot = BackupFiles.chunk(snapshotFile, BackupFiles.hash(snapshotFile), chunks, inventory);
        JSONObject photo = BackupFiles.chunk(photoFile, BackupFiles.hash(photoFile), chunks, inventory);
        JSONObject valid = manifest(snapshot, photo, inventory); BackupFiles.validateManifest(valid);
        check(true, "a manifest with exactly the referenced original and snapshot chunks is accepted");
        JSONObject missing = clone(valid); missing.getJSONArray("blobs").remove(0);
        fails(() -> BackupFiles.validateManifest(missing), "manifest missing a referenced blob is rejected before download");
        JSONObject extra = clone(valid); extra.getJSONArray("blobs").put(new JSONObject().put("sha256", h("d")).put("bytes", 1));
        fails(() -> BackupFiles.validateManifest(extra), "extra unreferenced blobs cannot hide inside the inventory");
        JSONObject duplicateBlob = clone(valid); duplicateBlob.getJSONArray("blobs").put(clone(duplicateBlob.getJSONArray("blobs").getJSONObject(0)));
        fails(() -> BackupFiles.validateManifest(duplicateBlob), "duplicate blob declarations are rejected");
        JSONObject badLength = clone(valid); badLength.getJSONArray("blobs").getJSONObject(0).put("bytes", 3);
        fails(() -> BackupFiles.validateManifest(badLength), "declared inventory lengths must agree with the actual descriptor references");
        JSONObject invalidHash = clone(valid); invalidHash.getJSONArray("photos").getJSONObject(0).put("sha256", "../../../private");
        fails(() -> BackupFiles.validateManifest(invalidHash), "untrusted hashes cannot be used as paths");
        JSONObject badChunkHash = clone(valid); badChunkHash.getJSONObject("snapshot").getJSONArray("chunks").getJSONObject(0).put("sha256", "invalid");
        fails(() -> BackupFiles.validateManifest(badChunkHash), "malformed chunk hashes are rejected before transport");
        JSONObject badCount = clone(valid); badCount.getJSONObject("counts").put("photos", 0);
        fails(() -> BackupFiles.validateManifest(badCount), "photo summary cannot claim a different descriptor count");
        JSONObject negativeCount = clone(valid); negativeCount.getJSONObject("counts").put("trips", -1);
        fails(() -> BackupFiles.validateManifest(negativeCount), "negative trip counts are rejected");
        JSONObject duplicatePhoto = clone(valid); duplicatePhoto.getJSONArray("photos").put(clone(photo)); duplicatePhoto.getJSONObject("counts").put("photos", 2);
        fails(() -> BackupFiles.validateManifest(duplicatePhoto), "one original photo hash cannot be ambiguously described twice");
        JSONObject wrongSchema = clone(valid).put("schema", 2);
        fails(() -> BackupFiles.validateManifest(wrongSchema), "future manifest schemas are not silently interpreted as the current one");
        JSONObject badSource = clone(valid).put("source_id", "not-an-install-uuid");
        fails(() -> BackupFiles.validateManifest(badSource), "invalid source identity is rejected");
        JSONObject badTime = clone(valid).put("captured_at", 0);
        fails(() -> BackupFiles.validateManifest(badTime), "missing capture time cannot be advertised as a complete backup");
        JSONObject longLabel = clone(valid).put("device_label", "x".repeat(129));
        fails(() -> BackupFiles.validateManifest(longLabel), "overlong device label is rejected");
        JSONObject huge = clone(valid).put("untrusted_extra", "x".repeat(BackupFiles.MAX_MANIFEST_BYTES));
        fails(() -> BackupFiles.validateManifest(huge), "oversized metadata is bounded without allocating a giant file");
        JSONObject hugeSnapshot = clone(valid).put("snapshot", synthetic(17L * BackupFiles.CHUNK_BYTES, BackupFiles.CHUNK_BYTES, 17));
        fails(() -> BackupFiles.validateManifest(hugeSnapshot), "snapshot descriptors above 64 MiB are rejected without reading that many bytes");
        JSONObject hugeFile = clone(valid); hugeFile.getJSONArray("photos").getJSONObject(0).put("bytes", BackupFiles.MAX_FILE_BYTES + 1);
        fails(() -> BackupFiles.validateManifest(hugeFile), "one file cannot exceed the 2 GiB restoration bound");
        JSONObject emptyChunks = clone(valid); emptyChunks.getJSONObject("snapshot").put("chunks", new JSONArray());
        fails(() -> BackupFiles.validateManifest(emptyChunks), "empty chunk lists cannot describe a nonempty snapshot");
        JSONObject hugeChunk = clone(valid); hugeChunk.getJSONObject("snapshot").getJSONArray("chunks").getJSONObject(0).put("bytes", BackupFiles.CHUNK_BYTES + 1);
        fails(() -> BackupFiles.validateManifest(hugeChunk), "chunks above the transport's 4 MiB bound are rejected");
        JSONObject conflictLengths = clone(valid); conflictLengths.getJSONArray("photos").getJSONObject(0).getJSONArray("chunks").getJSONObject(0)
                .put("sha256", snapshot.getJSONArray("chunks").getJSONObject(0).getString("sha256"));
        fails(() -> BackupFiles.validateManifest(conflictLengths), "the same content hash cannot claim conflicting sizes across descriptors");
        JSONObject lengthMismatch = clone(valid); lengthMismatch.getJSONObject("snapshot").put("bytes", snapshot.getLong("bytes") + 1);
        fails(() -> BackupFiles.validateManifest(lengthMismatch), "descriptor total length must equal the complete ordered chunk lengths");
        JSONObject bigTotal = clone(valid); JSONArray manyPhotos = new JSONArray();
        for (int i = 0; i < 33; i++) {
            JSONObject item = synthetic(BackupFiles.MAX_FILE_BYTES, BackupFiles.CHUNK_BYTES, 512);
            String unique = String.format("%064x", i + 100); item.put("sha256", unique); manyPhotos.put(item);
        }
        bigTotal.put("photos", manyPhotos); bigTotal.getJSONObject("counts").put("photos", manyPhotos.length());
        fails(() -> BackupFiles.validateManifest(bigTotal), "aggregate size above 64 GiB is rejected using only small metadata fixtures");
    }

    private static void metadataRead(File root) throws Exception {
        byte[] utf8 = "{\"note\":\"Лодка — foto å ☃\"}".getBytes(StandardCharsets.UTF_8);
        File file = new File(root, "metadata.json"); BackupFiles.write(file, utf8);
        check(Arrays.equals(utf8, Files.readAllBytes(file.toPath())) && BackupFiles.readJson(file, 1024).getString("note").equals("Лодка — foto å ☃"),
                "UTF-8 metadata round-trips without charset conversion or byte changes");
        fails(() -> BackupFiles.readJson(file, utf8.length - 1), "metadata reader enforces its byte limit");
        File malformed = write(root, "malformed-utf8.json", new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xc3, 0x28, '"', '}'});
        fails(() -> BackupFiles.readJson(malformed, 1024), "malformed UTF-8 is rejected rather than silently replacing characters");
        File invalidJson = write(root, "invalid-json", "{bad".getBytes(StandardCharsets.UTF_8));
        fails(() -> BackupFiles.readJson(invalidJson, 1024), "truncated JSON cannot be restored");
        File empty = write(root, "empty-json", new byte[0]); fails(() -> BackupFiles.readJson(empty, 1024), "empty metadata cannot masquerade as an empty valid backup");
        byte[] changed = "{\"note\":\"second\"}".getBytes(StandardCharsets.UTF_8); BackupFiles.write(file, changed);
        check(Arrays.equals(changed, Files.readAllBytes(file.toPath())) && !new File(root, "metadata.json.writing").exists(), "atomic replacement publishes complete metadata and removes its temporary file");
    }

    private static void stagedAccountBinding(File root) throws Exception {
        String ownerA = "11111111-1111-4111-8111-111111111111", ownerB = "22222222-2222-4222-8222-222222222222";
        JSONObject data = new JSONObject().put("schema", 1).put("owner_id", ownerA).put("points", new JSONArray())
                .put("trips", new JSONArray()).put("photos", new JSONArray());
        File snapshotFile = write(root, "account-bound-snapshot.json", data.toString().getBytes(StandardCharsets.UTF_8));
        Map<String, File> inventory = new LinkedHashMap<>();
        JSONObject snapshot = BackupFiles.chunk(snapshotFile, BackupFiles.hash(snapshotFile), new File(root, "account-bound-chunks"), inventory);
        JSONArray blobs = new JSONArray();
        for (Map.Entry<String, File> entry : inventory.entrySet()) blobs.put(new JSONObject().put("sha256", entry.getKey()).put("bytes", entry.getValue().length()));
        JSONObject manifest = new JSONObject().put("schema", 1).put("source_id", UUID.randomUUID().toString()).put("captured_at", 1)
                .put("snapshot", snapshot).put("photos", new JSONArray()).put("blobs", blobs)
                .put("counts", new JSONObject().put("points", 0).put("trips", 0).put("photos", 0));
        JSONObject verified = BackupFiles.verifiedSnapshot(snapshotFile, manifest, ownerA);
        check(verified.getString("owner_id").equals(ownerA) && verified.getJSONArray("points").length() == 0,
                "correctly staged snapshot returns its verified data for the signed-in account");
        fails(() -> BackupFiles.verifiedSnapshot(snapshotFile, manifest, ownerB),
                "valid hashes and a shared per-install actor cannot authorize another account's staged snapshot");
        File corrupt = write(root, "account-bound-corrupt.json", data.toString().replace(ownerA, ownerB).getBytes(StandardCharsets.UTF_8));
        fails(() -> BackupFiles.verifiedSnapshot(corrupt, manifest, ownerB),
                "changing the embedded owner without changing the snapshot descriptor fails its full-file checksum");
        fails(() -> BackupFiles.verifiedSnapshot(new File(root, "missing-account-snapshot.json"), manifest, ownerA),
                "missing staged data cannot be sent using only its manifest");
        JSONObject incomplete = clone(manifest); incomplete.getJSONArray("blobs").remove(0);
        fails(() -> BackupFiles.verifiedSnapshot(snapshotFile, incomplete, ownerA),
                "retry also revalidates complete manifest inventory before accepting the staged account data");
        fails(() -> BackupFiles.verifiedSnapshot(snapshotFile, manifest, ""),
                "an absent account cannot match an untrusted staged owner value");
        check(BackupFiles.hash(snapshotFile).equals(snapshot.getString("sha256")),
                "rejected retries never rewrite the private snapshot");
    }
}
