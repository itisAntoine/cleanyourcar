package com.cleanyourcar.launcher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.cleanyourcar.launcher.layout.LayoutPolicy;

import java.util.List;

/**
 * Modal dialog allowing the user to show/hide installed apps and reorder them on the App Grid
 * (matching cleanyourtv's App Library & Reorder philosophy).
 */
public final class AppLibraryDialog {
    private static final int DIALOG_WIDTH_DP = 660;
    private static final int LIST_HEIGHT_DP = 390;
    public interface OnChangedListener {
        void onAppsUpdated();
    }

    private AppLibraryDialog() {
    }

    public static void show(final Context context, final AppRepository repo, final OnChangedListener listener) {
        final ThemeConfig.Palette p = ThemeConfig.getActivePalette(context);
        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AdaptiveUi.dp(context, 20), AdaptiveUi.dp(context, 16), AdaptiveUi.dp(context, 20), AdaptiveUi.dp(context, 16));
        root.setBackground(ThemeConfig.createGlassCardDrawable(
                AdaptiveUi.dpf(context, 22f), 0xF210172A, 0xF2090D18, p.borderLuminous, 2));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText("➕ THƯ VIỆN ỨNG DỤNG (ẨN / HIỆN & SẮP XẾP)");
        title.setTextColor(p.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView closeBtn = new TextView(context);
        closeBtn.setText("Đóng ✕");
        closeBtn.setTextColor(p.textPrimary);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        closeBtn.setPadding(AdaptiveUi.dp(context, 16), AdaptiveUi.dp(context, 8), AdaptiveUi.dp(context, 16), AdaptiveUi.dp(context, 8));
        closeBtn.setMinHeight(AdaptiveUi.dp(context, LayoutPolicy.MIN_TOUCH_TARGET_DP));
        closeBtn.setMinWidth(AdaptiveUi.dp(context, LayoutPolicy.MIN_TOUCH_TARGET_DP));
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setBackground(ThemeConfig.createInteractiveTileDrawable(
                AdaptiveUi.dpf(context, 10f), p.tileDefault, p.accentDanger, p.borderSubtle, p.accentDanger));
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        header.addView(closeBtn);
        root.addView(header);

        // The list keeps its designed 390dp height but shrinks with the window-bounded dialog.
        int listHeight = AdaptiveUi.dp(context, LIST_HEIGHT_DP);
        AdaptiveUi.MaxHeightFrame listFrame = new AdaptiveUi.MaxHeightFrame(context, listHeight);
        listFrame.setMinimumHeight(listHeight);
        ScrollView scroll = new ScrollView(context);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = AdaptiveUi.dp(context, 12);

        final LinearLayout listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer);
        listFrame.addView(scroll);
        root.addView(listFrame, scrollLp);

        final Runnable populate = new Runnable() {
            @Override
            public void run() {
                listContainer.removeAllViews();
                List<AppRepository.AppEntry> all = repo.loadAllApps(context);
                for (int i = 0; i < all.size(); i++) {
                    final AppRepository.AppEntry entry = all.get(i);
                    LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(AdaptiveUi.dp(context, 12), AdaptiveUi.dp(context, 8), AdaptiveUi.dp(context, 12), AdaptiveUi.dp(context, 8));
                    row.setBackground(ThemeConfig.createGlassCardDrawable(
                            AdaptiveUi.dpf(context, 12f), p.tileDefault, 0x10FFFFFF, p.borderSubtle, 1));
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    rlp.bottomMargin = AdaptiveUi.dp(context, 8);

                    if (entry.icon != null) {
                        ImageView iconView = new ImageView(context);
                        iconView.setImageDrawable(entry.icon);
                        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(AdaptiveUi.dp(context, 40), AdaptiveUi.dp(context, 40));
                        ilp.rightMargin = AdaptiveUi.dp(context, 12);
                        row.addView(iconView, ilp);
                    }

                    LinearLayout textCol = new LinearLayout(context);
                    textCol.setOrientation(LinearLayout.VERTICAL);
                    TextView nameView = new TextView(context);
                    nameView.setText(entry.label);
                    nameView.setTextColor(entry.hidden ? p.textSecondary : p.textPrimary);
                    nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                    nameView.setTypeface(Typeface.DEFAULT_BOLD);
                    nameView.setSingleLine(true);
                    nameView.setEllipsize(TextUtils.TruncateAt.END);
                    textCol.addView(nameView);

                    TextView pkgView = new TextView(context);
                    pkgView.setText(entry.packageName);
                    pkgView.setTextColor(p.textSecondary);
                    pkgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
                    pkgView.setSingleLine(true);
                    textCol.addView(pkgView);

                    row.addView(textCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                    // Move Up button
                    TextView upBtn = createActionChip(context, p, "▲", p.tileDefault);
                    upBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            repo.moveApp(context, entry.packageName, -1);
                            if (listener != null) listener.onAppsUpdated();
                            run();
                        }
                    });
                    row.addView(upBtn);

                    // Move Down button
                    TextView downBtn = createActionChip(context, p, "▼", p.tileDefault);
                    downBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            repo.moveApp(context, entry.packageName, 1);
                            if (listener != null) listener.onAppsUpdated();
                            run();
                        }
                    });
                    row.addView(downBtn);

                    // Show / Hide toggle button
                    int toggleFill = entry.hidden ? 0x55EA2143 : 0x5507CA6B;
                    String toggleLabel = entry.hidden ? "Đang ẩn" : "Đang hiện";
                    TextView visBtn = createActionChip(context, p, toggleLabel, toggleFill);
                    visBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            repo.toggleHidden(context, entry.packageName);
                            if (listener != null) listener.onAppsUpdated();
                            run();
                        }
                    });
                    row.addView(visBtn);

                    listContainer.addView(row, rlp);
                }
            }
        };
        populate.run();

        AdaptiveUi.showBounded(dialog, root, DIALOG_WIDTH_DP, false);
    }

    private static TextView createActionChip(Context context, ThemeConfig.Palette p, String text, int fill) {
        TextView btn = new TextView(context);
        btn.setText(text);
        btn.setTextColor(p.textPrimary);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setMinHeight(AdaptiveUi.dp(context, LayoutPolicy.MIN_TOUCH_TARGET_DP));
        btn.setMinWidth(AdaptiveUi.dp(context, LayoutPolicy.MIN_TOUCH_TARGET_DP));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(AdaptiveUi.dp(context, 12), AdaptiveUi.dp(context, 8), AdaptiveUi.dp(context, 12), AdaptiveUi.dp(context, 8));
        btn.setBackground(ThemeConfig.createInteractiveTileDrawable(
                AdaptiveUi.dpf(context, 8f), fill, p.tilePressed, p.borderSubtle, p.accentPrimary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = AdaptiveUi.dp(context, 6);
        btn.setLayoutParams(lp);
        return btn;
    }
}
