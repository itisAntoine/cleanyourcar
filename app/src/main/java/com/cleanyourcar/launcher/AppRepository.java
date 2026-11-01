package com.cleanyourcar.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import com.cleanyourcar.launcher.config.ConfigurationStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers installed launchable apps and persists user-customized ordering and visibility
 * (App Library hide/unhide & reorder, inspired by cleanyourtv).
 */
public final class AppRepository {
    private static final String KEY_SAVED_ORDER = ThemeConfig.KEY_APP_SAVED_ORDER;
    private static final String KEY_HIDDEN_PKGS = ThemeConfig.KEY_APP_HIDDEN_PACKAGES;
    private static final String KEY_UNHIDDEN_PKGS = ThemeConfig.KEY_APP_UNHIDDEN_PACKAGES;

    // Default priority order & curated allowlist for essential car apps
    private static final List<String> DEFAULT_PRIORITY_ORDER = Arrays.asList(
            CarIntentRouter.PKG_GOOGLE_MAPS,
            CarIntentRouter.PKG_BT_MUSIC,
            CarIntentRouter.PKG_USB_MUSIC,
            CarIntentRouter.PKG_DASHCAM,
            CarIntentRouter.PKG_EQUALIZER,
            CarIntentRouter.PKG_RADIO,
            "com.android.settings"
    );

    private static final Set<String> DEFAULT_ALLOWED_PACKAGES =
            Collections.unmodifiableSet(new HashSet<>(DEFAULT_PRIORITY_ORDER));

    public static boolean isPackageVisible(
            String packageName, Set<String> explicitlyHidden, Set<String> explicitlyUnhidden) {
        return isPackageVisible(packageName, explicitlyHidden, explicitlyUnhidden,
                DEFAULT_ALLOWED_PACKAGES);
    }

