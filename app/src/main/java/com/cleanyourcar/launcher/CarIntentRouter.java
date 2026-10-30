package com.cleanyourcar.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import com.cleanyourcar.launcher.config.ConfigurationStore;
import com.cleanyourcar.launcher.config.LauncherConfiguration;
import com.cleanyourcar.launcher.config.RoleSelection;
import com.cleanyourcar.launcher.config.RoleSelection.Role;

import java.io.IOException;
import java.util.Locale;

/**
 * Routes user actions to the head unit's target apps (Google Maps, Bluetooth Music, USB Music,
 * Front Dashcam, Equalizer/DSP, Brightness, and Night Clock). Role apps are resolved through
 * {@link ConfigurationStore}, with graceful fallbacks on standard Android devices/emulators.
 */
public final class CarIntentRouter {
    public static final String PKG_GOOGLE_MAPS = "com.google.android.apps.maps";
    public static final String PKG_BT_MUSIC = "com.autochips.bluetooth";
    public static final String CLS_BT_MAIN = "com.autochips.bluetooth.MainBluetoothActivity";
    public static final String PKG_USB_MUSIC = "com.acloud.stub.localmusic";
    public static final String CLS_USB_MAIN = "com.acloud.stub.localmusic.QtActivity";
    public static final String PKG_DASHCAM = "com.fvsm.camera";
    public static final String CLS_DASHCAM_MAIN = "com.fvsm.camera.MainActivity";
    public static final String PKG_EQUALIZER = "com.xyauto.Settings";
    public static final String CLS_EQUALIZER_APS = "com.xyauto.Settings.ApsStartServiceViewActivity";
    public static final String ACTION_XY_APS = "android.intent.action.xyaps";
    public static final String PKG_RADIO = "com.acloud.stub.localradio";

    private CarIntentRouter() {
    }

    /** Effective app for a role: user override, then accepted profile, then built-in default. */
    public static RoleSelection resolveRole(Context context, Role role) {
        return ConfigurationStore.get(context).load(context).roles.get(role);
    }

    public static String getTargetPackage(Context context, Role role) {
        return resolveRole(context, role).packageName;
    }

