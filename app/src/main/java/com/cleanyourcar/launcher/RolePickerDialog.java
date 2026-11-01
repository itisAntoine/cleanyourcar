package com.cleanyourcar.launcher;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import com.cleanyourcar.launcher.config.RoleSelection;
import com.cleanyourcar.launcher.config.RoleSelection.Role;

import java.util.List;

/**
 * Lets the user choose the app for one role. Navigation apps are offered only the strategies
 * they actually handle (google.navigation, geo, or open-only); other roles open the package.
 */
final class RolePickerDialog {

    static final String STRATEGY_GOOGLE = "google-navigation";
    static final String STRATEGY_GEO = "geo";
    static final String STRATEGY_OPEN = "launch-package";

    private RolePickerDialog() {
    }

    static String roleLabel(Context context, Role role) {
        switch (role) {
            case NAVIGATION: return I18n.tr(context, I18n.KEY_ROLE_NAVIGATION);
            case BLUETOOTH_MUSIC: return I18n.tr(context, I18n.KEY_ROLE_BT_MUSIC);
            case LOCAL_MUSIC: return I18n.tr(context, I18n.KEY_ROLE_LOCAL_MUSIC);
            case RADIO: return I18n.tr(context, I18n.KEY_ROLE_RADIO);
            case DASHCAM: return I18n.tr(context, I18n.KEY_ROLE_DASHCAM);
            case EQUALIZER: return I18n.tr(context, I18n.KEY_ROLE_EQUALIZER);
            case SETTINGS:
            default: return I18n.tr(context, I18n.KEY_ROLE_SETTINGS);
        }
    }

    static String strategyLabel(Context context, String strategyId) {
        if (STRATEGY_GOOGLE.equals(strategyId)) return I18n.tr(context, I18n.KEY_STRATEGY_GOOGLE);
        if (STRATEGY_GEO.equals(strategyId)) return I18n.tr(context, I18n.KEY_STRATEGY_GEO);
        return I18n.tr(context, I18n.KEY_STRATEGY_OPEN);
    }

    static void show(final Context context, final Role role, final Runnable onChanged) {
        final ThemeConfig.Palette p = ThemeConfig.getActivePalette(context);
        final Dialog dialog = SetupUi.newDialog(context);
        LinearLayout root = SetupUi.card(context, p);
        root.addView(SetupUi.title(context, p, I18n.tr(context, I18n.KEY_PICK_APP) + " — "
                + roleLabel(context, role)));

        CarIntentRouter.ResolvedRole current = CarIntentRouter.resolveRole(context, role);
        String currentPkg = current.isAvailable() ? current.selection.packageName : null;

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        List<AppRepository.AppEntry> apps = new AppRepository().loadAllApps(context);
        for (final AppRepository.AppEntry app : apps) {
            if (!isPickable(app.packageName)) continue;
            SetupUi.addRow(list, SetupUi.button(context, p, app.label,
                    app.packageName.equals(currentPkg), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialog.dismiss();
                            if (role == Role.NAVIGATION) {
                                showStrategies(context, app, onChanged);
                            } else {
                                save(context, role, app.packageName, STRATEGY_OPEN, onChanged);
                            }
                        }
                    }));
        }
        SetupUi.addRow(list, SetupUi.button(context, p, I18n.tr(context, I18n.KEY_CLEAR_CHOICE),
                false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        saveSelection(context, role, null, onChanged);
                    }
                }));
        SetupUi.addRow(list, SetupUi.button(context, p, I18n.tr(context, I18n.KEY_CANCEL), false,
                SetupUi.dismissOnClick(dialog)));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        root.addView(scroll);
        SetupUi.showDialog(dialog, root, false);
    }

    private static void showStrategies(final Context context, final AppRepository.AppEntry app,
            final Runnable onChanged) {
        final ThemeConfig.Palette p = ThemeConfig.getActivePalette(context);
        final Dialog dialog = SetupUi.newDialog(context);
        LinearLayout root = SetupUi.card(context, p);
        root.addView(SetupUi.title(context, p, app.label));
        for (final String strategy : new String[] {STRATEGY_GOOGLE, STRATEGY_GEO, STRATEGY_OPEN}) {
            if (!handles(context, app.packageName, strategy)) continue;
            SetupUi.addRow(root, SetupUi.button(context, p, strategyLabel(context, strategy),
                    false, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialog.dismiss();
                            save(context, Role.NAVIGATION, app.packageName, strategy, onChanged);
                        }
                    }));
        }
        SetupUi.addRow(root, SetupUi.button(context, p, I18n.tr(context, I18n.KEY_CANCEL), false,
                SetupUi.dismissOnClick(dialog)));
        SetupUi.showDialog(dialog, root);
    }

    /** True when the package resolves the strategy's destination scheme (open-only always). */
    private static boolean handles(Context context, String packageName, String strategy) {
        String probe;
        if (STRATEGY_GOOGLE.equals(strategy)) {
            probe = "google.navigation:q=0,0";
        } else if (STRATEGY_GEO.equals(strategy)) {
            probe = "geo:0,0?q=0,0";
        } else {
            return true;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(probe));
        intent.setPackage(packageName);
        try {
            return !context.getPackageManager().queryIntentActivities(intent, 0).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isPickable(String packageName) {
        return !packageName.endsWith(".test") && !packageName.contains("factorytest");
    }

    private static void save(Context context, Role role, String packageName, String strategy,
            Runnable onChanged) {
        saveSelection(context, role,
                new RoleSelection(packageName, null, strategy, versionIdentity(context,
                        packageName)), onChanged);
    }

    private static void saveSelection(Context context, Role role, RoleSelection selection,
            Runnable onChanged) {
        CarIntentRouter.SaveResult result = CarIntentRouter.setRole(context, role, selection);
        int key = result == CarIntentRouter.SaveResult.SAVED ? I18n.KEY_SAVED
                : result == CarIntentRouter.SaveResult.READ_ONLY ? I18n.KEY_READ_ONLY
                : I18n.KEY_SAVE_FAILED;
        Toast.makeText(context, I18n.tr(context, key), Toast.LENGTH_SHORT).show();
        if (onChanged != null) onChanged.run();
    }

    @SuppressWarnings("deprecation")
    private static String versionIdentity(Context context, String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return String.valueOf(code);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }
}
