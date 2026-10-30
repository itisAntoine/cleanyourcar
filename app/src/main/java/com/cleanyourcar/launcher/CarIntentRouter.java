package com.cleanyourcar.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.util.Locale;

/**
 * Routes user actions to the head unit's target apps (Google Maps, Bluetooth Music, USB Music,
 * Front Dashcam, Equalizer/DSP, Brightness, and Night Clock), with user-customizable package
 * overrides in SharedPreferences and graceful fallbacks on standard Android devices/emulators.
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

    public static String getTargetPackage(Context context, String prefKey, String defaultPkg) {
        String custom = ThemeConfig.prefs(context).getString(prefKey, null);
        if (custom != null && !custom.trim().isEmpty()) {
            return custom.trim();
        }
        return defaultPkg;
    }

    public static void setTargetPackage(Context context, String prefKey, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            ThemeConfig.prefs(context).edit().remove(prefKey).apply();
        } else {
            ThemeConfig.prefs(context).edit().putString(prefKey, packageName.trim()).apply();
        }
    }

    public static void launchGoogleMapsLive(Context context) {
        startOverlayService(context);
        String mapsPkg = getTargetPackage(context, ThemeConfig.KEY_PKG_MAPS, PKG_GOOGLE_MAPS);
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
        String mapsPkg = getTargetPackage(context, ThemeConfig.KEY_PKG_MAPS, PKG_GOOGLE_MAPS);

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
        String pkg = getTargetPackage(context, ThemeConfig.KEY_PKG_DASHCAM, PKG_DASHCAM);
        if (PKG_DASHCAM.equals(pkg) && launchExplicit(context, PKG_DASHCAM, CLS_DASHCAM_MAIN)) {
            return;
        }
        if (launchPackage(context, pkg)) {
            return;
        }
        Toast.makeText(context, "Không tìm thấy Camera hành trình (" + pkg + ")", Toast.LENGTH_SHORT).show();
    }

    public static void launchEqualizer(Context context) {
        stopOverlayService(context);
        String pkg = getTargetPackage(context, ThemeConfig.KEY_PKG_EQUALIZER, PKG_EQUALIZER);
        if (PKG_EQUALIZER.equals(pkg)) {
            try {
                Intent aps = new Intent(ACTION_XY_APS);
                aps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(aps);
                return;
            } catch (Exception ignored) {
            }
            if (launchExplicit(context, PKG_EQUALIZER, CLS_EQUALIZER_APS)) {
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
            String pkg = getTargetPackage(context, ThemeConfig.KEY_PKG_USB_MUSIC, PKG_USB_MUSIC);
            if (PKG_USB_MUSIC.equals(pkg) && launchExplicit(context, PKG_USB_MUSIC, CLS_USB_MAIN)) {
                return;
            }
            if (launchPackage(context, pkg)) return;
            Toast.makeText(context, "Không tìm thấy ứng dụng Nhạc USB (" + pkg + ")", Toast.LENGTH_SHORT).show();
        } else {
            String pkg = getTargetPackage(context, ThemeConfig.KEY_PKG_BT_MUSIC, PKG_BT_MUSIC);
            if (PKG_BT_MUSIC.equals(pkg) && launchExplicit(context, PKG_BT_MUSIC, CLS_BT_MAIN)) {
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
