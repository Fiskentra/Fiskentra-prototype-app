package com.fiskentra.app.backend;

import java.util.ArrayList;
import java.util.List;

/** Deterministic session boundary for the real transport; never loads Android account storage. */
public final class SupabaseAuthManager {
    public static final class Session {
        public final String userId, accessToken;
        public Session(String userId, String accessToken) {
            this.userId = userId;
            this.accessToken = accessToken;
        }
    }

    public final List<Boolean> refreshRequests = new ArrayList<>();
    public Session current, afterRefresh;
    public boolean denyCurrentAccount, failSession;

    public SupabaseAuthManager(Session initial) { current = initial; }

    Session sessionForCloud(boolean forceRefresh) throws Exception {
        refreshRequests.add(forceRefresh);
        if (failSession || current == null) throw new Exception("Fixture session unavailable");
        if (forceRefresh && afterRefresh != null) current = afterRefresh;
        return current;
    }

    boolean isCurrentAccount(String owner) {
        return !denyCurrentAccount && current != null && owner.equals(current.userId);
    }
}
