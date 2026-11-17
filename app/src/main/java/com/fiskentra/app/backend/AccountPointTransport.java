package com.fiskentra.app.backend;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Authenticated account API only. No fallback to the legacy device-header table. */
public final class AccountPointTransport {
    public static final class Failure extends Exception {
        public final String category;
        Failure(String category, String message) { super(message); this.category = category; }
    }
    private final SupabaseAuthManager auth;
    public AccountPointTransport(SupabaseAuthManager auth) { this.auth = auth; }

    public long send(CloudSyncLedger.Request request) throws Exception {
        JSONObject body = new JSONObject().put("p_point_id", request.remoteId).put("p_operation_id", request.operationId)
                .put("p_expected_version", request.expectedVersion).put("p_payload", request.deleted ? new JSONObject() : request.payload).put("p_deleted", request.deleted);
        String response = request(request.owner, "POST", "/rest/v1/rpc/sync_account_point", body);
        try { long version = Long.parseLong(response.trim()); if (version > 0) return version; }
        catch (NumberFormatException ignored) { }
        throw new Failure("permanent", "Cloud confirmation was invalid; local record retained");
    }
    public JSONArray page(String owner, String after) throws Exception {
        String path = "/rest/v1/account_points?select=point_id,version,payload,deleted&owner_id=eq."
                + URLEncoder.encode(owner, "UTF-8") + "&order=point_id.asc&limit=200";
        if (!after.isEmpty()) { java.util.UUID.fromString(after); path += "&point_id=gt." + after; }
        return new JSONArray(request(owner, "GET", path, null));
    }
    private String request(String owner, String method, String path, JSONObject payload) throws Exception {
        if (!SupabaseConfig.isConfigured()) throw new Failure("permanent", "Supabase client configuration is missing");
        URL configured = new URL(SupabaseConfig.url());
        if (!"https".equals(configured.getProtocol()) || !configured.getHost().equals(SupabaseConfig.PROJECT_REF + ".supabase.co")
                || configured.getUserInfo() != null || configured.getPort() != -1)
            throw new Failure("permanent", "Supabase URL does not match the account session project");
        for (int attempt = 0; attempt < 2; attempt++) {
            SupabaseAuthManager.Session session;
            try { session = auth.sessionForCloud(attempt > 0); }
            catch (Exception error) { throw new Failure("auth", "Sign in again to sync points"); }
            if (!owner.equals(session.userId) || !auth.isCurrentAccount(owner)) throw new Failure("auth", "Account changed; sync paused");
            HttpURLConnection connection = null;
            try {
                URL url = new URL(SupabaseConfig.url() + path);
                connection = CloudDataPolicy.openAccountConnection(url, owner, session.accessToken);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(8000); connection.setReadTimeout(10000); connection.setRequestMethod(method);
                connection.setRequestProperty("apikey", SupabaseConfig.publishableKey());
                connection.setRequestProperty("Authorization", "Bearer " + session.accessToken);
                connection.setRequestProperty("Accept", "application/json");
                if (payload != null) {
                    connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream output = connection.getOutputStream()) { output.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }
                }
                int status = connection.getResponseCode();
                String response = read(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
                if (status >= 200 && status < 300) return response;
                if (status == 401 && attempt == 0) continue;
                if (status == 401 || status == 403) throw new Failure("auth", "Cloud access was denied; local record retained");
                String code = ""; try { code = new JSONObject(response).optString("code"); } catch (Exception ignored) { }
                if ("40001".equals(code) || status == 409) throw new Failure("conflict", "Point changed in the cloud; local changes were retained");
                if (status == 404 || "PGRST202".equals(code) || "42P01".equals(code))
                    throw new Failure("permanent", "Account sync schema is not ready on this server");
                if (status == 400 || status == 422) throw new Failure("permanent", "Point was rejected; local record retained");
                throw new Failure("failed", "Cloud request failed; automatic retry is queued");
            } finally { if (connection != null) connection.disconnect(); }
        }
        throw new Failure("auth", "Sign in again to sync points");
    }
    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > 4 * 1024 * 1024) throw new Failure("permanent", "Cloud response is too large; use a smaller restore batch");
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
