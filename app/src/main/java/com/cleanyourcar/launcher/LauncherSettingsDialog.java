package com.cleanyourcar.launcher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.cleanyourcar.launcher.layout.LayoutPolicy;

/**
 * Multi-user / multi-preference customization dialog with inline expandable dropdowns
 * and live preview (stays open while the user explores themes, opacity, dock side, and grid size):
 * - Visual Theme (Apple Sapphire, Ultraviolet Prism, Titanium & Ember)
 * - Glass Opacity (Crystal, Liquid, Frosted, Solid)
 * - Dock Side (Left vs Right)
 * - Dashboard Column Swap (Music Left vs Navigation Left)
 * - App Grid Columns (3, 4, or 5 columns)
 */
public final class LauncherSettingsDialog {
    private static final int DIALOG_WIDTH_DP = 650;
    public interface OnSettingsChangedListener {
        void onSettingsChanged();
    }

    private LauncherSettingsDialog() {
    }

    public static void show(final Context context, final OnSettingsChangedListener listener) {
        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Remember which dropdown row is currently expanded (-1 = none)
        final int[] expandedSection = new int[] { -1 };

        final LinearLayout outerHost = new LinearLayout(context);
        outerHost.setOrientation(LinearLayout.VERTICAL);

        final Runnable[] renderRef = new Runnable[1];
        renderRef[0] = new Runnable() {
            @Override
            public void run() {
                outerHost.removeAllViews();
                final ThemeConfig.Palette p = ThemeConfig.getActivePalette(context);

                LinearLayout root = new LinearLayout(context);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setPadding(AdaptiveUi.dp(context, 22), AdaptiveUi.dp(context, 18), AdaptiveUi.dp(context, 22), AdaptiveUi.dp(context, 18));
                root.setBackground(ThemeConfig.createGlassCardDrawable(
                        AdaptiveUi.dpf(context, 24f), p.dockTop, p.dockBottom, p.borderLuminous, 2));

                // Header
                LinearLayout header = new LinearLayout(context);
                header.setOrientation(LinearLayout.HORIZONTAL);
                header.setGravity(Gravity.CENTER_VERTICAL);

                TextView title = new TextView(context);
                title.setText(I18n.tr(context, I18n.KEY_SETTINGS_TITLE));
                title.setTextColor(p.textPrimary);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                TextView closeBtn = new TextView(context);
                closeBtn.setText(I18n.tr(context, I18n.KEY_CLOSE));
                closeBtn.setTextColor(p.textPrimary);
                closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
                closeBtn.setPadding(AdaptiveUi.dp(context, 16), AdaptiveUi.dp(context, 8), AdaptiveUi.dp(context, 16), AdaptiveUi.dp(context, 8));
closeBtn.setMinHeight(AdaptiveUi.dp(context, LayoutPolicy.MIN_TOUCH_TARGET_DP));
                closeBtn.setMinWidth(AdaptiveUi.dp(context, LayoutPolicy.MIN_TOUCH_TARGET_DP));
                closeBtn.setGravity(Gravity.CENTER);
                closeBtn.setBackground(ThemeConfig.createInteractiveTileDrawable(
                        999f, p.tileDefault, p.accentDanger, p.borderSubtle, p.accentDanger));
                closeBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                    }
                });
                header.addView(closeBtn);
                root.addView(header);

                ScrollView scroll = new ScrollView(context);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                slp.topMargin = AdaptiveUi.dp(context, 10);

                LinearLayout list = new LinearLayout(context);
                list.setOrientation(LinearLayout.VERTICAL);

                // 0. Language Dropdown (Tiếng Việt, English, 中文, 日本語, 한국어, Español)
                int currentLang = ThemeConfig.getLanguageId(context);
                addDropdownSection(
                        context,
                        list,
                        p,
                        0,
                        expandedSection,
                        I18n.tr(context, I18n.KEY_LANGUAGE_ROW),
                        I18n.LANGUAGE_NAMES[currentLang],
                        I18n.LANGUAGE_NAMES,
                        currentLang,
                        new OnOptionSelected() {
                            @Override
                            public void onSelect(int index) {
                                ThemeConfig.setLanguageId(context, index);
                                if (listener != null) listener.onSettingsChanged();
                                renderRef[0].run();
                            }
                        },
                        renderRef[0]
                );