    /** Stores an explicit user choice; a blank package clears it. Returns false if not saved. */
    public static boolean setTargetPackage(Context context, Role role, String packageName) {
        ConfigurationStore store = ConfigurationStore.get(context);
        store.load(context);
        try {
            if (packageName == null || packageName.trim().isEmpty()) {
                store.setRole(role, null);
            } else {
                String strategy = role == Role.NAVIGATION ? "google-navigation" : "launch-package";
                store.setRole(role, new RoleSelection(packageName, null, strategy, null));
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static void launchGoogleMapsLive(Context context) {
        startOverlayService(context);
        String mapsPkg = getTargetPackage(context, Role.NAVIGATION);
        if (launchPackage(context, mapsPkg)) {
            return;
        }
        try {
            Intent geo = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="));
            geo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(geo);
        } catch (Exception e) {
            Toast.makeText(context, "Chưa cài ứng dụng Bản đồ (" + mapsPkg + ")", Toast.LENGTH_SHORT).show();
        }
    }

    public static void launchCoordinatesInMapsLive(Context context, double lat, double lon, String label) {
        startOverlayService(context);
        String mapsPkg = getTargetPackage(context, Role.NAVIGATION);

        try {
            String navUri = String.format(Locale.US, "google.navigation:q=%.6f,%.6f", lat, lon);
            Intent navIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(navUri));
            navIntent.setPackage(mapsPkg);
            navIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(navIntent);
            return;
        } catch (Exception ignored) {
        }

        try {
            String q = (label != null && !label.isEmpty())
                    ? String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", lat, lon, lat, lon, Uri.encode(label))
                    : String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f", lat, lon, lat, lon);
            Intent geoIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(q));
            geoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(geoIntent);
        } catch (Exception e) {
            Toast.makeText(context, String.format(Locale.US, "Đi tới: %.5f, %.5f", lat, lon), Toast.LENGTH_SHORT).show();
        }
    }

    public static void startOverlayService(Context context) {
        try {
            Intent svc = new Intent(context, OverlayHudService.class);
            svc.setAction(OverlayHudService.ACTION_SHOW_EXPANDED);
            context.startService(svc);
        } catch (Exception ignored) {
        }
    }

    public static void stopOverlayService(Context context) {
        try {
            Intent svc = new Intent(context, OverlayHudService.class);
            svc.setAction(OverlayHudService.ACTION_HIDE);
            context.stopService(svc);
        } catch (Exception ignored) {
        }
    }

    public static void launchDashcam(Context context) {
        stopOverlayService(context);
        RoleSelection selection = resolveRole(context, Role.DASHCAM);
        String pkg = selection.packageName;
        if (launchRoleComponent(context, Role.DASHCAM, selection)) {
            return;
        }
        if (launchPackage(context, pkg)) {
            return;
        }
        Toast.makeText(context, "Không tìm thấy Camera hành trình (" + pkg + ")", Toast.LENGTH_SHORT).show();
    }

    public static void launchEqualizer(Context context) {
        stopOverlayService(context);
        RoleSelection selection = resolveRole(context, Role.EQUALIZER);
        String pkg = selection.packageName;
        if (PKG_EQUALIZER.equals(pkg)) {
            try {
                Intent aps = new Intent(ACTION_XY_APS);
                aps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(aps);
                return;
            } catch (Exception ignored) {
            }
            if (launchRoleComponent(context, Role.EQUALIZER, selection)) {
                return;
            }
        }
        if (launchPackage(context, pkg)) {
            return;
        }
        try {
            Intent eq = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
            eq.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(eq);
            return;
        } catch (Exception ignored) {
        }
        launchSystemSettings(context, Settings.ACTION_SOUND_SETTINGS);
    }

    public static void launchMusicSourceApp(Context context, int source) {
        if (source == HeadUnitMediaBridge.SOURCE_USB) {
            RoleSelection selection = resolveRole(context, Role.LOCAL_MUSIC);
            String pkg = selection.packageName;
            if (launchRoleComponent(context, Role.LOCAL_MUSIC, selection)) {
                return;
            }
            if (launchPackage(context, pkg)) return;
            Toast.makeText(context, "Không tìm thấy ứng dụng Nhạc USB (" + pkg + ")", Toast.LENGTH_SHORT).show();
        } else {
            RoleSelection selection = resolveRole(context, Role.BLUETOOTH_MUSIC);
            String pkg = selection.packageName;
            if (launchRoleComponent(context, Role.BLUETOOTH_MUSIC, selection)) {
                return;
            }
            if (launchPackage(context, pkg)) return;
            launchSystemSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS);
        }
    }

    public static void launchSystemSettings(Context context, String action) {
        try {
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Exception ignored) {
            }
        }
    }

    public static int cycleBrightness(Context context) {
        try {
            int current = Settings.System.getInt(
                    context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 180);
            int next;
            String label;
            if (current < 90) {
                next = 160;
                label = "Độ sáng: 60%";
            } else if (current < 210) {
                next = 255;
                label = "Độ sáng: 100%";
            } else {
                next = 50;
                label = "Độ sáng: 20% (Ban đêm)";
            }
            if (Settings.System.canWrite(context)) {
                Settings.System.putInt(
                        context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, next);
                Toast.makeText(context, label, Toast.LENGTH_SHORT).show();
                return next;
            }
        } catch (Exception ignored) {
        }
        launchSystemSettings(context, Settings.ACTION_DISPLAY_SETTINGS);
        return -1;
    }

    public static boolean launchPackage(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Launches the selection's component, or the built-in component when the selection names
     * the built-in package without one. The component must belong to the selected package.
     */
    private static boolean launchRoleComponent(Context context, Role role, RoleSelection selection) {
        String flattened = selection.componentName;
        RoleSelection builtIn = LauncherConfiguration.builtInRole(role);
        if (flattened == null && builtIn.packageName.equals(selection.packageName)) {
            flattened = builtIn.componentName;
        }
        if (flattened == null) {
            return false;
        }
        ComponentName component = ComponentName.unflattenFromString(flattened);
        if (component == null || !selection.packageName.equals(component.getPackageName())) {
            return false;
        }
        return launchExplicit(context, component.getPackageName(), component.getClassName());
    }

    public static boolean launchExplicit(Context context, String packageName, String className) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(new ComponentName(packageName, className));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
