package com.fiskentra.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fiskentra.app.R;

/** Compact boat guidance controls; secondary actions live in the map menu. */
final class BoatNavigationHud {
    interface Actions {
        void destination();
        void voice();
        void options();
        void stop();
        void choose();
    }

    final LinearLayout turnPanel, routePanel;
    private final Context context;
    private final TextView routeSummary, statusTitle, statusDetail;
    private boolean hasDestination, compact;
    private String mainSummary = "", secondarySummary = "", secondaryStatus = "";
    private static final int NAVY = 0xff001e2e, INK = 0xffeff4ff,
            MUTED = 0xffb7c9e6, BLUE = 0xff00a7ff, LINE = 0xff53778f;

    BoatNavigationHud(Context context, Actions actions) {
        this.context = context;

        routePanel = capsule();
        routePanel.addView(icon(R.drawable.ic_waves, BLUE), iconSpace(20, 8));
        routeSummary = text(14, true, INK);
        routeSummary.setMaxLines(2);
        routePanel.addView(routeSummary, new LinearLayout.LayoutParams(0, -2, 1));
        routePanel.addView(icon(R.drawable.ic_chevron_right, MUTED), iconSpace(16, 0));
        routePanel.setOnClickListener(v -> {
            if (hasDestination) actions.options();
            else actions.choose();
        });

        turnPanel = capsule();
        turnPanel.addView(icon(R.drawable.ic_navigation, BLUE), iconSpace(18, 8));
        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        statusTitle = text(14, true, INK);
        statusDetail = text(11, false, MUTED);
        statusTitle.setMaxLines(1);
        statusDetail.setMaxLines(1);
        copy.addView(statusTitle, new LinearLayout.LayoutParams(-1, -2));
        copy.addView(statusDetail, new LinearLayout.LayoutParams(-1, -2));
        turnPanel.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        turnPanel.setOnClickListener(v -> actions.options());

        profile(false, false);
        status("Boat guidance");
    }

    void profile(boolean hasDestination, boolean voice) {
        this.hasDestination = hasDestination;
        renderSummary();
    }

    void status(String message) {
        String value = message == null ? "" : message.trim();
        String[] lines = value.split("\\r?\\n", 2);
        setText(statusTitle, lines[0].isEmpty() ? "Boat guidance" : lines[0]);
        secondaryStatus = lines.length > 1 ? lines[1].replace('\n', ' ').trim() : "";
        setText(statusDetail, secondaryStatus);
        statusDetail.setVisibility(compact || secondaryStatus.isEmpty() ? View.GONE : View.VISIBLE);
        turnPanel.setContentDescription(value.isEmpty() ? "Boat guidance options" : value + ". Guidance options");
    }

    void compact(boolean value) {
        compact = value;
        statusDetail.setVisibility(value || secondaryStatus.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void summary(String main, String secondary) {
        mainSummary = main == null ? "" : main.trim().replace('\n', ' ');
        secondarySummary = secondary == null ? "" : secondary.trim().replace('\n', ' ');
        renderSummary();
    }

    private void renderSummary() {
        String label = !hasDestination ? "Choose destination"
                : hasReadableValue(mainSummary) ? mainSummary : "Waiting for GPS";
        setText(routeSummary, label);
        routePanel.setContentDescription(!hasDestination ? "Choose boat destination"
                : "Boat guidance, " + label + (secondarySummary.isEmpty() ? "" : ". " + secondarySummary) + ". Open options");
    }

    private boolean hasReadableValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetterOrDigit(value.charAt(i))) return true;
        }
        return false;
    }

    private LinearLayout capsule() {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setMinimumHeight(dp(48));
        panel.setMinimumWidth(dp(48));
        panel.setPadding(dp(12), dp(8), dp(10), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(NAVY);
        background.setCornerRadius(dp(24));
        background.setStroke(dp(1), LINE);
        panel.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3300a7ff), background, null));
        panel.setFocusable(true);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return panel;
    }

    private LinearLayout.LayoutParams iconSpace(int size, int after) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(size), dp(size));
        params.setMarginEnd(dp(after));
        if (after == 0) params.setMarginStart(dp(7));
        return params;
    }

    private ImageView icon(int drawable, int color) {
        ImageView image = new ImageView(context);
        image.setImageResource(drawable);
        image.setColorFilter(color);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    private TextView text(int size, boolean bold, int color) {
        TextView text = new TextView(context);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        text.setIncludeFontPadding(false);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return text;
    }

    private void setText(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
