package com.fiskentra.app.search;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Debounced latest-wins requests, including delivery after a failed transport cancellation. */
public final class PlaceSearchController implements AutoCloseable {
    public enum Status { IDLE, WAITING, LOADING, RESULTS, EMPTY, ERROR }
    public static final class State {
        public final Status status;
        public final List<PlaceResult> results;
        public final PlaceSearchProvider.Failure failure;
        State(Status status, List<PlaceResult> results, PlaceSearchProvider.Failure failure) { this.status = status; this.results = results; this.failure = failure; }
    }
    public interface Listener { void changed(State state); }
    public interface Clock { long millis(); }
    private final PlaceSearchProvider provider;
    private final Executor delivery;
    private final ScheduledExecutorService timer;
    private final ExecutorService io;
    private final Clock clock;
    private long generation, retryAt;
    private boolean closed;
    private ScheduledFuture<?> pending, timeout;
    private Future<?> request;
    private PlaceSearchProvider.Cancellation cancellation;
    public PlaceSearchController(PlaceSearchProvider provider, Executor delivery) {
        this(provider, delivery, Executors.newSingleThreadScheduledExecutor(), Executors.newFixedThreadPool(2), () -> System.nanoTime() / 1_000_000);
    }
    public PlaceSearchController(PlaceSearchProvider provider, Executor delivery, ScheduledExecutorService timer, ExecutorService io, Clock clock) {
        this.provider = provider; this.delivery = delivery; this.timer = timer; this.io = io; this.clock = clock;
    }
    public synchronized void search(PlaceSearchProvider.Request query, Listener listener) {
        cancelPending(); if (closed) return; long token = generation;
        if (query.query.codePointCount(0, query.query.length()) < 2) { publish(token, new State(Status.IDLE, Collections.emptyList(), null), listener); return; }
        if (clock.millis() < retryAt) { publish(token, new State(Status.ERROR, Collections.emptyList(), new PlaceSearchProvider.Failure(PlaceSearchProvider.Error.RATE_LIMIT, retryAt - clock.millis())), listener); return; }
        publish(token, new State(Status.WAITING, Collections.emptyList(), null), listener);
        pending = timer.schedule(() -> begin(token, query, listener), 300, TimeUnit.MILLISECONDS);
    }
    private synchronized void begin(long token, PlaceSearchProvider.Request query, Listener listener) {
        if (closed || token != generation) return;
        cancellation = new PlaceSearchProvider.Cancellation(); PlaceSearchProvider.Cancellation abort = cancellation;
        publish(token, new State(Status.LOADING, Collections.emptyList(), null), listener);
        timeout = timer.schedule(() -> expire(token, abort, listener), 10, TimeUnit.SECONDS);
        request = io.submit(() -> {
            try { List<PlaceResult> result = provider.search(query, abort); complete(token, new State(result.isEmpty() ? Status.EMPTY : Status.RESULTS, result, null), listener); }
            catch (PlaceSearchProvider.Failure failure) { complete(token, new State(Status.ERROR, Collections.emptyList(), failure), listener); }
            catch (RuntimeException failure) { complete(token, new State(Status.ERROR, Collections.emptyList(), new PlaceSearchProvider.Failure(PlaceSearchProvider.Error.SERVICE)), listener); }
        });
    }
    private synchronized void complete(long token, State state, Listener listener) {
        if (closed || token != generation) return;
        if (timeout != null) timeout.cancel(false);
        if (state.failure != null && state.failure.category == PlaceSearchProvider.Error.RATE_LIMIT) retryAt = clock.millis() + state.failure.retryAfterMillis;
        publish(token, state, listener);
    }
    private synchronized void expire(long token, PlaceSearchProvider.Cancellation abort, Listener listener) {
        if (closed || token != generation) return;
        abort.cancel(); if (request != null) request.cancel(true);
        long expiredGeneration = ++generation;
        publish(expiredGeneration, new State(Status.ERROR, Collections.emptyList(), new PlaceSearchProvider.Failure(PlaceSearchProvider.Error.TIMEOUT)), listener);
    }
    private void publish(long token, State state, Listener listener) {
        delivery.execute(() -> { synchronized (PlaceSearchController.this) { if (closed || token != generation) return; } listener.changed(state); });
    }
    public synchronized void cancel() { cancelPending(); }
    private void cancelPending() {
        generation++; if (pending != null) pending.cancel(false); if (timeout != null) timeout.cancel(false);
        if (cancellation != null) cancellation.cancel(); if (request != null) request.cancel(true);
    }
    @Override public synchronized void close() { closed = true; cancelPending(); timer.shutdownNow(); io.shutdownNow(); }
}
