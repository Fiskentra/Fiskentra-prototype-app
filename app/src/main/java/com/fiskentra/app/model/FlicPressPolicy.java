package com.fiskentra.app.model;

/** Maps raw Flic 2 gestures to Fiskentra field actions without Android dependencies. */
public final class FlicPressPolicy {
    public enum Action { NONE, WAYPOINT, CATCH, TACKLE_CHANGE }

    private FlicPressPolicy() { }

    public static Action resolve(boolean singleClick, boolean doubleClick, boolean hold) {
        if (hold) return Action.TACKLE_CHANGE;
        if (doubleClick) return Action.CATCH;
        if (singleClick) return Action.WAYPOINT;
        return Action.NONE;
    }
}
