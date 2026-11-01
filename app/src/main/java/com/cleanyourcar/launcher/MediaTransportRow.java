package com.cleanyourcar.launcher;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import com.cleanyourcar.launcher.layout.LayoutPolicy;

/** Builds the three media controls with a scrollable minimum-width row on narrow windows. */
final class MediaTransportRow {

    private MediaTransportRow() {
    }

    static HorizontalScrollView create(Context context, LayoutPolicy.LayoutSpec spec,
            View previous, View playPause, View next) {
        HorizontalScrollView viewport = new HorizontalScrollView(context);
        viewport.setHorizontalScrollBarEnabled(false);
        viewport.setFillViewport(true);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int width = AdaptiveUi.dp(context, spec.transportButtonWidthDp);
        int height = AdaptiveUi.dp(context, spec.transportHeightDp);
        int gap = AdaptiveUi.dp(context, LayoutPolicy.TRANSPORT_GAP_DP);
        addButton(row, previous, width, height, gap);
        addButton(row, playPause, width, height, gap);
        addButton(row, next, width, height, 0);

        viewport.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return viewport;
    }

    private static void addButton(LinearLayout row, View button, int width, int height,
            int rightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.rightMargin = rightMargin;
        button.setMinimumWidth(width);
        button.setMinimumHeight(height);
        row.addView(button, params);
    }
}
