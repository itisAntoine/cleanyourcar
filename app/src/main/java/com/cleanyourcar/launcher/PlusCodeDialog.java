package com.cleanyourcar.launcher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.cleanyourcar.launcher.layout.LayoutPolicy;

import com.cleanyourcar.launcher.config.ConfigurationStore;
import com.cleanyourcar.launcher.config.NavigationReference;

/**
 * State 5: Offline Plus Code Keypad Modal.
 * Pure-math Open Location Code input (23456789CFGHJMPQRVWX+). Short codes use a fresh current
 * location or the explicitly chosen city preset; full codes need no reference.
 */
public final class PlusCodeDialog {
    private static final int DIALOG_WIDTH_DP = 620;
    private static final int DIALOG_PADDING_DP = LayoutPolicy.KEYPAD_PADDING_DP;
    private static final int KEY_HEIGHT_DP = 58;
    private static final int KEY_MARGIN_DP = LayoutPolicy.KEYPAD_KEY_MARGIN_DP;
    private static final String[] KEYS = new String[] {
            "2", "3", "4", "5", "6", "7", "8",
            "9", "C", "F", "G", "H", "J", "M",
            "P", "Q", "R", "V", "W", "X", "⌫"
    };

    /** Long enough for a full 11-character code typed without '+'. */
    private static final int MAX_CODE_LENGTH = 11;

    private static Dialog activeDialog;
    /** The active dialog is an overlay window shown from the HUD. */
    private static boolean activeFromOverlay;

    private PlusCodeDialog() {
    }

    public static void dismissActive() {
        if (activeDialog != null) {
            try {
                if (activeDialog.isShowing()) {
                    activeDialog.dismiss();
                }
            } catch (Throwable ignored) {
            }
            activeDialog = null;
            activeFromOverlay = false;
        }
    }

    /**
     * Dismisses the keypad only when it was opened from the HUD as an overlay window; used when
     * a HUD session closes (reverse, invalidation, denial) so no overlay dialog outlives it.
     */
    public static void dismissOverlayDialog() {
        if (activeFromOverlay) {
            dismissActive();
        }
    }

    public static void show(final Context context, final boolean fromOverlay) {
        dismissActive();
        final ThemeConfig.Palette p = ThemeConfig.getActivePalette(context);
        final Dialog dialog = new Dialog(context);
        activeDialog = dialog;
        activeFromOverlay = fromOverlay;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (fromOverlay && dialog.getWindow() != null) {
            int type = (android.os.Build.VERSION.SDK_INT >= 26)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            dialog.getWindow().setType(type);
        }

        final StringBuilder buffer = new StringBuilder();
        // -1 means no city was chosen; there is no implicit default city.
        final int[] cityIndex = new int[] {savedCityIndex(context)};

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AdaptiveUi.dp(context, DIALOG_PADDING_DP), AdaptiveUi.dp(context, 18),
                AdaptiveUi.dp(context, DIALOG_PADDING_DP), AdaptiveUi.dp(context, 18));
        root.setBackground(ThemeConfig.createGlassCardDrawable(
                AdaptiveUi.dpf(context, 24f), 0xF2121A2E, 0xF20A0F1D, p.borderLuminous, 2));

        // Header Row: Title + City Selector + Close Button
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText("Nhập mã vị trí Google Maps");
        title.setTextColor(p.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleLp);

