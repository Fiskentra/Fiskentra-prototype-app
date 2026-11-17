package com.fiskentra.app.backend;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exercises the production binary/JSON transport; HTTPS is intercepted before a socket can open. */
public final class AccountBackupTransportTest {
    private static final String OWNER = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER = "22222222-2222-4222-8222-222222222222";
    private static final String BACKUP = "33333333-3333-4333-8333-333333333333";
    private static final String ACCESS = "fixture-user-access", REFRESHED = "fixture-refreshed-access";
    private static final byte[] ORIGINAL = new byte[]{(byte) 0xff, (byte) 0xd8, 0, 1, 2, (byte) 0xfe, 3, (byte) 0xff, (byte) 0xd9};
    private static final String BUCKET_JSON = "{\"id\":\"fiskentra-backups-v1\",\"public\":false,\"file_size_limit\":4194304}";
    private static final Deque<Response> planned = new ArrayDeque<>();
    private static final List<Spy> connections = new ArrayList<>();
    private static int checks;

    private static final class Response {
        final int status; final byte[] body; long contentLength; Runnable onClose;
        Response(int status, byte[] body) { this.status = status; this.body = body; contentLength = body == null ? -1 : body.length; }
    }
    private static final class Spy extends HttpURLConnection {
        final Response response; final Map<String,String> headers = new LinkedHashMap<>();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean disconnected, streamOpened, streamClosed, redirectPolicy;
        long declaredLength = -1;
        Spy(URL url, Response response) { super(url); this.response = response; }
        @Override public void connect() { connected = true; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void setRequestProperty(String name, String value) { headers.put(name,value); }
        @Override public void setInstanceFollowRedirects(boolean value) { redirectPolicy = true; super.setInstanceFollowRedirects(value); }
        @Override public void setFixedLengthStreamingMode(long length) { declaredLength = length; }
        @Override public void setFixedLengthStreamingMode(int length) { declaredLength = length; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public int getResponseCode() { return response.status; }
        @Override public long getContentLengthLong() { return response.contentLength; }
        @Override public InputStream getInputStream() { return stream(); }
        @Override public InputStream getErrorStream() { return stream(); }
        private InputStream stream() {
            if (response.body == null) return null;
            streamOpened = true;
            return new ByteArrayInputStream(response.body) {
                @Override public void close() throws IOException {
                    streamClosed = true;
                    if (response.onClose != null) response.onClose.run();
                    super.close();
                }
            };
        }
        String sentText() { return new String(output.toByteArray(), StandardCharsets.UTF_8); }
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void check(boolean success, String why) { checks++; if (!success) throw new AssertionError(why); }
    private static void reset() {
        check(planned.isEmpty(), "Every response in the previous exchange was consumed"); connections.clear();
        SupabaseConfig.configured = true; SupabaseConfig.endpoint = "https://" + SupabaseConfig.PROJECT_REF + ".supabase.co";
    }
    private static Response reply(int status, String value) { return reply(status, value.getBytes(StandardCharsets.UTF_8)); }
    private static Response reply(int status, byte[] value) { Response response = new Response(status,value); planned.add(response); return response; }
    private static void bucket() { reply(200,BUCKET_JSON); }
    private static SupabaseAuthManager auth() { return new SupabaseAuthManager(new SupabaseAuthManager.Session(OWNER,ACCESS)); }
    private static AccountBackupTransport.Failure failure(String category, Operation call) throws Exception {
        try { call.run(); throw new AssertionError("Expected backup failure: " + category); }
        catch (AccountBackupTransport.Failure error) {
            check(category.equals(error.category), "Failure has a safe actionable category: expected " + category + ", got " + error.category);
            check(!error.getMessage().contains(ACCESS) && !error.getMessage().contains("private-server-detail"), "Error messages cannot echo credentials or backend text");
            return error;
        }
    }
    private static void closed() {
        for (Spy c : connections) {
            check(c.disconnected, "Every HTTP attempt disconnects");
            check(!c.streamOpened || c.streamClosed, "Every opened response stream closes");
            check(c.redirectPolicy && !c.getInstanceFollowRedirects(), "Bearer credentials cannot follow redirects");
            check(c.getConnectTimeout() == 10000 && c.getReadTimeout() == 30000, "Network waits are bounded");
            check(!c.headers.containsKey("X-Device-Id"), "Backup transport never uses legacy device ownership");
        }
    }
    private static String sha(byte[] value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(value)) result.append(String.format("%02x",b & 255));
        return result.toString();
    }
    private static JSONObject manifest(String hash) throws Exception {
        JSONObject chunk = new JSONObject().put("sha256",hash).put("bytes",ORIGINAL.length);
        JSONObject file = new JSONObject().put("sha256",hash).put("bytes",ORIGINAL.length).put("chunks",new JSONArray().put(chunk));
        return new JSONObject().put("schema",1).put("source_id",BACKUP).put("captured_at",1234567)
                .put("device_label","Līdaka ✦").put("snapshot",file).put("photos",new JSONArray())
                .put("blobs",new JSONArray().put(chunk)).put("counts",new JSONObject().put("points",3).put("photos",0).put("trips",2));
    }

    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                Response next = planned.poll();
                if (next == null) throw new AssertionError("Unexpected HTTPS request; real networking is disabled");
                Spy spy = new Spy(url,next); connections.add(spy); return spy;
            }
        } : null);
        Path folder = Files.createTempDirectory("fiskentra-backup-transport-");
        Path source = folder.resolve("original.bin"), target = folder.resolve("restored.bin");
        Files.write(source,ORIGINAL); String hash = sha(ORIGINAL);
        try {
            reset(); SupabaseAuthManager auth = auth(); AccountBackupTransport transport = new AccountBackupTransport(auth);
            bucket(); reply(200,"{}"); reply(200,ORIGINAL);
            transport.putBlob(OWNER,hash,source.toFile());
            check(connections.size()==3,"New uploads verify bucket, POST bytes, then privately download/hash the result");
            Spy sent=connections.get(1), received=connections.get(2);
            check("POST".equals(sent.getRequestMethod()),"Chunks upload through standard POST");
            check(sent.getURL().getPath().equals("/storage/v1/object/fiskentra-backups-v1/"+OWNER+"/"+hash),"Storage path binds owner and content hash");
            check(Arrays.equals(ORIGINAL,sent.output.toByteArray()),"Original binary bytes, including zero/non-UTF8 bytes, are unchanged");
            check(sent.declaredLength==ORIGINAL.length,"Streaming upload has exact Content-Length");
            check("false".equals(sent.headers.get("x-upsert")),"Uploads never request overwrite");
            check("application/octet-stream".equals(sent.headers.get("Content-Type")),"Chunks have a binary content type");
            check(("Bearer "+ACCESS).equals(sent.headers.get("Authorization")),"Signed-in user's bearer reaches the wire");
            check(SupabaseConfig.publishableKey().equals(sent.headers.get("apikey")),"Only the configured public API key is attached");
            check(received.getURL().getPath().startsWith("/storage/v1/object/authenticated/"),"Verification uses private authenticated downloads");
            closed();

            for (int status : new int[]{400,409}) {
                reset(); transport=new AccountBackupTransport(auth()); bucket(); reply(status,"{\"error\":\"Duplicate\",\"message\":\"The resource already exists\"}"); reply(200,ORIGINAL);
                transport.putBlob(OWNER,hash,source.toFile());
                check(connections.size()==3,"Existing-blob HTTP "+status+" verifies the bytes instead of overwriting"); closed();
            }

            reset(); transport=new AccountBackupTransport(auth()); bucket(); reply(409,"{}"); reply(200,new byte[ORIGINAL.length]);
            AccountBackupTransport duplicateTransport=transport;
            failure("integrity",()->duplicateTransport.putBlob(OWNER,hash,source.toFile())); closed();

            reset(); transport=new AccountBackupTransport(auth()); bucket(); reply(200,ORIGINAL);
            transport.getBlob(OWNER,hash,ORIGINAL.length,target.toFile());
            check(Arrays.equals(ORIGINAL,Files.readAllBytes(target)),"Private download restores exactly the original bytes"); closed();
            reset(); transport=new AccountBackupTransport(auth()); bucket();
            transport.getBlob(OWNER,hash,ORIGINAL.length,target.toFile());
            check(connections.size()==1,"Already verified staged file is reusable after checking bucket privacy"); closed();

            Files.write(target,new byte[]{4,5,6});
            reset(); AccountBackupTransport existing=new AccountBackupTransport(auth()); bucket();
            failure("integrity",()->existing.getBlob(OWNER,hash,ORIGINAL.length,target.toFile()));
            check(Arrays.equals(new byte[]{4,5,6},Files.readAllBytes(target)),"Restore cannot replace a different existing local file"); closed(); Files.delete(target);

            reset(); AccountBackupTransport corrupt=new AccountBackupTransport(auth()); bucket(); reply(200,new byte[ORIGINAL.length]);
            failure("integrity",()->corrupt.getBlob(OWNER,hash,ORIGINAL.length,target.toFile()));
            check(!Files.exists(target),"Checksum failure publishes no destination file");
            try(java.util.stream.Stream<Path> files=Files.list(folder)){check(files.noneMatch(p->p.toString().endsWith(".part")),"Checksum failure removes its own staged partial file");} closed();

            reset(); AccountBackupTransport truncated=new AccountBackupTransport(auth()); bucket(); reply(200,new byte[]{1,2});
            failure("integrity",()->truncated.getBlob(OWNER,hash,ORIGINAL.length,target.toFile()));
            check(!Files.exists(target),"Declared length mismatch does not publish partial data"); closed();
            reset(); AccountBackupTransport tooLong=new AccountBackupTransport(auth()); bucket(); Response unknown=reply(200,new byte[ORIGINAL.length+1]); unknown.contentLength=-1;
            failure("integrity",()->tooLong.getBlob(OWNER,hash,ORIGINAL.length,target.toFile()));
            check(!Files.exists(target),"Unknown-length responses remain bounded by expected bytes"); closed();

            for(String invalidBucket:new String[]{BUCKET_JSON.replace("false","true"),BUCKET_JSON.replace("4194304","0"),
                    BUCKET_JSON.replace("4194304","4194305"),BUCKET_JSON.replace("4194304","null"),BUCKET_JSON.replace("fiskentra-backups-v1","different"),
                    BUCKET_JSON.replace("false","\"false\"")}) {
                reset(); AccountBackupTransport unsafe=new AccountBackupTransport(auth()); reply(200,invalidBucket);
                failure("configuration",()->unsafe.putBlob(OWNER,hash,source.toFile()));
                check(connections.size()==1,"Unsafe bucket configuration prevents binary transfer"); closed();
            }

            reset(); SupabaseAuthManager expired=auth(); expired.afterRefresh=new SupabaseAuthManager.Session(OWNER,REFRESHED);
            transport=new AccountBackupTransport(expired); reply(401,"{}"); bucket();
            transport.verifyBucket(OWNER);
            check(expired.refreshRequests.equals(Arrays.asList(false,true)),"401 triggers exactly one forced refresh");
            check(("Bearer "+REFRESHED).equals(connections.get(1).headers.get("Authorization")),"Retry uses refreshed token"); closed();
            reset(); SupabaseAuthManager bad=auth(); AccountBackupTransport denied=new AccountBackupTransport(bad); reply(401,"{}"); reply(401,"{}");
            failure("auth",()->denied.verifyBucket(OWNER)); check(connections.size()==2,"Repeated 401 stops after two attempts"); closed();

            reset(); SupabaseAuthManager switched=auth(); switched.current=new SupabaseAuthManager.Session(OTHER,ACCESS);
            AccountBackupTransport wrong=new AccountBackupTransport(switched);
            failure("auth",()->wrong.verifyBucket(OWNER)); check(connections.isEmpty(),"Wrong account fails before any HTTP request");
            reset(); SupabaseAuthManager during=auth(); AccountBackupTransport changed=new AccountBackupTransport(during); bucket();
            Response downloaded=reply(200,ORIGINAL); downloaded.onClose=()->during.current=new SupabaseAuthManager.Session(OTHER,ACCESS);
            failure("auth",()->changed.getBlob(OWNER,hash,ORIGINAL.length,target.toFile())); check(!Files.exists(target),"Account switch during download publishes no file"); closed();

            reset(); AccountBackupTransport invalid=new AccountBackupTransport(auth());
            failure("integrity",()->invalid.putBlob(OWNER,hash.toUpperCase(java.util.Locale.US),source.toFile()));
            failure("integrity",()->invalid.putBlob(OWNER,"../escape",source.toFile()));
            failure("integrity",()->invalid.getBlob(OWNER,hash,0,target.toFile()));
            failure("permanent",()->invalid.getBlob("../../owner",hash,ORIGINAL.length,target.toFile()));
            check(connections.isEmpty(),"Invalid identifiers and lengths never reach the network");
            Path large=folder.resolve("large.bin");try(RandomAccessFile f=new RandomAccessFile(large.toFile(),"rw")){f.setLength(AccountBackupTransport.MAX_BLOB_BYTES+1);}
            failure("integrity",()->invalid.putBlob(OWNER,hash,large.toFile()));Files.delete(large);
            failure("integrity",()->invalid.putBlob(OWNER,"0".repeat(64),source.toFile()));check(connections.isEmpty(),"Changed local checksum fails before bucket access");

            reset(); transport=new AccountBackupTransport(auth()); bucket(); reply(200,"\""+BACKUP+"\"");
            check(BACKUP.equals(transport.commit(OWNER,BACKUP,manifest(hash))),"Commit returns exactly the acknowledged immutable identity");
            JSONObject commit=new JSONObject(connections.get(1).sentText());
            check(BACKUP.equals(commit.getString("p_backup_id")),"Stable backup ID reaches RPC");
            check("Līdaka ✦".equals(commit.getJSONObject("p_manifest").getString("device_label")),"UTF-8 metadata remains intact");
            check(connections.get(1).getURL().getPath().equals("/rest/v1/rpc/commit_account_backup"),"Commit uses backup-specific RPC");closed();
            reset(); AccountBackupTransport wrongAck=new AccountBackupTransport(auth()); bucket();reply(200,"\""+OTHER+"\"");
            failure("integrity",()->wrongAck.commit(OWNER,BACKUP,manifest(hash)));closed();

            reset(); transport=new AccountBackupTransport(auth());bucket();reply(200,"[{\"backup_id\":\""+BACKUP+"\",\"counts\":{\"photos\":2}}]");
            JSONArray list=transport.list(OWNER,"2026-11-07T01:01:01+02:00",BACKUP,20);
            check(list.length()==1,"Summary list is parsed");
            String query=URLDecoder.decode(connections.get(1).getURL().getQuery(),"UTF-8");
            check(query.contains("order=created_at.desc,backup_id.desc")&&query.contains("limit=20"),"List has deterministic bounded descending order");
            check(query.contains("created_at.lt.2026-11-06T23:01:01Z")&&query.contains("backup_id.lt."+BACKUP),"Keyset normalizes time and includes tie-breaking ID");
            check(query.contains("counts:manifest->counts")&&!query.contains("select=manifest&"),"List requests summaries rather than full manifests");closed();
            reset(); AccountBackupTransport cursor=new AccountBackupTransport(auth());
            failure("permanent",()->cursor.list(OWNER,"2026-11-07T00:00:00Z","",20));
            failure("permanent",()->cursor.list(OWNER,"injected,filter",BACKUP,20));
            failure("permanent",()->cursor.list(OWNER,"","",101));check(connections.isEmpty(),"Invalid pagination is rejected locally");

            reset(); transport=new AccountBackupTransport(auth());bucket();reply(200,new JSONArray().put(new JSONObject().put("manifest",manifest(hash))).toString());
            check(transport.manifest(OWNER,BACKUP).getInt("schema")==1,"Single-backup read returns only its manifest");closed();
            reset(); AccountBackupTransport absent=new AccountBackupTransport(auth());bucket();reply(200,"[]");
            failure("missing",()->absent.manifest(OWNER,BACKUP));closed();
            reset(); AccountBackupTransport malformed=new AccountBackupTransport(auth());reply(200,"private-server-detail-not-json");
            failure("integrity",()->malformed.verifyBucket(OWNER));closed();

            for(int status:new int[]{302,400,403,404,413,429,500}) {
                reset(); AccountBackupTransport http=new AccountBackupTransport(auth());reply(status,"private-server-detail");
                String category=status==302||status==404?"configuration":status==403?"auth":status==400||status==413?"permanent":"failed";
                failure(category,()->http.verifyBucket(OWNER));closed();
            }
            for(String[] diagnostic:new String[][]{
                    {"{\"code\":\"22023\",\"message\":\"private-server-detail\"}","22023"},
                    {"{\"error\":\"InvalidMimeType\",\"message\":\"private-server-detail\"}","InvalidMimeType"},
                    {"{\"code\":\"\",\"error\":\"InvalidRequest\"}","InvalidRequest"},
                    {"{\"code\":\"PGRST_202.bad-request\"}","PGRST_202.bad-request"},
                    {"{\"code\":\"22023\",\"error\":\"OtherCode\"}","22023"},
                    {"{\"code\":\"Bearer private-server-detail\"}",""},
                    {"{\"error\":\"private/server/detail\"}",""},
                    {"{\"code\":\""+"x".repeat(65)+"\"}",""},
                    {"{\"error\":{\"private\":\"server-detail\"}}",""},
                    {"private-server-detail-not-json",""}}) {
                reset(); AccountBackupTransport diagnosticTransport=new AccountBackupTransport(auth());reply(400,diagnostic[0]);
                AccountBackupTransport.Failure diagnosticFailure=failure("permanent",()->diagnosticTransport.verifyBucket(OWNER));
                check(diagnosticFailure.httpStatus==400,"HTTP diagnostics retain the numeric response status");
                check(diagnostic[1].equals(diagnosticFailure.serverCode),"Only bounded enum-like server codes enter diagnostics");
                check(!diagnosticFailure.getMessage().contains("InvalidMimeType")&&!diagnosticFailure.getMessage().contains("22023"),"Production-facing messages stay generic");closed();
            }
            reset();AccountBackupTransport localDiagnostic=new AccountBackupTransport(auth());
            AccountBackupTransport.Failure localFailure=failure("integrity",()->localDiagnostic.getBlob(OWNER,hash,0,target.toFile()));
            check(localFailure.httpStatus==0&&localFailure.serverCode.isEmpty(),"Non-HTTP failures keep backwards-compatible empty diagnostic defaults");
            for(String endpoint:new String[]{"http://"+SupabaseConfig.PROJECT_REF+".supabase.co","https://wrong.supabase.co",
                    "https://user@"+SupabaseConfig.PROJECT_REF+".supabase.co","https://"+SupabaseConfig.PROJECT_REF+".supabase.co:443",
                    "https://"+SupabaseConfig.PROJECT_REF+".supabase.co/path","https://"+SupabaseConfig.PROJECT_REF+".supabase.co?query=1"}) {
                reset();SupabaseConfig.endpoint=endpoint;AccountBackupTransport config=new AccountBackupTransport(auth());
                failure("configuration",()->config.verifyBucket(OWNER));check(connections.isEmpty(),"Unexpected endpoint cannot receive credentials");
            }
            reset();check(planned.isEmpty(),"All planned requests completed");
            System.out.println("AccountBackupTransport: "+checks+" checks PASS (fake HTTPS only, original binary bytes verified)");
        } finally {
            try(java.util.stream.Stream<Path> files=Files.list(folder)){for(Path p:(Iterable<Path>)files::iterator)Files.deleteIfExists(p);}
            Files.deleteIfExists(folder);
        }
    }
}