    /**
     * @param defaultVisible packages visible without an explicit choice; null shows every
     *                       discovered launchable app
     */
    public static boolean isPackageVisible(String packageName, Set<String> explicitlyHidden,
            Set<String> explicitlyUnhidden, Set<String> defaultVisible) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (explicitlyHidden != null && explicitlyHidden.contains(packageName)) {
            return false;
        }
        if (explicitlyUnhidden != null && explicitlyUnhidden.contains(packageName)) {
            return true;
        }
        return defaultVisible == null || defaultVisible.contains(packageName);
    }

    /** Curated car-app list for existing users; null (all discovered apps) for generic installs. */
    public static Set<String> defaultVisiblePackages(boolean curatedAppVisibility) {
        return curatedAppVisibility ? DEFAULT_ALLOWED_PACKAGES : null;
    }

    private static Set<String> defaultVisiblePackages(Context context) {
        return defaultVisiblePackages(
                ConfigurationStore.get(context).load(context).curatedAppVisibility);
    }

    public static final class AppEntry {
        public final String packageName;
        public final String className;
        public final String label;
        public final Drawable icon;
        public final boolean hidden;

        public AppEntry(String packageName, String className, String label, Drawable icon, boolean hidden) {
            this.packageName = packageName;
            this.className = className;
            this.label = label;
            this.icon = icon;
            this.hidden = hidden;
        }
    }

    public static List<String> orderAndFilterPackages(
            List<String> installedPkgs,
            List<String> savedOrder,
            Set<String> hiddenPkgs,
            String selfPkg) {
        if (installedPkgs == null) return Collections.emptyList();
        Set<String> hidden = (hiddenPkgs != null) ? hiddenPkgs : Collections.<String>emptySet();
        Set<String> installedSet = new LinkedHashSet<>(installedPkgs);

        List<String> ordered = new ArrayList<>();
        Set<String> placed = new HashSet<>();

        if (savedOrder != null) {
            for (String pkg : savedOrder) {
                if (pkg == null || pkg.equals(selfPkg) || hidden.contains(pkg)) continue;
                if (installedSet.contains(pkg) && placed.add(pkg)) {
                    ordered.add(pkg);
                }
            }
        }

        for (String pkg : installedPkgs) {
            if (pkg == null || pkg.equals(selfPkg) || hidden.contains(pkg)) continue;
            if (placed.add(pkg)) {
                ordered.add(pkg);
            }
        }
        return ordered;
    }

    public List<AppEntry> loadVisibleApps(Context context) {
        List<AppEntry> all = loadAllApps(context);
        List<AppEntry> visible = new ArrayList<>();
        for (AppEntry entry : all) {
            if (!entry.hidden) {
                visible.add(entry);
            }
        }
        return visible;
    }

    public List<AppEntry> loadAllApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolved = pm.queryIntentActivities(mainIntent, 0);
        Map<String, AppEntry> byPkg = new HashMap<>();
        Set<String> hidden = getHiddenPackages(context);
        Set<String> unhidden = getUnhiddenPackages(context);
        Set<String> defaultVisible = defaultVisiblePackages(context);
        String selfPkg = context.getPackageName();

        if (resolved != null) {
            for (ResolveInfo info : resolved) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (pkg == null || pkg.equals(selfPkg) || byPkg.containsKey(pkg)) continue;
                CharSequence rawLabel = info.loadLabel(pm);
                String label = getFriendlyVietnameseLabel(pkg, rawLabel != null ? rawLabel.toString() : pkg);
                Drawable icon = info.loadIcon(pm);
                boolean isVisible = isPackageVisible(pkg, hidden, unhidden, defaultVisible);
                byPkg.put(pkg, new AppEntry(pkg, info.activityInfo.name, label, icon, !isVisible));
            }
        }

        List<String> installed = new ArrayList<>(byPkg.keySet());
        Collections.sort(installed, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return byPkg.get(a).label.compareToIgnoreCase(byPkg.get(b).label);
            }
        });

        List<String> savedOrder = getSavedOrder(context);
        List<String> fullOrder = orderAndFilterPackages(
                installed, savedOrder, Collections.<String>emptySet(), selfPkg);

        List<AppEntry> result = new ArrayList<>();
        for (String pkg : fullOrder) {
            AppEntry item = byPkg.get(pkg);
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }

    private static String getFriendlyVietnameseLabel(String pkg, String fallback) {
        if (CarIntentRouter.PKG_GOOGLE_MAPS.equals(pkg)) return "Google Maps";
        if (CarIntentRouter.PKG_BT_MUSIC.equals(pkg)) return "Nhạc Bluetooth";
        if (CarIntentRouter.PKG_USB_MUSIC.equals(pkg)) return "Nhạc USB";
        if (CarIntentRouter.PKG_DASHCAM.equals(pkg)) return "Camera Trước";
        if (CarIntentRouter.PKG_EQUALIZER.equals(pkg)) return "Equalizer (DSP)";
        if (CarIntentRouter.PKG_RADIO.equals(pkg)) return "Đài FM Radio";
        if ("com.android.settings".equals(pkg)) return "Cài đặt hệ thống";
        return fallback;
    }

    public Set<String> getHiddenPackages(Context context) {
        SharedPreferences prefs = ThemeConfig.prefs(context);
        Set<String> raw = prefs.getStringSet(KEY_HIDDEN_PKGS, null);
        if (raw == null) return new HashSet<>();
        return new HashSet<>(raw);
    }

    public Set<String> getUnhiddenPackages(Context context) {
        SharedPreferences prefs = ThemeConfig.prefs(context);
        Set<String> raw = prefs.getStringSet(KEY_UNHIDDEN_PKGS, null);
        if (raw == null) return new HashSet<>();
        return new HashSet<>(raw);
    }

    public void toggleHidden(Context context, String packageName) {
        Set<String> hidden = getHiddenPackages(context);
        Set<String> unhidden = getUnhiddenPackages(context);
        boolean currentlyVisible = isPackageVisible(
                packageName, hidden, unhidden, defaultVisiblePackages(context));
        if (currentlyVisible) {
            unhidden.remove(packageName);
            hidden.add(packageName);
        } else {
            hidden.remove(packageName);
            unhidden.add(packageName);
        }
        ThemeConfig.prefs(context).edit()
                .putStringSet(KEY_HIDDEN_PKGS, hidden)
                .putStringSet(KEY_UNHIDDEN_PKGS, unhidden)
                .apply();
    }

    public List<String> getSavedOrder(Context context) {
        String csv = ThemeConfig.prefs(context).getString(KEY_SAVED_ORDER, null);
        if (csv == null || csv.trim().isEmpty()) {
            return new ArrayList<>(DEFAULT_PRIORITY_ORDER);
        }
        String[] parts = csv.split(",");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) list.add(trimmed);
        }
        return list;
    }

    public void moveApp(Context context, String packageName, int delta) {
        List<AppEntry> all = loadAllApps(context);
        List<String> pkgs = new ArrayList<>();
        for (AppEntry e : all) {
            pkgs.add(e.packageName);
        }
        int idx = pkgs.indexOf(packageName);
        if (idx < 0) return;
        int target = idx + delta;
        if (target < 0 || target >= pkgs.size()) return;
        Collections.swap(pkgs, idx, target);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkgs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(pkgs.get(i));
        }
        ThemeConfig.prefs(context).edit().putString(KEY_SAVED_ORDER, sb.toString()).apply();
    }
}