        final TextView cityBtn = new TextView(context);
        cityBtn.setText(cityLabel(context, cityIndex[0]));
        cityBtn.setTextColor(p.textSecondary);
        cityBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        cityBtn.setTypeface(Typeface.DEFAULT_BOLD);
        cityBtn.setMinHeight(AdaptiveUi.dp(context, LayoutPolicy.MIN_TOUCH_TARGET_DP));
        cityBtn.setGravity(Gravity.CENTER);
        cityBtn.setPadding(AdaptiveUi.dp(context, 12), AdaptiveUi.dp(context, 8), AdaptiveUi.dp(context, 12), AdaptiveUi.dp(context, 8));
        cityBtn.setBackground(ThemeConfig.createInteractiveTileDrawable(
                AdaptiveUi.dpf(context, 10f), p.tileDefault, p.tilePressed, p.borderSubtle, p.accentPrimary));
        cityBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Cycles through the presets and back to "no city".
                cityIndex[0] = cityIndex[0] + 1 >= PlusCodeCodec.VIETNAM_PRESETS.length
                        ? -1 : cityIndex[0] + 1;
                saveCityIndex(context, cityIndex[0]);
                cityBtn.setText(cityLabel(context, cityIndex[0]));
            }
        });
        header.addView(cityBtn);

        TextView closeBtn = new TextView(context);
        closeBtn.setText("✕");
        closeBtn.setTextColor(p.textPrimary);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setMinHeight(AdaptiveUi.dp(context, LayoutPolicy.MIN_TOUCH_TARGET_DP));
        closeBtn.setMinWidth(AdaptiveUi.dp(context, LayoutPolicy.MIN_TOUCH_TARGET_DP));
        closeBtn.setPadding(AdaptiveUi.dp(context, 14), AdaptiveUi.dp(context, 6), AdaptiveUi.dp(context, 14), AdaptiveUi.dp(context, 6));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = AdaptiveUi.dp(context, 10);
        closeBtn.setBackground(ThemeConfig.createInteractiveTileDrawable(
                AdaptiveUi.dpf(context, 10f), p.tileDefault, p.accentDanger, p.borderSubtle, p.accentDanger));
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        header.addView(closeBtn, closeLp);
        root.addView(header);

        TextView parkedHint = new TextView(context);
        parkedHint.setText(I18n.tr(context, I18n.KEY_SETUP_PARKED));
        parkedHint.setTextColor(p.textSecondary);
        parkedHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        parkedHint.setPadding(0, AdaptiveUi.dp(context, 6), 0, 0);
        root.addView(parkedHint);

        // Input Display + Go CTA Row
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(AdaptiveUi.dp(context, 16), AdaptiveUi.dp(context, 10), AdaptiveUi.dp(context, 10), AdaptiveUi.dp(context, 10));
        inputRow.setBackground(ThemeConfig.createGlassCardDrawable(
                AdaptiveUi.dpf(context, 14f), p.wellBackground, p.wellBackground, p.accentPrimary, 2));
        LinearLayout.LayoutParams inputRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputRowLp.topMargin = AdaptiveUi.dp(context, 12);
        inputRowLp.bottomMargin = AdaptiveUi.dp(context, 12);

        final TextView codeView = new TextView(context);
        codeView.setText("VD: QMVX2P");
        codeView.setTextColor(p.textSecondary);
        codeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        inputRow.addView(codeView, codeLp);

        TextView goBtn = new TextView(context);
        goBtn.setText(I18n.tr(context, I18n.KEY_GO_NOW));
        goBtn.setTextColor(0xFF042412);
        goBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        goBtn.setTypeface(Typeface.DEFAULT_BOLD);
        goBtn.setMinHeight(AdaptiveUi.dp(context, LayoutPolicy.MIN_TOUCH_TARGET_DP));
        goBtn.setGravity(Gravity.CENTER);
        goBtn.setPadding(AdaptiveUi.dp(context, 20), AdaptiveUi.dp(context, 12), AdaptiveUi.dp(context, 20), AdaptiveUi.dp(context, 12));
        goBtn.setBackground(ThemeConfig.createInteractiveTileDrawable(
                AdaptiveUi.dpf(context, 11f), p.accentSuccess, p.accentPrimary, p.borderLuminous, p.textPrimary));
        goBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String raw = buffer.toString().trim();
                NavigationReference.ReferenceResult reference = null;
                if (NavPayloadParser.requiresReference(raw)) {
                    reference = NavDispatchActivity.currentReference(context);
                    if (!reference.available) {
                        Toast.makeText(context, I18n.tr(context, I18n.KEY_NEED_REFERENCE),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                PlusCodeCodec.LatLon decoded = PlusCodeCodec.isValidCode(raw)
                        ? NavPayloadParser.parse(raw, reference) : null;
                if (decoded == null) {
                    Toast.makeText(context, I18n.tr(context, I18n.KEY_INVALID_DESTINATION),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                CarIntentRouter.launchNavigation(context, decoded);
            }
        });
        inputRow.addView(goBtn);
        root.addView(inputRow, inputRowLp);

        // 21-button keypad: 7 columns x 3 rows when it fits; fewer, wider rows in narrow windows.
        // Columns are recomputed whenever the dialog bounds change (rotation, resize).
        final GridLayout grid = new GridLayout(context);
        final TextView[] keyViews = new TextView[KEYS.length];

        for (int i = 0; i < KEYS.length; i++) {
            final String key = KEYS[i];
            TextView keyBtn = new TextView(context);
            keyBtn.setText(key);
            keyBtn.setTextColor(p.textPrimary);
            keyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
            keyBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            keyBtn.setGravity(Gravity.CENTER);
            int fill = "⌫".equals(key) ? 0x66EA2143 : p.tileDefault;
            keyBtn.setBackground(ThemeConfig.createInteractiveTileDrawable(
                    AdaptiveUi.dpf(context, 12f), fill, p.tilePressed, p.borderSubtle, p.accentPrimary));

            keyBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if ("⌫".equals(key)) {
                        if (buffer.length() > 0) {
                            buffer.deleteCharAt(buffer.length() - 1);
                        }
                    } else if (buffer.length() < MAX_CODE_LENGTH) {
                        buffer.append(key);
                    }
                    if (buffer.length() == 0) {
                        codeView.setText("VD: QMVX2P");
                        codeView.setTextColor(p.textSecondary);
                    } else {
                        codeView.setText(buffer.toString());
                        codeView.setTextColor(p.textPrimary);
                    }
                }
            });
            keyViews[i] = keyBtn;
        }
        root.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        AdaptiveUi.showBounded(dialog, root, DIALOG_WIDTH_DP, true,
                new AdaptiveUi.OnBoundsChanged() {
                    @Override
                    public void onBounds(LayoutPolicy.LayoutSpec spec) {
                        layoutKeys(context, grid, keyViews, spec.keypadColumns(DIALOG_WIDTH_DP));
                    }
                });
    }

    private static void layoutKeys(Context context, GridLayout grid, TextView[] keys,
            int columns) {
        if (grid.getChildCount() == keys.length && grid.getColumnCount() == columns) return;
        grid.removeAllViews();
        grid.setColumnCount(columns);
        int keyMargin = AdaptiveUi.dp(context, KEY_MARGIN_DP);
        for (TextView key : keys) {
            GridLayout.LayoutParams klp = new GridLayout.LayoutParams();
            klp.width = 0;
            klp.height = AdaptiveUi.dp(context, KEY_HEIGHT_DP);
            klp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            klp.setMargins(keyMargin, keyMargin, keyMargin, keyMargin);
            grid.addView(key, klp);
        }
    }

    private static int savedCityIndex(Context context) {
        NavigationReference.SavedReference saved =
                ConfigurationStore.get(context).load(context).savedNavigationReference;
        return saved.present ? saved.cityIndex : -1;
    }

    private static void saveCityIndex(Context context, int index) {
        if (index < 0) {
            ThemeConfig.prefs(context).edit().remove(ThemeConfig.KEY_CITY_INDEX).apply();
        } else {
            ThemeConfig.prefs(context).edit().putInt(ThemeConfig.KEY_CITY_INDEX, index).apply();
            // Picking a city here overrides an earlier "None"; current-location mode keeps it
            // as the fallback.
            if (SetupActivity.referenceMode(context) == NavigationReference.Mode.NONE) {
                SetupActivity.setReferenceMode(context, NavigationReference.Mode.CITY);
            }
        }
    }

    private static String cityLabel(Context context, int index) {
        String name = index < 0 ? I18n.tr(context, I18n.KEY_CHOOSE_CITY)
                : PlusCodeCodec.VIETNAM_PRESETS[index].name;
        return "📍 " + name + " ▾";
    }
}
