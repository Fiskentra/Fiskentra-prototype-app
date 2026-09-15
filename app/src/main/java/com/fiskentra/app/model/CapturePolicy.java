package com.fiskentra.app.model;

/** Event UTC and fix monotonic time have distinct purposes. No later-fix auto-attachment. */
public final class CapturePolicy {
    private CapturePolicy() { }
    public static boolean canAttach(long occurredAtUtc, long fixUtc, long fixNanos, long nowNanos) {
        return LocationQualityPolicy.fresh(fixNanos, nowNanos)
                && occurredAtUtc - fixUtc <= 30_000L && fixUtc - occurredAtUtc <= 5_000L;
    }
}
