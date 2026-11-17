package com.fiskentra.app.backend;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Original bytes, immutable content-addressed chunks, and verified reconstruction. */
public final class BackupFiles {
    public static final int CHUNK_BYTES = 4 * 1024 * 1024;
    public static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    public static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;
    public static final long MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024;
    public static final long MAX_BACKUP_BYTES = 64L * 1024 * 1024 * 1024;
    private BackupFiles() { }

    public interface ChunkSource { File get(String sha256, long bytes) throws Exception; }

    public static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }
    public static String hash(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(64);
        for (byte value : bytes) text.append(Character.forDigit((value >> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return text.toString();
    }
    public static boolean validHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    public static void verify(File file, String sha256, long bytes) throws Exception {
        if (!validHash(sha256) || bytes <= 0 || bytes > MAX_FILE_BYTES || !file.isFile()
                || file.length() != bytes || !sha256.equals(hash(file)))
            throw new IOException("Backup file is missing or damaged; local data was retained");
    }
    public static void write(File file, byte[] bytes) throws Exception {
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("Backup storage is unavailable");
        File temporary = new File(parent, file.getName() + ".writing");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(bytes); output.getFD().sync(); }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit(); }
    }
    public static JSONObject readJson(File file, int limit) throws Exception {
        if (file.length() <= 0 || file.length() > limit) throw new IOException("Backup metadata exceeds the supported size");
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("Backup metadata exceeds the supported size");
                output.write(buffer, 0, count);
            }
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
            return new JSONObject(text);
        }
    }

    /** Re-check staged bytes and their embedded account before a retry can upload any object. */
    public static JSONObject verifiedSnapshot(File file, JSONObject manifest, String owner) throws Exception {
        validateManifest(manifest);
        if (owner == null || !java.util.UUID.fromString(owner).toString().equals(owner))
            throw new IOException("Invalid backup account identity");
        JSONObject descriptor = manifest.getJSONObject("snapshot");
        verify(file, descriptor.getString("sha256"), descriptor.getLong("bytes"));
        JSONObject data = readJson(file, MAX_SNAPSHOT_BYTES);
        if (data.getInt("schema") != 1 || !owner.equals(data.getString("owner_id")))
            throw new IOException("Staged backup belongs to another account or unsupported format; originals retained");
        return data;
    }

    /** The source is read exactly once; source changes cannot create an acknowledged mixed file. */
    public static JSONObject chunk(File source, String expectedHash, File directory, Map<String, File> inventory) throws Exception {
        if (!validHash(expectedHash) || source.length() <= 0 || source.length() > MAX_FILE_BYTES)
            throw new IOException("Photo or route file cannot be backed up; original retained");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Backup staging space is unavailable");
        MessageDigest whole = MessageDigest.getInstance("SHA-256");
        JSONArray chunks = new JSONArray(); long total = 0;
        try (InputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[CHUNK_BYTES]; boolean ended = false;
            while (!ended) {
                int size = 0;
                while (size < buffer.length) { int read = input.read(buffer, size, buffer.length - size); if (read == -1) { ended = true; break; } size += read; }
                if (size == 0) break;
                total += size;
                if (total > MAX_FILE_BYTES) throw new IOException("Backup file exceeds the supported size");
                whole.update(buffer, 0, size);
                MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(buffer, 0, size);
                String sha = hex(digest.digest()); File blob = new File(directory, sha);
                if (!blob.isFile() || blob.length() != size || !sha.equals(hash(blob))) {
                    byte[] part = size == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, size);
                    write(blob, part);
                }
                inventory.put(sha, blob); chunks.put(new JSONObject().put("sha256", sha).put("bytes", size));
            }
        }
        if (total <= 0 || !expectedHash.equals(hex(whole.digest())))
            throw new IOException("A file changed during backup; retry queued and original retained");
        return new JSONObject().put("sha256", expectedHash).put("bytes", total).put("chunks", chunks);
    }

    public static void assemble(JSONObject descriptor, File destination, ChunkSource source) throws Exception {
        Map<String, Long> referenced = new HashMap<>(); validateDescriptor(descriptor, referenced);
        String expected = descriptor.getString("sha256"); long expectedBytes = descriptor.getLong("bytes");
        if (destination.isFile() && destination.length() == expectedBytes && expected.equals(hash(destination))) return;
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("Restore storage is unavailable");
        File partial = new File(parent, destination.getName() + ".downloading");
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); long written = 0;
        try {
            try (FileOutputStream output = new FileOutputStream(partial)) {
                JSONArray chunks = descriptor.getJSONArray("chunks"); byte[] buffer = new byte[128 * 1024];
                for (int i = 0; i < chunks.length(); i++) {
                    JSONObject chunk = chunks.getJSONObject(i); String sha = chunk.getString("sha256"); long size = chunk.getLong("bytes");
                    File file = source.get(sha, size); verify(file, sha, size);
                    try (InputStream input = new FileInputStream(file)) {
                        int count; while ((count = input.read(buffer)) != -1) { written += count; if (written > expectedBytes) throw new IOException("Invalid backup size"); output.write(buffer, 0, count); digest.update(buffer, 0, count); }
                    }
                }
                output.getFD().sync();
            }
            if (written != expectedBytes || !expected.equals(hex(digest.digest()))) throw new IOException("Restored file checksum failed; original data retained");
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { if (partial.exists() && !partial.delete()) partial.deleteOnExit(); }
    }

    public static void validateManifest(JSONObject manifest) throws Exception {
        if (manifest.toString().getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES || manifest.getInt("schema") != 1)
            throw new IOException("Unsupported backup manifest");
        java.util.UUID.fromString(manifest.getString("source_id"));
        if (manifest.getLong("captured_at") <= 0 || manifest.optString("device_label").length() > 128) throw new IOException("Invalid backup metadata");
        Map<String, Long> referenced = new HashMap<>();
        JSONObject snapshot = manifest.getJSONObject("snapshot"); validateDescriptor(snapshot, referenced);
        if (snapshot.getLong("bytes") > MAX_SNAPSHOT_BYTES) throw new IOException("Backup snapshot exceeds the supported size");
        JSONArray photos = manifest.getJSONArray("photos"); Set<String> originals = new HashSet<>(); long total = snapshot.getLong("bytes");
        for (int i = 0; i < photos.length(); i++) {
            JSONObject photo = photos.getJSONObject(i); validateDescriptor(photo, referenced);
            if (!originals.add(photo.getString("sha256"))) throw new IOException("Duplicate photo descriptor");
            total += photo.getLong("bytes"); if (total > MAX_BACKUP_BYTES) throw new IOException("Backup exceeds the supported size");
        }
        JSONObject counts = manifest.getJSONObject("counts");
        if (counts.getInt("points") < 0 || counts.getInt("trips") < 0 || counts.getInt("photos") != photos.length()) throw new IOException("Invalid backup counts");
        JSONArray blobs = manifest.getJSONArray("blobs"); Map<String, Long> declared = new HashMap<>();
        for (int i = 0; i < blobs.length(); i++) {
            JSONObject blob = blobs.getJSONObject(i); String sha = blob.getString("sha256"); long bytes = blob.getLong("bytes");
            if (!validHash(sha) || bytes <= 0 || bytes > CHUNK_BYTES || declared.put(sha, bytes) != null) throw new IOException("Invalid backup inventory");
        }
        if (!declared.equals(referenced)) throw new IOException("Incomplete backup inventory; no data was restored");
    }
    private static void validateDescriptor(JSONObject descriptor, Map<String, Long> referenced) throws Exception {
        long size = descriptor.getLong("bytes");
        if (!validHash(descriptor.getString("sha256")) || size <= 0 || size > MAX_FILE_BYTES) throw new IOException("Invalid backup file descriptor");
        JSONArray chunks = descriptor.getJSONArray("chunks"); long sum = 0;
        if (chunks.length() == 0 || chunks.length() > MAX_FILE_BYTES / CHUNK_BYTES + 1) throw new IOException("Invalid backup chunk count");
        for (int i = 0; i < chunks.length(); i++) {
            JSONObject chunk = chunks.getJSONObject(i); String sha = chunk.getString("sha256"); long bytes = chunk.getLong("bytes");
            if (!validHash(sha) || bytes <= 0 || bytes > CHUNK_BYTES) throw new IOException("Invalid backup chunk");
            Long previous = referenced.put(sha, bytes);
            if (previous != null && previous != bytes) throw new IOException("Conflicting backup chunk lengths");
            sum += bytes;
        }
        if (sum != size) throw new IOException("Backup chunk lengths do not match");
    }
}
