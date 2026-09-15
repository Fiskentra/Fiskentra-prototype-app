package com.fiskentra.app.search;

import java.util.List;

public interface PlaceSearchProvider {
    enum Error { OFFLINE, TIMEOUT, RATE_LIMIT, CONFIGURATION, MALFORMED, SERVICE, CANCELLED }
    final class Failure extends Exception {
        public final Error category;
        public final long retryAfterMillis;
        public Failure(Error category) { this(category, 0); }
        public Failure(Error category, long retryAfterMillis) { super(category.name()); this.category = category; this.retryAfterMillis = retryAfterMillis; }
    }
    final class Request {
        public final String query;
        public final double centerLatitude, centerLongitude;
        private final double[] bounds;
        public Request(String query, double centerLatitude, double centerLongitude, double[] explicitBounds) {
            this.query = query == null ? "" : query.trim(); this.centerLatitude = centerLatitude; this.centerLongitude = centerLongitude;
            this.bounds = PlaceResult.validBounds(explicitBounds) ? explicitBounds.clone() : null;
        }
        public double[] bounds() { return bounds == null ? null : bounds.clone(); }
    }
    /** Cancellation owns a single request, so an obsolete request cannot disconnect its successor. */
    final class Cancellation {
        private boolean cancelled;
        private Runnable abort;
        public synchronized boolean cancelled() { return cancelled || Thread.currentThread().isInterrupted(); }
        public synchronized void onCancel(Runnable action) { abort = action; if (cancelled && action != null) action.run(); }
        public synchronized void cancel() { cancelled = true; if (abort != null) abort.run(); }
    }
    List<PlaceResult> search(Request request, Cancellation cancellation) throws Failure;
}
