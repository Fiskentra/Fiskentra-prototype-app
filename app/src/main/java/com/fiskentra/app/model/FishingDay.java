package com.fiskentra.app.model;

public final class FishingDay {
    public final long id;
    public final long startedAt;
    public final long endedAt;

    public FishingDay(long id, long startedAt, long endedAt) {
        this.id = id;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }

    public boolean isActive() {
        return endedAt <= 0L;
    }

    public long effectiveEnd(long now) {
        return isActive() ? now : endedAt;
    }
}
