package com.fiskentra.app.backend;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

/** Immutable private account backups. Original photo bytes are never transformed. */
public final class AccountBackupTransport {
    public static final String BUCKET = "fiskentra-backups-v1";
    public static final long MAX_BLOB_BYTES = 4L * 1024L * 1024L;
    public static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 64 * 1024;
    private final SupabaseAuthManager auth;

    public static final class Failure extends IOException {
        public final String category;
        public final int httpStatus;
        public final String serverCode;
        public Failure(String category, String message) { this(category, message, 0, ""); }
        private Failure(String category, String message, int httpStatus, String serverCode) {
            super(message); this.category = category; this.httpStatus = httpStatus; this.serverCode = serverCode;
        }
    }

    public AccountBackupTransport(SupabaseAuthManager auth) { this.auth = auth; }

    /** Read the live bucket configuration; a public or unlimited bucket is never accepted. */
    public void verifyBucket(String owner) throws Exception {
        JSONObject value = object(json(owner, "GET", "/storage/v1/bucket/" + BUCKET, null));
        long limit = value.optLong("file_size_limit", -1);
        if (!BUCKET.equals(value.optString("id")) || !(value.opt("public") instanceof Boolean) || value.optBoolean("public", true)
                || limit <= 0 || limit > MAX_BLOB_BYTES)
            throw new Failure("configuration", "Private backup storage is not configured safely; local data is retained");
    }