                // 1. Theme Dropdown
                int currentTheme = ThemeConfig.getThemeId(context);
                addDropdownSection(
                        context,
                        list,
                        p,
                        1,
                        expandedSection,
                        I18n.tr(context, I18n.KEY_THEME_ROW),
                        p.name,
                        new String[] {
                                "Liquid Glass — Apple Sapphire",
                                "Liquid Glass — Ultraviolet Prism",
                                "Liquid Glass — Titanium & Ember"
                        },
                        currentTheme,
                        new OnOptionSelected() {
                            @Override
                            public void onSelect(int index) {
                                ThemeConfig.setThemeId(context, index);
                                if (listener != null) listener.onSettingsChanged();
                                renderRef[0].run();
                            }
                        },
                        renderRef[0]
                );

                // 2. Glass Opacity Dropdown
                int currentOpacity = ThemeConfig.getGlassOpacity(context);
                addDropdownSection(
                        context,
                        list,
                        p,
                        2,
                        expandedSection,
                        I18n.tr(context, I18n.KEY_OPACITY_ROW),
                        ThemeConfig.getOpacityLabel(currentOpacity),
                        new String[] {
                                ThemeConfig.getOpacityLabel(ThemeConfig.OPACITY_CRYSTAL),
                                ThemeConfig.getOpacityLabel(ThemeConfig.OPACITY_BALANCED),
                                ThemeConfig.getOpacityLabel(ThemeConfig.OPACITY_FROSTED),
                                ThemeConfig.getOpacityLabel(ThemeConfig.OPACITY_SOLID)
                        },
                        currentOpacity,
                        new OnOptionSelected() {
                            @Override
                            public void onSelect(int index) {
                                ThemeConfig.setGlassOpacity(context, index);
                                if (listener != null) listener.onSettingsChanged();
                                renderRef[0].run();
                            }
                        },
                        renderRef[0]
                );

                // 3. Dock Position Dropdown
                boolean dockLeft = ThemeConfig.isDockLeft(context);
                addDropdownSection(
                        context,
                        list,
                        p,
                        3,
                        expandedSection,
                        I18n.tr(context, I18n.KEY_DOCK_SIDE_ROW),
                        dockLeft ? "Bên Trái (Left)" : "Bên Phải (Right)",
                        new String[] {
                                "Bên Trái (Left)",
                                "Bên Phải (Right)"
                        },
                        dockLeft ? 0 : 1,
                        new OnOptionSelected() {
                            @Override
                            public void onSelect(int index) {
                                ThemeConfig.setDockLeft(context, index == 0);
                                if (listener != null) listener.onSettingsChanged();
                                renderRef[0].run();
                            }
                        },
                        renderRef[0]
                );

                // 4. Dashboard Column Layout Dropdown
                boolean swapped = ThemeConfig.isSwapDashboardColumns(context);
                addDropdownSection(
                        context,
                        list,
                        p,
                        4,
                        expandedSection,
                        I18n.tr(context, I18n.KEY_DASH_LAYOUT_ROW),
                        swapped ? "Google Maps Left • Music Right" : "Music Left • Google Maps Right",
                        new String[] {
                                "Music Left • Google Maps Right",
                                "Google Maps Left • Music Right"
                        },
                        swapped ? 1 : 0,
                        new OnOptionSelected() {
                            @Override
                            public void onSelect(int index) {
                                ThemeConfig.setSwapDashboardColumns(context, index == 1);
                                if (listener != null) listener.onSettingsChanged();
                                renderRef[0].run();
                            }
                        },
                        renderRef[0]
                );

                // 5. App Grid Columns Dropdown (3 / 4 / 5)
                int cols = ThemeConfig.getGridColumns(context);
                addDropdownSection(
                        context,
                        list,
                        p,
                        5,
                        expandedSection,
                        I18n.tr(context, I18n.KEY_GRID_COLS_ROW),
                        cols + " × 2 (" + (cols * 2) + " apps)",
                        new String[] {
                                "3 × 2 (6 apps)",
                                "4 × 2 (8 apps)",
                                "5 × 2 (10 apps)"
                        },
                        cols - 3,
                        new OnOptionSelected() {
                            @Override
                            public void onSelect(int index) {
                                ThemeConfig.setGridColumns(context, index + 3);
                                if (listener != null) listener.onSettingsChanged();
                                renderRef[0].run();
                            }
                        },
                        renderRef[0]
                );

