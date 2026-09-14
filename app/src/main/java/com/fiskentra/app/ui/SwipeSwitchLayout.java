package com.fiskentra.app.ui;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Horizontal page switch that preserves ordinary vertical scrolling and map panning. */
public final class SwipeSwitchLayout extends FrameLayout {
    public interface Listener {
        void onSwipeLeft();
        void onSwipeRight();
    }

    private final int touchSlop;
    private final int minimumDistance;
    private final int edgeWidth;
    private final int headerHeight;
    private float downX;
    private float downY;
    private boolean edgeStart;
    private boolean tracking;
    private boolean multiTouch;
    private boolean leftSwipeRequiresEdge;
    private Listener listener;

    public SwipeSwitchLayout(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        minimumDistance = Math.round(72f * density);
        edgeWidth = Math.round(48f * density);
        headerHeight = Math.round(88f * density);
    }

    public void configure(boolean leftSwipeRequiresEdge, Listener listener) {
        this.leftSwipeRequiresEdge = leftSwipeRequiresEdge;
        this.listener = listener;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) multiTouch = false;
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) { multiTouch = true; tracking = false; }
        return super.dispatchTouchEvent(event);
    }

    @Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
        // MapLibre owns gestures in the map body; reserve the header/edges for page switching.
        super.requestDisallowInterceptTouchEvent(disallow && !(leftSwipeRequiresEdge && edgeStart && !multiTouch));
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            edgeStart = downX >= getWidth() - edgeWidth || downX <= edgeWidth || downY <= headerHeight;
            tracking = false;
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !multiTouch) {
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            boolean horizontal = Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.25f;
            boolean allowed = !leftSwipeRequiresEdge || edgeStart;
            if (horizontal && allowed) {
                tracking = true;
                return true;
            }
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!tracking || multiTouch) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - downX;
            if (Math.abs(dx) >= minimumDistance && listener != null) {
                if (dx < 0f) listener.onSwipeLeft();
                else listener.onSwipeRight();
            }
            performClick();
            tracking = false;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) tracking = false;
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