    /** Upload one content-addressed chunk, then verify its full private download before acknowledging it. */
    public void putBlob(String owner, String sha256, File file) throws Exception {
        validateHash(sha256); validateOwner(owner);
        long size = checkedSize(file);
        if (!sha256.equals(hash(file))) throw new Failure("integrity", "Local backup chunk changed; please retry the backup");
        verifyBucket(owner);
        String path = "/storage/v1/object/" + BUCKET + "/" + owner + "/" + sha256;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = connection(owner, "POST", path, attempt > 0);
            try {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/octet-stream");
                connection.setRequestProperty("cache-control", "max-age=0, private, no-store");
                connection.setRequestProperty("x-upsert", "false");
                connection.setFixedLengthStreamingMode(size);
                MessageDigest sent = digest();
                long count;
                try (InputStream input = new FileInputStream(file); OutputStream output = connection.getOutputStream()) {
                    count = transfer(input, output, sent, MAX_BLOB_BYTES);
                }
                if (count != size || !sha256.equals(hex(sent.digest())))
                    throw new Failure("integrity", "Local backup chunk changed during upload; local files are retained");
                int status = connection.getResponseCode();
                String text = readText(status >= 200 && status < 300 ? connection.getInputStream()
                        : connection.getErrorStream(), MAX_ERROR_BYTES);
                if (status >= 200 && status < 300 || alreadyExists(status, text)) {
                    verifyRemoteBlob(owner, sha256, size);
                    return;
                }
                if (status == 401 && attempt == 0) continue;
                throw failure(status, text);
            } catch (Failure error) { throw error; }
            catch (IOException error) { throw new Failure("failed", "Backup upload was interrupted; local files and completed chunks are retained"); }
            finally { connection.disconnect(); }
        }
        throw new Failure("auth", "Sign in again to continue the backup");
    }

    /** Download to a new staged file. Existing different files are never replaced. */
    public void getBlob(String owner, String sha256, long expectedBytes, File destination) throws Exception {
        validateHash(sha256); validateOwner(owner); validateBytes(expectedBytes);
        verifyBucket(owner);
        if (destination.isFile()) {
            if (destination.length() == expectedBytes && sha256.equals(hash(destination))) return;
            throw new Failure("integrity", "An existing staged backup chunk is different; local files were not replaced");
        }
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory()) throw new Failure("local", "Backup staging folder is unavailable");
        File temporary = File.createTempFile("backup-", ".part", parent);
        try {
            download(owner, sha256, expectedBytes, temporary);
            if (!auth.isCurrentAccount(owner)) throw new Failure("auth", "Account changed; restore paused");
            // No REPLACE_EXISTING: another local writer cannot be silently overwritten.
            Files.move(temporary.toPath(), destination.toPath());
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }

    /** Publish only after every referenced immutable blob exists; an identical retry returns the same ID. */
    public String commit(String owner, String backupId, JSONObject manifest) throws Exception {
        validateOwner(owner); validateUuid(backupId);
        if (manifest == null) throw new Failure("permanent", "Backup manifest is missing");
        byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        if (manifestBytes.length > MAX_MANIFEST_BYTES)
            throw new Failure("permanent", "Backup manifest is too large; no incomplete backup was published");
        verifyBucket(owner);
        JSONObject body = new JSONObject().put("p_backup_id", backupId).put("p_manifest", manifest);
        String text = json(owner, "POST", "/rest/v1/rpc/commit_account_backup", body);
        String confirmed;
        try { confirmed = new JSONArray("[" + text + "]").getString(0); }
        catch (Exception error) { throw new Failure("integrity", "Cloud backup confirmation was invalid; local files are retained"); }
        if (!backupId.equals(confirmed)) throw new Failure("integrity", "Cloud backup identity did not match; local files are retained");
        return confirmed;
    }

    /** Summary-only keyset pages, newest first. A cursor always includes both sort keys. */
    public JSONArray list(String owner, String beforeCreatedAt, String beforeId, int limit) throws Exception {
        validateOwner(owner);
        if (limit < 1 || limit > 100) throw new Failure("permanent", "Invalid backup page size");
        String time = beforeCreatedAt == null ? "" : beforeCreatedAt;
        String id = beforeId == null ? "" : beforeId;
        if (time.isEmpty() != id.isEmpty()) throw new Failure("permanent", "Incomplete backup cursor");
        String select = "backup_id,created_at,source_id:manifest->>source_id,captured_at:manifest->>captured_at,"
                + "device_label:manifest->>device_label,counts:manifest->counts";
        String path = "/rest/v1/account_backups?select=" + encode(select) + "&owner_id=eq." + owner
                + "&order=created_at.desc,backup_id.desc&limit=" + limit;
        if (!time.isEmpty()) {
            validateUuid(id);
            try { time = OffsetDateTime.parse(time).toInstant().toString(); }
            catch (Exception error) {
                try { time = Instant.parse(time).toString(); }
                catch (Exception invalid) { throw new Failure("permanent", "Invalid backup cursor time"); }
            }
            path += "&or=" + encode("(created_at.lt." + time + ",and(created_at.eq." + time + ",backup_id.lt." + id + "))");
        }
        verifyBucket(owner);
        return array(json(owner, "GET", path, null));
    }

    /** Fetch one bounded immutable manifest, never all snapshot payloads in a list response. */
    public JSONObject manifest(String owner, String backupId) throws Exception {
        validateOwner(owner); validateUuid(backupId); verifyBucket(owner);
        String path = "/rest/v1/account_backups?select=manifest&owner_id=eq." + owner
                + "&backup_id=eq." + backupId + "&limit=1";
        JSONArray rows = array(json(owner, "GET", path, null));
        if (rows.length() != 1) throw new Failure("missing", "This backup is unavailable for the signed-in account");
        try { return rows.getJSONObject(0).getJSONObject("manifest"); }
        catch (Exception error) { throw new Failure("integrity", "Cloud backup manifest response was invalid"); }
    }

    private void verifyRemoteBlob(String owner, String sha256, long size) throws Exception {
        download(owner, sha256, size, null);
    }

    private void download(String owner, String sha256, long size, File staged) throws Exception {
        String path = "/storage/v1/object/authenticated/" + BUCKET + "/" + owner + "/" + sha256;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = connection(owner, "GET", path, attempt > 0);
            try {
                int status = connection.getResponseCode();
                if (status == 401 && attempt == 0) { readText(connection.getErrorStream(), MAX_ERROR_BYTES); continue; }
                if (status < 200 || status >= 300) throw failure(status, readText(connection.getErrorStream(), MAX_ERROR_BYTES));
                long announced = connection.getContentLengthLong();
                if (announced > MAX_BLOB_BYTES || announced >= 0 && announced != size)
                    throw new Failure("integrity", "Cloud backup chunk size is invalid; restore was stopped");
                MessageDigest received = digest();
                long count;
                if (staged == null) {
                    try (InputStream input = connection.getInputStream()) { count = transfer(input, null, received, size); }
                } else {
                    try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(staged)) {
                        count = transfer(input, output, received, size); output.getFD().sync();
                    }
                }
                if (count != size || !sha256.equals(hex(received.digest())))
                    throw new Failure("integrity", "Cloud backup chunk checksum did not match; local data was not changed");
                if (!auth.isCurrentAccount(owner)) throw new Failure("auth", "Account changed; backup transfer paused");
                return;
            } catch (Failure error) { throw error; }
            catch (IOException error) { throw new Failure("failed", "Backup download was interrupted; local data was not changed"); }
            finally { connection.disconnect(); }
        }
        throw new Failure("auth", "Sign in again to continue restoring the backup");
    }

    private String json(String owner, String method, String path, JSONObject body) throws Exception {
        byte[] bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes != null && bytes.length > MAX_MANIFEST_BYTES + 1024)
            throw new Failure("permanent", "Backup request is too large; local files are retained");
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = connection(owner, method, path, attempt > 0);
            try {
                if (bytes != null) {
                    connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
                }
                int status = connection.getResponseCode();
                String text = readText(status >= 200 && status < 300 ? connection.getInputStream()
                        : connection.getErrorStream(), status >= 200 && status < 300 ? MAX_MANIFEST_BYTES + 1024 : MAX_ERROR_BYTES);
                if (status >= 200 && status < 300) {
                    if (!auth.isCurrentAccount(owner)) throw new Failure("auth", "Account changed; backup transfer paused");
                    return text;
                }
                if (status == 401 && attempt == 0) continue;
                throw failure(status, text);
            } catch (Failure error) { throw error; }
            catch (IOException error) { throw new Failure("failed", "Cloud backup request was interrupted; local data is retained"); }
            finally { connection.disconnect(); }
        }
        throw new Failure("auth", "Sign in again to continue cloud backups");
    }

    private HttpURLConnection connection(String owner, String method, String path, boolean forceRefresh) throws Exception {
        validateOwner(owner);
        if (!SupabaseConfig.isConfigured()) throw new Failure("configuration", "Supabase client configuration is missing");
        URL base = URI.create(SupabaseConfig.url()).toURL();
        if (!"https".equals(base.getProtocol()) || !(SupabaseConfig.PROJECT_REF + ".supabase.co").equals(base.getHost())
                || base.getUserInfo() != null || base.getPort() != -1 || !base.getPath().isEmpty()
                || base.getQuery() != null || base.getRef() != null)
            throw new Failure("configuration", "Backup URL does not match the account project");
        SupabaseAuthManager.Session session;
        try { session = auth.sessionForCloud(forceRefresh); }
        catch (Exception error) { throw new Failure("auth", "Sign in again to continue cloud backups"); }
        if (session == null || !owner.equals(session.userId) || !auth.isCurrentAccount(owner))
            throw new Failure("auth", "Account changed; backup transfer paused");
        HttpURLConnection connection = CloudDataPolicy.openAccountConnection(URI.create(SupabaseConfig.url() + path).toURL(), owner, session.accessToken);
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(30000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("apikey", SupabaseConfig.publishableKey());
        connection.setRequestProperty("Authorization", "Bearer " + session.accessToken);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-store");
        return connection;
    }

    private static long checkedSize(File file) throws Failure {
        if (file == null || !file.isFile()) throw new Failure("local", "A backup chunk is missing; local records are retained");
        long size = file.length(); validateBytes(size); return size;
    }
    private static void validateBytes(long size) throws Failure {
        if (size <= 0 || size > MAX_BLOB_BYTES) throw new Failure("integrity", "Invalid backup chunk size");
    }
    private static void validateOwner(String owner) throws Failure {
        validateUuid(owner);
    }
    private static void validateUuid(String value) throws Failure {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException error) { throw new Failure("permanent", "Invalid backup identity"); }
    }
    private static void validateHash(String value) throws Failure {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new Failure("integrity", "Invalid backup checksum");
    }
    private static MessageDigest digest() throws Exception { return MessageDigest.getInstance("SHA-256"); }
    private static String hash(File file) throws Exception {
        MessageDigest value = digest();
        try (InputStream input = new FileInputStream(file)) { transfer(input, null, value, MAX_BLOB_BYTES); }
        return hex(value.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(Locale.US, "%02x", value & 255));
        return text.toString();
    }
    private static long transfer(InputStream input, OutputStream output, MessageDigest hash, long maximum) throws Exception {
        byte[] buffer = new byte[32768]; long count = 0; int read;
        while ((read = input.read(buffer)) != -1) {
            count += read;
            if (count > maximum) throw new Failure("integrity", "Backup chunk exceeds its declared size; restore was stopped");
            hash.update(buffer, 0, read); if (output != null) output.write(buffer, 0, read);
        }
        return count;
    }
    private static String readText(InputStream input, int maximum) throws Exception {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int read;
            while ((read = stream.read(buffer)) != -1) {
                if (output.size() + read > maximum) throw new Failure("integrity", "Cloud backup response is too large");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private static boolean alreadyExists(int status, String text) {
        if (status == 409) return true;
        if (status != 400) return false;
        String value = text.toLowerCase(Locale.US);
        return value.contains("alreadyexists") || value.contains("already_exists") || value.contains("already exists") || value.contains("duplicate");
    }
    private static Failure failure(int status, String text) {
        String code = "";
        try {
            JSONObject error = new JSONObject(text);
            String candidate = error.optString("code");
            if (candidate.isEmpty()) candidate = error.optString("error");
            if (candidate.matches("[A-Za-z0-9_.-]{1,64}")) code = candidate;
        } catch (Exception ignored) { }
        if (status == 401 || status == 403) return new Failure("auth", "Cloud backup access was denied; local data is retained", status, code);
        if (status >= 300 && status < 400) return new Failure("configuration", "Cloud backup redirects are not allowed", status, code);
        if (status == 409 || "40001".equals(code)) return new Failure("conflict", "This backup identity already has different contents; the previous backup was retained", status, code);
        if (status == 404 || "PGRST202".equals(code) || "42P01".equals(code)) return new Failure("configuration", "Cloud backup storage or schema is not ready", status, code);
        if (status == 400 || status == 413 || status == 422) return new Failure("permanent", "Cloud backup was rejected; no completed backup was replaced", status, code);
        return new Failure("failed", "Cloud backup service is unavailable; completed backups and local files are retained", status, code);
    }
    private static String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    private static JSONObject object(String text) throws Failure {
        try { return new JSONObject(text); }
        catch (Exception error) { throw new Failure("integrity", "Cloud backup metadata response was invalid"); }
    }
    private static JSONArray array(String text) throws Failure {
        try { return new JSONArray(text); }
        catch (Exception error) { throw new Failure("integrity", "Cloud backup list response was invalid"); }
    }
}