                SetupUi.addRow(list, SetupUi.button(context, p,
                        I18n.tr(context, I18n.KEY_SETUP_ENTRY), false,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                dialog.dismiss();
                                context.startActivity(new android.content.Intent(context,
                                        SetupActivity.class));
                            }
                        }));

                TextView hint = new TextView(context);
                hint.setText("Chạm vào mục bất kỳ để xổ danh sách chọn nhanh — giao diện tự cập nhật trực tiếp phía sau.");
                hint.setTextColor(p.textSecondary);
                hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                hlp.topMargin = AdaptiveUi.dp(context, 10);
                list.addView(hint, hlp);
                scroll.addView(list);
                root.addView(scroll, slp);

                outerHost.addView(root);
            }
        };

        renderRef[0].run();
        AdaptiveUi.showBounded(dialog, outerHost, DIALOG_WIDTH_DP, false);
    }

    private interface OnOptionSelected {
        void onSelect(int index);
    }

    private static void addDropdownSection(
            Context context,
            LinearLayout parent,
            ThemeConfig.Palette p,
            final int sectionId,
            final int[] expandedSection,
            String labelText,
            String currentValueText,
            String[] options,
            int selectedIndex,
            final OnOptionSelected callback,
            final Runnable rerender) {
        final boolean isExpanded = (expandedSection[0] == sectionId);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackground(ThemeConfig.createGlassCardDrawable(
                AdaptiveUi.dpf(context, 16f), p.tileDefault, 0x10FFFFFF, isExpanded ? p.accentPrimary : p.borderSubtle, isExpanded ? 2 : 1));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = AdaptiveUi.dp(context, 8);

        // Header trigger row
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setMinimumHeight(AdaptiveUi.dp(context, LayoutPolicy.MIN_TOUCH_TARGET_DP));
        headerRow.setPadding(AdaptiveUi.dp(context, 16), AdaptiveUi.dp(context, 12), AdaptiveUi.dp(context, 16), AdaptiveUi.dp(context, 12));
        headerRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expandedSection[0] = isExpanded ? -1 : sectionId;
                rerender.run();
            }
        });

        TextView label = new TextView(context);
        label.setText(labelText);
        label.setTextColor(p.textPrimary);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(context);
        val.setText(currentValueText + (isExpanded ? "  ▴" : "  ▾"));
        val.setTextColor(p.textSecondary);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(val);

        container.addView(headerRow);

        // Expanded Dropdown Options List
        if (isExpanded) {
            LinearLayout dropdownMenu = new LinearLayout(context);
            dropdownMenu.setOrientation(LinearLayout.VERTICAL);
            dropdownMenu.setPadding(AdaptiveUi.dp(context, 12), AdaptiveUi.dp(context, 4), AdaptiveUi.dp(context, 12), AdaptiveUi.dp(context, 12));

            for (int i = 0; i < options.length; i++) {
                final int optionIdx = i;
                boolean isSelected = (i == selectedIndex);

                TextView optView = new TextView(context);
                optView.setText((isSelected ? "✓  " : "     ") + options[i]);
                optView.setTextColor(p.textPrimary);
                optView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                optView.setTypeface(isSelected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                optView.setMinHeight(AdaptiveUi.dp(context, LayoutPolicy.MIN_TOUCH_TARGET_DP));
                optView.setGravity(Gravity.CENTER_VERTICAL);
                optView.setPadding(AdaptiveUi.dp(context, 14), AdaptiveUi.dp(context, 10), AdaptiveUi.dp(context, 14), AdaptiveUi.dp(context, 10));

                int optFill = isSelected ? p.accentPrimary : p.wellBackground;
                optView.setBackground(ThemeConfig.createInteractiveTileDrawable(
                        AdaptiveUi.dpf(context, 11f), optFill, p.tilePressed, isSelected ? p.borderLuminous : p.borderSubtle, p.accentPrimary));

                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                olp.topMargin = AdaptiveUi.dp(context, 6);
                optView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        callback.onSelect(optionIdx);
                    }
                });
                dropdownMenu.addView(optView, olp);
            }
            container.addView(dropdownMenu);
        }

        parent.addView(container, clp);
    }
}
