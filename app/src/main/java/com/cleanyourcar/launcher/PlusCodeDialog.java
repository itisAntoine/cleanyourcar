package com.cleanyourcar.launcher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * State 5: Offline 6-Character Plus Code Keypad Modal.
 * Pure-math Open Location Code input (23456789CFGHJMPQRVWX+) with Vietnamese city preset selector.
 */
public final class PlusCodeDialog {
    private static final String[] KEYS = new String[] {
            "2", "3", "4", "5", "6", "7", "8",
            "9", "C", "F", "G", "H", "J", "M",
            "P", "Q", "R", "V", "W", "X", "⌫"
    };

    private static Dialog activeDialog;

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
        }
    }

    public static void show(final Context context, final boolean fromOverlay) {
        dismissActive();
        final ThemeConfig.Palette p = ThemeConfig.getActivePalette(context);
        final Dialog dialog = new Dialog(context);
        activeDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (fromOverlay && dialog.getWindow() != null) {
            int type = (android.os.Build.VERSION.SDK_INT >= 26)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            dialog.getWindow().setType(type);
        }

        final StringBuilder buffer = new StringBuilder();
        final int[] cityIndex = new int[] {
                ThemeConfig.prefs(context).getInt(ThemeConfig.KEY_CITY_INDEX, 0)
        };
        if (cityIndex[0] < 0 || cityIndex[0] >= PlusCodeCodec.VIETNAM_PRESETS.length) {
            cityIndex[0] = 0;
        }

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(22, 18, 22, 18);
        root.setBackground(ThemeConfig.createGlassCardDrawable(
                24f, 0xF2121A2E, 0xF20A0F1D, p.borderLuminous, 2));

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
        cityBtn.setText("📍 " + PlusCodeCodec.VIETNAM_PRESETS[cityIndex[0]].name + " ▾");
        cityBtn.setTextColor(p.textSecondary);
        cityBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        cityBtn.setTypeface(Typeface.DEFAULT_BOLD);
        cityBtn.setPadding(12, 8, 12, 8);
        cityBtn.setBackground(ThemeConfig.createInteractiveTileDrawable(
                10f, p.tileDefault, p.tilePressed, p.borderSubtle, p.accentPrimary));
        cityBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cityIndex[0] = (cityIndex[0] + 1) % PlusCodeCodec.VIETNAM_PRESETS.length;
                ThemeConfig.prefs(context).edit().putInt(ThemeConfig.KEY_CITY_INDEX, cityIndex[0]).apply();
                cityBtn.setText("📍 " + PlusCodeCodec.VIETNAM_PRESETS[cityIndex[0]].name + " ▾");
            }
        });
        header.addView(cityBtn);

        TextView closeBtn = new TextView(context);
        closeBtn.setText("✕");
        closeBtn.setTextColor(p.textPrimary);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(14, 6, 14, 6);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = 10;
        closeBtn.setBackground(ThemeConfig.createInteractiveTileDrawable(
                10f, p.tileDefault, p.accentDanger, p.borderSubtle, p.accentDanger));
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        header.addView(closeBtn, closeLp);
        root.addView(header);

        // Input Display + Go CTA Row
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(16, 10, 10, 10);
        inputRow.setBackground(ThemeConfig.createGlassCardDrawable(
                14f, p.wellBackground, p.wellBackground, p.accentPrimary, 2));
        LinearLayout.LayoutParams inputRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputRowLp.topMargin = 12;
        inputRowLp.bottomMargin = 12;

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
        goBtn.setPadding(20, 12, 20, 12);
        goBtn.setBackground(ThemeConfig.createInteractiveTileDrawable(
                11f, p.accentSuccess, p.accentPrimary, p.borderLuminous, p.textPrimary));
        goBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String raw = buffer.toString().trim();
                PlusCodeCodec.CityPreset preset = PlusCodeCodec.VIETNAM_PRESETS[cityIndex[0]];
                double refLat = preset.lat;
                double refLon = preset.lon;
                try {
                    LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                    if (lm != null) {
                        Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                        if (loc != null && Math.abs(loc.getLatitude()) > 0.1) {
                            refLat = loc.getLatitude();
                            refLon = loc.getLongitude();
                        }
                    }
                } catch (SecurityException ignored) {
                }

                PlusCodeCodec.LatLon decoded = PlusCodeCodec.decode(raw, refLat, refLon);
                if (decoded == null) {
                    Toast.makeText(context, "Hãy nhập đủ 6 ký tự (VD: QMVX2P)", Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                CarIntentRouter.launchCoordinatesInMapsLive(context, decoded.lat, decoded.lon, decoded.label);
            }
        });
        inputRow.addView(goBtn);
        root.addView(inputRow, inputRowLp);

        // 21-button Keypad Grid (7 columns x 3 rows — clean symmetric layout, no '+' needed)
        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(7);
        grid.setRowCount(3);

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
                    12f, fill, p.tilePressed, p.borderSubtle, p.accentPrimary));

            GridLayout.LayoutParams klp = new GridLayout.LayoutParams();
            klp.width = 74;
            klp.height = 58;
            klp.setMargins(5, 5, 5, 5);
            keyBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if ("⌫".equals(key)) {
                        if (buffer.length() > 0) {
                            buffer.deleteCharAt(buffer.length() - 1);
                        }
                    } else if (buffer.length() < 6) {
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
            grid.addView(keyBtn, klp);
        }
        root.addView(grid);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
            dialog.getWindow().setLayout(620, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }
}
