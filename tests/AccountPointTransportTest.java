package com.fiskentra.app.backend;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs the production HTTP transport with in-memory HTTPS connections; no sockets or real secrets. */
public final class AccountPointTransportTest {
    private static final String OWNER = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER = "22222222-2222-4222-8222-222222222222";
    private static final String POINT = "33333333-3333-4333-8333-333333333333";
    private static final String OPERATION = "44444444-4444-4444-8444-444444444444";
    private static final String ACCESS = "fixture-user-access", REFRESHED_ACCESS = "fixture-refreshed-access";
    private static final Deque<Response> responses = new ArrayDeque<>();
    private static final List<SpyConnection> connections = new ArrayList<>();
    private static int checks;

    private static final class Response {
        final int status;
        final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }

    private static final class SpyConnection extends HttpURLConnection {
        final Response response;
        final Map<String, String> headers = new LinkedHashMap<>();
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        boolean disconnected, responseClosed, redirectPolicySet;
        SpyConnection(URL url, Response response) { super(url); this.response = response; }
        @Override public void connect() { connected = true; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void setRequestProperty(String name, String value) { headers.put(name, value); }
        @Override public void setInstanceFollowRedirects(boolean follow) {
            redirectPolicySet = true;
            super.setInstanceFollowRedirects(follow);
        }
        @Override public OutputStream getOutputStream() { return sent; }
        @Override public int getResponseCode() { return response.status; }
        @Override public InputStream getInputStream() { return body(); }
        @Override public InputStream getErrorStream() { return body(); }
        private InputStream body() {
            if (response.body == null) return null;
            return new ByteArrayInputStream(response.body.getBytes(StandardCharsets.UTF_8)) {
                @Override public void close() throws IOException { responseClosed = true; super.close(); }
            };
        }
        String sentText() { return new String(sent.toByteArray(), StandardCharsets.UTF_8); }
    }

