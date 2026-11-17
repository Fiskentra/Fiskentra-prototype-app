package com.fiskentra.app.backend;

import com.fiskentra.app.data.PointLedger;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Authenticated account sync has its own boundary. Enabling it never enables the
 * preserved anonymous device-header API or consumes that API's legacy outbox.
 * Auth, public maps, search, weather and health checks have separate access paths.
 * Complete backups use a separate private immutable object namespace.
 */
public final class CloudDataPolicy {
    private static final boolean LOCAL_ONLY_BETA = false;

    private CloudDataPolicy() {}

    public static boolean isLocalOnlyBeta() { return LOCAL_ONLY_BETA; }
    /** The old anonymous device-header API must never be re-enabled by account sync. */
    public static boolean allowsPersonalDataRequests() { return false; }
    public static boolean accountSyncAvailable() { return !LOCAL_ONLY_BETA; }

    /** Do not claim, complete, retry or discard the durable outbox while disabled. */
    public static PointLedger.Operation claimForTransfer(PointLedger ledger, long now) {
        return allowsPersonalDataRequests() ? ledger.claim(now) : null;
    }

    /** Last boundary before any personal-data HTTP request, including future reads/photos. */
    public static HttpURLConnection openConnection(URL url) throws IOException {
        if (!allowsPersonalDataRequests()) {
            throw new IOException("Cloud transfer is disabled in this local beta");
        }
        return (HttpURLConnection) url.openConnection();
    }

    public static HttpURLConnection openAccountConnection(URL url, String owner, String bearer) throws IOException {
        if (!accountSyncAvailable() || owner == null || !owner.matches("[0-9a-fA-F-]{36}")
                || bearer == null || bearer.trim().isEmpty()) throw new IOException("Sign in to sync points");
        if (!"https".equals(url.getProtocol())
                || !url.getHost().equals(SupabaseConfig.PROJECT_REF + ".supabase.co")
                || url.getUserInfo() != null || url.getPort() != -1 || url.getRef() != null)
            throw new IOException("Invalid account sync project");
        String path = url.getPath();
        String bucket = "fiskentra-backups-v1";
        boolean allowed = "/rest/v1/account_points".equals(path)
                || "/rest/v1/rpc/sync_account_point".equals(path)
                || "/rest/v1/account_backups".equals(path)
                || "/rest/v1/rpc/commit_account_backup".equals(path)
                || ("/storage/v1/bucket/" + bucket).equals(path);
        for (String prefix : new String[]{"/storage/v1/object/", "/storage/v1/object/authenticated/"}) {
            String expected = prefix + bucket + "/" + owner + "/";
            if (path.startsWith(expected) && path.substring(expected.length()).matches("[0-9a-f]{64}")
                    && url.getQuery() == null) allowed = true;
        }
        if (!allowed) throw new IOException("Invalid account sync endpoint");
        return (HttpURLConnection) url.openConnection();
    }
}
