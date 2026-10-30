package com.cleanyourcar.launcher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view factory shared by the setup screens, matching the launcher's glass style. */
final class SetupUi {

    private SetupUi() {
    }

    /** Preferred setup dialog width; bounded by the window in {@link #showDialog}. */
    static final int DIALOG_WIDTH_DP = 650;

    static int dp(Context context, int value) {
        return AdaptiveUi.dp(context, value);
    }

    static LinearLayout card(Context context, ThemeConfig.Palette p) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 22), dp(context, 18), dp(context, 22), dp(context, 18));
        card.setBackground(ThemeConfig.createGlassCardDrawable(
                AdaptiveUi.dpf(context, 24f), p.dockTop, p.dockBottom, p.borderLuminous, 2));
        return card;
    }

    static TextView title(Context context, ThemeConfig.Palette p, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(p.textPrimary);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    static TextView section(Context context, ThemeConfig.Palette p, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(p.textPrimary);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(context, 18), 0, dp(context, 4));
        return view;
    }

    static TextView note(Context context, ThemeConfig.Palette p, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(p.textSecondary);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        view.setPadding(0, dp(context, 4), 0, dp(context, 4));
        return view;
    }

    /** Full-width row button with at least a 48dp touch target. */
    static TextView button(Context context, ThemeConfig.Palette p, String text, boolean selected,
            View.OnClickListener onClick) {
        TextView view = new TextView(context);
        view.setText((selected ? "✓  " : "") + text);
        view.setTextColor(p.textPrimary);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        view.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(context, 48));
        view.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        view.setBackground(ThemeConfig.createInteractiveTileDrawable(
                AdaptiveUi.dpf(context, 11f),
                selected ? p.accentPrimary : p.wellBackground, p.tilePressed,
                selected ? p.borderLuminous : p.borderSubtle, p.accentPrimary));
        view.setOnClickListener(onClick);
        return view;
    }

    static void addRow(LinearLayout parent, View child) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(parent.getContext(), 6);
        parent.addView(child, lp);
    }

    static Dialog newDialog(Context context) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return dialog;
    }

    static View.OnClickListener dismissOnClick(final Dialog dialog) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        };
    }

    /** Shows a window-bounded dialog whose whole content scrolls when it does not fit. */
    static void showDialog(Dialog dialog, View root) {
        AdaptiveUi.showBounded(dialog, root, DIALOG_WIDTH_DP, true);
    }
}