    @FunctionalInterface private interface Attempt { void run() throws Exception; }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void reset() {
        check(responses.isEmpty(), "Every planned response was consumed");
        connections.clear();
        SupabaseConfig.configured = true;
        SupabaseConfig.endpoint = "https://" + SupabaseConfig.PROJECT_REF + ".supabase.co";
    }
    private static SupabaseAuthManager auth() {
        return new SupabaseAuthManager(new SupabaseAuthManager.Session(OWNER, ACCESS));
    }
    private static CloudSyncLedger.Request pointRequest(boolean deleted) throws Exception {
        JSONObject entry = new JSONObject().put("owner", OWNER).put("remoteId", POINT).put("localId", 1234);
        JSONObject payload = new JSONObject().put("time", 1234).put("title", "Līdaka ✦");
        JSONObject job = new JSONObject().put("operationId", OPERATION).put("expectedVersion", 7)
                .put("deleted", deleted).put("payload", payload);
        return new CloudSyncLedger.Request(entry, job);
    }
    private static void reply(int status, String body) { responses.add(new Response(status, body)); }
    private static void failure(String category, Attempt attempt) throws Exception {
        try { attempt.run(); throw new AssertionError("Expected failure category: " + category); }
        catch (AccountPointTransport.Failure expected) {
            check(category.equals(expected.category), "HTTP outcome has the expected recovery category");
        }
    }
    private static void closedConnections() {
        for (SpyConnection connection : connections) {
            check(connection.disconnected, "Every HTTP attempt is disconnected, including failures");
            check(connection.response.body == null || connection.responseClosed, "Response streams are closed");
            check(connection.redirectPolicySet && !connection.getInstanceFollowRedirects(),
                    "Credentials must not follow HTTP redirects");
        }
    }

    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                Response response = responses.poll();
                if (response == null) throw new AssertionError("Unexpected HTTPS connection; real network is disabled");
                SpyConnection connection = new SpyConnection(url, response);
                connections.add(connection);
                return connection;
            }
        } : null);

        reset();
        SupabaseAuthManager auth = auth();
        AccountPointTransport transport = new AccountPointTransport(auth);
        reply(200, " 8 \n");
        check(transport.send(pointRequest(false)) == 8, "A positive server version confirms an upload");
        SpyConnection sent = connections.get(0);
        check("POST".equals(sent.getRequestMethod()), "Uploads use POST");
        check("/rest/v1/rpc/sync_account_point".equals(sent.getURL().getPath()), "Uploads use the account RPC only");
        check("https".equals(sent.getURL().getProtocol()) && (SupabaseConfig.PROJECT_REF + ".supabase.co").equals(sent.getURL().getHost()),
                "Only the configured fake HTTPS endpoint was reached");
        check(SupabaseConfig.publishableKey().equals(sent.headers.get("apikey")), "Publishable key header is attached");
        check(("Bearer " + ACCESS).equals(sent.headers.get("Authorization")), "Authorization is the user's access bearer");
        check(!sent.headers.containsKey("X-Device-Id"), "Legacy device identity is never sent");
        check("application/json".equals(sent.headers.get("Content-Type")), "RPC payload is JSON");
        check(sent.getConnectTimeout() == 8000 && sent.getReadTimeout() == 10000, "Requests have bounded timeouts");
        JSONObject body = new JSONObject(sent.sentText());
        check(POINT.equals(body.getString("p_point_id")) && OPERATION.equals(body.getString("p_operation_id")),
                "Point and idempotency identities reach the actual wire payload");
        check(body.getLong("p_expected_version") == 7 && !body.getBoolean("p_deleted"), "Optimistic version and delete state are sent");
        check("Līdaka ✦".equals(body.getJSONObject("p_payload").getString("title")), "UTF-8 point details survive HTTP encoding");
        check(auth.refreshRequests.equals(Arrays.asList(false)), "Successful requests do not force a token refresh");
        closedConnections();

        reset(); auth = auth(); transport = new AccountPointTransport(auth);
        reply(200, "[{\"point_id\":\"" + POINT + "\",\"version\":8,\"payload\":{},\"deleted\":false}]");
        JSONArray page = transport.page(OWNER, POINT);
        sent = connections.get(0);
        check(page.length() == 1 && POINT.equals(page.getJSONObject(0).getString("point_id")), "Restore reads the returned rows");
        check("GET".equals(sent.getRequestMethod()) && "/rest/v1/account_points".equals(sent.getURL().getPath()),
                "Restore uses only the authenticated account table");
        check(sent.getURL().getQuery().contains("owner_id=eq." + OWNER)
                && sent.getURL().getQuery().contains("order=point_id.asc")
                && sent.getURL().getQuery().contains("limit=200")
                && sent.getURL().getQuery().contains("point_id=gt." + POINT)
                && !sent.getURL().getQuery().contains("offset="), "Restore is owner-filtered, ordered and uses a stable keyset cursor");
        check(sent.sent.size() == 0 && !sent.getDoOutput(), "Restore never sends an upload body");
        closedConnections();

        reset(); transport = new AccountPointTransport(auth()); reply(200, "[]");
        check(transport.page(OWNER, "").length() == 0
                && !connections.get(0).getURL().getQuery().contains("point_id=gt."),
                "The first restore page does not invent a cursor");
        closedConnections();

        reset(); transport = new AccountPointTransport(auth());
        try { transport.page(OWNER, "bad-cursor&owner_id=eq.other"); throw new AssertionError("Invalid cursor accepted"); }
        catch (IllegalArgumentException expected) { check(connections.isEmpty(), "Invalid cursor cannot inject an API filter or reach HTTP"); }

        reset(); auth = auth(); auth.afterRefresh = new SupabaseAuthManager.Session(OWNER, REFRESHED_ACCESS);
        transport = new AccountPointTransport(auth); reply(401, "{}"); reply(200, "9");
        check(transport.send(pointRequest(true)) == 9, "An expired token can refresh once for the same account");
        check(auth.refreshRequests.equals(Arrays.asList(false, true)), "Only the second attempt forces refresh");
        check(connections.size() == 2 && connections.get(0).sentText().equals(connections.get(1).sentText()),
                "Response-loss/auth retries preserve the complete operation payload");
        check(("Bearer " + REFRESHED_ACCESS).equals(connections.get(1).headers.get("Authorization")),
                "The retried request uses the refreshed user bearer");
        check(new JSONObject(connections.get(1).sentText()).getBoolean("p_deleted"), "Deletion retries retain the tombstone");
        check(new JSONObject(connections.get(0).sentText()).getJSONObject("p_payload").length() == 0
                && new JSONObject(connections.get(1).sentText()).getJSONObject("p_payload").length() == 0,
                "Neither the deletion attempt nor its replay retransmits point coordinates or notes");
        closedConnections();

        reset(); auth = auth(); transport = new AccountPointTransport(auth); reply(401, null); reply(401, "{}");
        AccountPointTransport denied = transport;
        failure("auth", () -> denied.send(pointRequest(false)));
        check(connections.size() == 2 && auth.refreshRequests.size() == 2, "Repeated 401 stops after exactly two attempts");
        closedConnections();

        for (int status : new int[] {403, 409, 404, 400, 422, 500, 302}) {
            reset(); auth = auth(); transport = new AccountPointTransport(auth); reply(status, "{}");
            String category = status == 403 ? "auth" : status == 409 ? "conflict"
                    : status == 404 || status == 400 || status == 422 ? "permanent" : "failed";
            AccountPointTransport failing = transport;
            failure(category, () -> failing.send(pointRequest(false)));
            check(connections.size() == 1 && auth.refreshRequests.equals(Arrays.asList(false)),
                    "Non-401 failures never blindly refresh or repeat a write");
            closedConnections();
        }
        for (String code : new String[] {"40001", "PGRST202", "42P01"}) {
            reset(); transport = new AccountPointTransport(auth()); reply(400, new JSONObject().put("code", code).toString());
            AccountPointTransport coded = transport;
            failure("40001".equals(code) ? "conflict" : "permanent", () -> coded.send(pointRequest(false)));
            closedConnections();
        }
        for (String invalid : new String[] {"", "0", "-1", "\"10\"", "true", "{}", "1.5", "9223372036854775808"}) {
            reset(); transport = new AccountPointTransport(auth()); reply(200, invalid);
            AccountPointTransport unconfirmed = transport;
            failure("permanent", () -> unconfirmed.send(pointRequest(false)));
            check(connections.size() == 1, "Malformed confirmations do not acknowledge or repeat a write");
            closedConnections();
        }

        reset(); auth = new SupabaseAuthManager(new SupabaseAuthManager.Session(OTHER, ACCESS));
        AccountPointTransport differentOwner = new AccountPointTransport(auth);
        failure("auth", () -> differentOwner.send(pointRequest(false)));
        check(connections.isEmpty(), "Account-switch before connection cannot send another owner's job");

        reset(); auth = auth(); auth.denyCurrentAccount = true;
        AccountPointTransport changedBeforeOpen = new AccountPointTransport(auth);
        failure("auth", () -> changedBeforeOpen.send(pointRequest(false)));
        check(connections.isEmpty(), "A changed account between session acquisition and connection is rejected");

        reset(); auth = auth(); auth.afterRefresh = new SupabaseAuthManager.Session(OTHER, REFRESHED_ACCESS);
        AccountPointTransport changedDuringRefresh = new AccountPointTransport(auth); reply(401, "{}");
        failure("auth", () -> changedDuringRefresh.send(pointRequest(false)));
        check(connections.size() == 1, "A refresh switching accounts cannot replay the old owner's write");
        closedConnections();

        reset(); auth = auth(); auth.failSession = true;
        AccountPointTransport missingSession = new AccountPointTransport(auth);
        failure("auth", () -> missingSession.send(pointRequest(false)));
        check(connections.isEmpty(), "Missing session fails before opening a socket");

        reset(); SupabaseConfig.configured = false;
        AccountPointTransport notConfigured = new AccountPointTransport(auth());
        failure("permanent", () -> notConfigured.send(pointRequest(false)));
        check(connections.isEmpty(), "Missing API configuration cannot reach HTTP");

        for (String endpoint : new String[] {"https://wrong-project.invalid", "http://" + SupabaseConfig.PROJECT_REF + ".supabase.co",
                "https://fixture@" + SupabaseConfig.PROJECT_REF + ".supabase.co", "https://" + SupabaseConfig.PROJECT_REF + ".supabase.co:443"}) {
            reset(); SupabaseConfig.endpoint = endpoint;
            AccountPointTransport wrongProject = new AccountPointTransport(auth());
            failure("permanent", () -> wrongProject.send(pointRequest(false)));
            check(connections.isEmpty(), "Project/protocol/userinfo/port mismatch cannot send credentials");
        }

        reset();
        try { CloudDataPolicy.openConnection(new URL("https://account-fixture.invalid/rest/v1/saved_points"));
            throw new AssertionError("Legacy API was enabled"); }
        catch (IOException expected) { check(connections.isEmpty(), "Legacy anonymous API stays blocked before HTTP"); }
        try { CloudDataPolicy.openAccountConnection(new URL("https://account-fixture.invalid/rest/v1/saved_points"), OWNER, ACCESS);
            throw new AssertionError("Wrong account endpoint was enabled"); }
        catch (IOException expected) { check(connections.isEmpty(), "Account transport cannot use the legacy table"); }
        try { CloudDataPolicy.openAccountConnection(new URL("http://account-fixture.invalid/rest/v1/account_points"), OWNER, ACCESS);
            throw new AssertionError("Insecure account endpoint was enabled"); }
        catch (IOException expected) { check(connections.isEmpty(), "Personal account traffic requires HTTPS"); }
        check(responses.isEmpty(), "No hidden retries or unconsumed fixture responses remain");
        System.out.println("AccountPointTransportTest: " + checks + " checks passed; no network or credential files used");
    }
}
