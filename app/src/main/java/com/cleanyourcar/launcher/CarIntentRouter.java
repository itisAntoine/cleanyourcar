package com.cleanyourcar.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import com.cleanyourcar.launcher.compat.DeviceProfile;
import com.cleanyourcar.launcher.compat.EnvironmentFacts;
import com.cleanyourcar.launcher.config.ConfigurationStore;
import com.cleanyourcar.launcher.config.LauncherConfiguration;
import com.cleanyourcar.launcher.config.RoleSelection;
import com.cleanyourcar.launcher.config.RoleSelection.Role;

import com.cleanyourcar.launcher.integration.IntegrationController;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Routes user actions to the head unit's target apps (Google Maps, Bluetooth Music, USB Music,
 * Front Dashcam, Equalizer/DSP, Brightness, and Night Clock). Role apps are resolved through
 * {@link ConfigurationStore}; only explicitly chosen or profile-provided apps are launched on
 * generic installs, with standard Android settings panels as the only generic fallbacks.
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

    /** Emits launch requests. URIs are plain strings so routing logic stays JVM-testable. */
    public interface Transport {
        LaunchResult startView(String uri, String packageName);

        LaunchResult startAction(String action);

        LaunchResult launchComponent(String packageName, String className);

        LaunchResult launchPackage(String packageName);
    }

    /** Shows the navigation HUD overlay; called only after a launch actually succeeded. */
    public interface Overlay {
        void show();
    }

    /** A validated role selection, or the reason no app may be launched for the role. */
    public static final class ResolvedRole {
        public enum Unavailable {
            /** No user choice, no accepted profile default, and not a legacy XYAuto install. */
            NOT_CONFIGURED,
            /** The strategy cannot serve this role (e.g. a navigation scheme for radio). */
            STRATEGY_NOT_ALLOWED,
            /** The component does not belong to the selected package. */
            COMPONENT_MISMATCH,
            /** The strategy needs the XYAuto adapter, which this install has not accepted. */
            ADAPTER_REQUIRED
        }

        public final Role role;
        /** Validated selection; null when unavailable. */
        public final RoleSelection selection;
        /** Null when available. */
        public final Unavailable reason;
        /** XYAuto-specific actions are allowed (legacy XYAuto install or accepted adapter). */
        public final boolean xyAutoActionsAllowed;

        private ResolvedRole(Role role, RoleSelection selection, Unavailable reason,
                boolean xyAutoActionsAllowed) {
            this.role = role;
            this.selection = selection;
            this.reason = reason;
            this.xyAutoActionsAllowed = xyAutoActionsAllowed;
        }

        public boolean isAvailable() {
            return selection != null;
        }
    }

    public static final class LaunchResult {
        public enum Status { LAUNCHED, BLOCKED, FAILED }

        public enum Reason {
            NONE,
            ROLE_UNAVAILABLE,
            INVALID_DESTINATION,
            /** The app was opened but its strategy cannot receive a destination. */
            DESTINATION_NOT_SENT,
            NOT_FOUND,
            NOT_PERMITTED
        }

        public final Status status;
        public final Reason reason;

        private LaunchResult(Status status, Reason reason) {
            this.status = status;
            this.reason = reason;
        }

        public static LaunchResult launched() {
            return new LaunchResult(Status.LAUNCHED, Reason.NONE);
        }

        public static LaunchResult blocked(Reason reason) {
            return new LaunchResult(Status.BLOCKED, reason);
        }

        public static LaunchResult failed(Reason reason) {
            return new LaunchResult(Status.FAILED, reason);
        }

        public boolean isLaunched() {
            return status == Status.LAUNCHED;
        }
    }

    public enum SaveResult { SAVED, READ_ONLY, FAILED }

    public static ResolvedRole resolveRole(Context context, Role role) {
        return resolveRole(ConfigurationStore.get(context).load(context), role);
    }

    /**
     * Effective app for a role: user choice, then accepted profile default, then the built-in
     * XYAuto apps only after accepting the XYAuto profile. Legacy preference presence is not
     * adapter evidence.
     */
    public static ResolvedRole resolveRole(LauncherConfiguration configuration, Role role) {
        boolean xyAuto = DeviceProfile.ADAPTER_XYAUTO.equals(configuration.acceptedAdapterId);
        RoleSelection selection = configuration.roleOverrides.get(role);
        if (selection == null) {
            selection = configuration.profileRoleDefaults.get(role);
        }
        if (selection == null) {
            return new ResolvedRole(role, null, ResolvedRole.Unavailable.NOT_CONFIGURED, xyAuto);
        }
        String strategy = selection.strategyId;
        if (DeviceProfile.NAVIGATION_STRATEGY_IDS.contains(strategy) && role != Role.NAVIGATION) {
            return new ResolvedRole(role, null, ResolvedRole.Unavailable.STRATEGY_NOT_ALLOWED,
                    xyAuto);
        }
        if (DeviceProfile.XYAUTO_STRATEGY_IDS.contains(strategy)) {
            if (role != Role.EQUALIZER) {
                return new ResolvedRole(role, null, ResolvedRole.Unavailable.STRATEGY_NOT_ALLOWED,
                        xyAuto);
            }
            if (!xyAuto) {
                return new ResolvedRole(role, null, ResolvedRole.Unavailable.ADAPTER_REQUIRED,
                        false);
            }
        }
        if (selection.componentName != null) {
            String[] component = unflatten(selection.componentName);
            if (component == null || !selection.packageName.equals(component[0])) {
                return new ResolvedRole(role, null, ResolvedRole.Unavailable.COMPONENT_MISMATCH,
                        xyAuto);
            }
        }
        return new ResolvedRole(role, selection, null, xyAuto);
    }

    /** Package of the role's app, or null when the role is unavailable. */
    public static String getTargetPackage(Context context, Role role) {
        ResolvedRole resolved = resolveRole(context, role);
        return resolved.isAvailable() ? resolved.selection.packageName : null;
    }

    /** Stores an explicit user choice with its strategy; a null selection clears it. */
    public static SaveResult setRole(Context context, Role role, RoleSelection selection) {
        ConfigurationStore store = ConfigurationStore.get(context);
        store.load(context);
        if (store.isReadOnly()) {
            return SaveResult.READ_ONLY;
        }
        try {
            store.setRole(role, selection);
            return SaveResult.SAVED;
        } catch (ConfigurationStore.NewerFormatException e) {
            return SaveResult.READ_ONLY;
        } catch (IOException | RuntimeException e) {
            return SaveResult.FAILED;
        }
    }

    /**
     * Opens the role's app using only its validated strategy. On AAOS a refused route is
     * reported as is (see {@link #launchRole(ResolvedRole, Transport, boolean)}); elsewhere
     * the usual component and package fallbacks apply.
     */
    public static LaunchResult launchRole(ResolvedRole resolved, Transport transport) {
        return launchRole(resolved, transport, stopsOnRestriction(transport));
    }

    /**
     * With {@code stopOnRestriction} (AAOS only), a route refused by the system (NOT_PERMITTED)
     * is reported as is and no other route is tried for the same request. Without it, a
     * NOT_PERMITTED result usually means a non-exported or protected component on aftermarket
     * firmware, so the next route is still tried.
     */
    static LaunchResult launchRole(ResolvedRole resolved, Transport transport,
            boolean stopOnRestriction) {
        if (resolved == null || !resolved.isAvailable()) {
            return LaunchResult.blocked(LaunchResult.Reason.ROLE_UNAVAILABLE);
        }
        RoleSelection selection = resolved.selection;
        LaunchResult result = LaunchResult.failed(LaunchResult.Reason.NOT_FOUND);
        boolean xyAutoEqualizer = "xyauto-equalizer".equals(selection.strategyId)
                || (resolved.role == Role.EQUALIZER && resolved.xyAutoActionsAllowed
                        && PKG_EQUALIZER.equals(selection.packageName));
        if (xyAutoEqualizer) {
            result = transport.startAction(ACTION_XY_APS);
            if (result.isLaunched() || stops(stopOnRestriction, result)) return result;
        }
        String[] component = componentFor(resolved);
        if (component != null) {
            result = transport.launchComponent(component[0], component[1]);
            if (result.isLaunched() || stops(stopOnRestriction, result)) return result;
        }
        if (stopOnRestriction) {
            return transport.launchPackage(selection.packageName);
        }
        LaunchResult byPackage = transport.launchPackage(selection.packageName);
        return byPackage.isLaunched() || result.reason != LaunchResult.Reason.NOT_PERMITTED
                ? byPackage : result;
    }

    /** The system refused the launch; on AAOS it must be explained, never retried. */
    static boolean isRestricted(LaunchResult result) {
        return result != null && result.reason == LaunchResult.Reason.NOT_PERMITTED;
    }

    private static boolean stops(boolean stopOnRestriction, LaunchResult result) {
        return stopOnRestriction && isRestricted(result);
    }

    /**
     * Whether refused launches must be explained instead of retried: true only on AAOS. A
     * SecurityException elsewhere usually means a non-exported or protected component. Note
     * that real AAOS UX restrictions usually let the launch succeed and then swap in the
     * system's ActivityBlockingActivity rather than throwing; this rule covers the cases
     * where the system does refuse the start.
     */
    static boolean stopsOnRestriction(Context context) {
        try {
            return EnvironmentFacts.PLATFORM_AAOS.equals(
                    EnvironmentFacts.collect(context).platform);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean stopsOnRestriction(Transport transport) {
        return transport instanceof AndroidTransport
                && stopsOnRestriction(((AndroidTransport) transport).context);
    }

    /**
     * Sends the destination to the navigation app using exactly its chosen strategy. A geo
     * provider never receives a google.navigation URI; launch-only apps are just opened.
     */
    public static LaunchResult launchNavigation(ResolvedRole resolved, PlusCodeCodec.LatLon target,
            Transport transport) {
        if (resolved == null || !resolved.isAvailable()) {
            return LaunchResult.blocked(LaunchResult.Reason.ROLE_UNAVAILABLE);
        }
        if (target == null || !NavPayloadParser.isValidLatLon(target.lat, target.lon)) {
            return LaunchResult.blocked(LaunchResult.Reason.INVALID_DESTINATION);
        }
        RoleSelection selection = resolved.selection;
        if ("google-navigation".equals(selection.strategyId)) {
            return transport.startView(String.format(Locale.US, "google.navigation:q=%.6f,%.6f",
                    target.lat, target.lon), selection.packageName);
        }
        if ("geo".equals(selection.strategyId)) {
            return transport.startView(geoUri(target), selection.packageName);
        }
        LaunchResult opened = launchRole(resolved, transport);
        return opened.isLaunched()
                ? new LaunchResult(LaunchResult.Status.LAUNCHED,
                        LaunchResult.Reason.DESTINATION_NOT_SENT)
                : opened;
    }

    /** Opens the navigation app without a destination; the overlay follows only a launch. */
    public static LaunchResult openNavigationApp(ResolvedRole navigation, Transport transport,
            Overlay overlay) {
        return showOverlayIfLaunched(launchRole(navigation, transport), overlay);
    }

    /** {@link #launchNavigation(ResolvedRole, PlusCodeCodec.LatLon, Transport)} plus overlay. */
    public static LaunchResult launchNavigation(ResolvedRole navigation,
            PlusCodeCodec.LatLon target, Transport transport, Overlay overlay) {
        return showOverlayIfLaunched(launchNavigation(navigation, target, transport), overlay);
    }

    /**
     * Opens an app from the grid. The overlay appears only when the app is the available
     * navigation role's package and it actually launched.
     */
    public static LaunchResult launchGridApp(String packageName, ResolvedRole navigation,
            Transport transport, Overlay overlay) {
        LaunchResult result = transport.launchPackage(packageName);
        boolean isNavigationApp = navigation != null && navigation.isAvailable()
                && packageName.equals(navigation.selection.packageName);
        return isNavigationApp ? showOverlayIfLaunched(result, overlay) : result;
    }

    private static LaunchResult showOverlayIfLaunched(LaunchResult result, Overlay overlay) {
        if (result.isLaunched() && overlay != null) {
            overlay.show();
        }
        return result;
    }

    static String geoUri(PlusCodeCodec.LatLon target) {
        String coords = String.format(Locale.US, "%.6f,%.6f", target.lat, target.lon);
        String uri = "geo:" + coords + "?q=" + coords;
        if (target.label != null && !target.label.trim().isEmpty()) {
            try {
                uri += "(" + URLEncoder.encode(target.label.trim(), "UTF-8").replace("+", "%20")
                        + ")";
            } catch (UnsupportedEncodingException ignored) {
            }
        }
        return uri;
    }

    /** Opens the navigation app without a destination. */
    public static void launchGoogleMapsLive(Context context) {
        LaunchResult result = openNavigationApp(resolveRole(context, Role.NAVIGATION),
                new AndroidTransport(context), overlay(context));
        toastFailure(context, result, stopsOnRestriction(context));
    }

    /** Sends a destination to the chosen navigation app and explains any blocked result. */
    public static LaunchResult launchNavigation(Context context, PlusCodeCodec.LatLon target) {
        LaunchResult result = launchNavigation(resolveRole(context, Role.NAVIGATION), target,
                new AndroidTransport(context), overlay(context));
        if (result.reason == LaunchResult.Reason.DESTINATION_NOT_SENT) {
            toast(context, I18n.tr(context, I18n.KEY_DEST_NOT_SENT));
        } else {
            toastFailure(context, result, stopsOnRestriction(context));
        }
        return result;
    }

    /** Opens a grid app; the HUD overlay follows only a successful navigation-app launch. */
    public static boolean launchGridApp(Context context, String packageName) {
        return launchGridApp(packageName, resolveRole(context, Role.NAVIGATION),
                new AndroidTransport(context), overlay(context)).isLaunched();
    }

    /** Evaluates a HUD request; the decision source is {@link HudSessionPolicy#requestShow}. */
    interface HudGate {
        HudSessionPolicy.Decision requestShow();

        void explainDenial(HudSessionPolicy.Reason reason);
    }

    /**
     * Starts the HUD only when the session policy grants it; otherwise navigation stays full
     * screen and the denial is explained.
     */
    static Overlay gatedOverlay(final HudGate gate, final Overlay start) {
        return gatedOverlay(gate, start, null);
    }

    /**
     * Like {@link #gatedOverlay(HudGate, Overlay)}; a denied request also removes an overlay
     * that is already showing from an earlier session by running {@code stopShowing}.
     */
    static Overlay gatedOverlay(final HudGate gate, final Overlay start,
            final Overlay stopShowing) {
        return new Overlay() {
            @Override
            public void show() {
                HudSessionPolicy.Decision decision = gate.requestShow();
                if (decision.isShow()) {
                    start.show();
                } else if (decision.action == HudSessionPolicy.Action.DENY) {
                    if (stopShowing != null) {
                        stopShowing.show();
                    }
                    gate.explainDenial(decision.reason);
                }
            }
        };
    }

    private static Overlay overlay(final Context context) {
        return gatedOverlay(new HudGate() {
            @Override
            public HudSessionPolicy.Decision requestShow() {
                return OverlayHudService.newPolicy(context).requestShow(
                        OverlayHudService.currentHudStatus(context),
                        IntegrationController.processReverseState(),
                        OverlayHudService.isMapLayoutVerified(context));
            }

            @Override
            public void explainDenial(HudSessionPolicy.Reason reason) {
                OverlayHudService.explainDenial(context, reason);
            }
        }, new Overlay() {
            @Override
            public void show() {
                startOverlayService(context);
            }
        }, new Overlay() {
            @Override
            public void show() {
                stopOverlayService(context);
            }
        });
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
        boolean stop = stopsOnRestriction(context);
        LaunchResult result = launchRole(resolveRole(context, Role.DASHCAM),
                new AndroidTransport(context), stop);
        toastFailure(context, result, stop);
    }

    public static void launchEqualizer(Context context) {
        stopOverlayService(context);
        launchEqualizer(context, stopsOnRestriction(context));
    }

    /** {@link #launchEqualizer(Context)} with the AAOS restriction rule given explicitly. */
    static void launchEqualizer(Context context, boolean stopOnRestriction) {
        LaunchResult result = launchRole(resolveRole(context, Role.EQUALIZER),
                new AndroidTransport(context), stopOnRestriction);
        if (result.isLaunched()) {
            return;
        }
        if (stops(stopOnRestriction, result)) {
            toastFailure(context, result, true);
            return;
        }
        // Standard Android audio panels are generic, not XYAuto defaults.
        try {
            Intent eq = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
            eq.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(eq);
            return;
        } catch (SecurityException e) {
            if (stopOnRestriction) {
                toast(context, I18n.tr(context, I18n.KEY_SYSTEM_RESTRICTED));
                return;
            }
        } catch (Exception ignored) {
        }
        launchSystemSettings(context, Settings.ACTION_SOUND_SETTINGS, stopOnRestriction);
    }

    public static void launchMusicSourceApp(Context context, int source) {
        launchMusicSourceApp(context, source, stopsOnRestriction(context));
    }

    /** {@link #launchMusicSourceApp(Context, int)} with the AAOS restriction rule given. */
    static void launchMusicSourceApp(Context context, int source, boolean stopOnRestriction) {
        if (source == HeadUnitMediaBridge.SOURCE_USB) {
            LaunchResult result = launchRole(resolveRole(context, Role.LOCAL_MUSIC),
                    new AndroidTransport(context), stopOnRestriction);
            toastFailure(context, result, stopOnRestriction);
        } else {
            LaunchResult result = launchRole(resolveRole(context, Role.BLUETOOTH_MUSIC),
                    new AndroidTransport(context), stopOnRestriction);
            if (stops(stopOnRestriction, result)) {
                toastFailure(context, result, true);
            } else if (!result.isLaunched()) {
                launchSystemSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS,
                        stopOnRestriction);
            }
        }
    }

    /**
     * Opens a settings panel. A missing panel falls back to the main settings screen; on AAOS a
     * panel refused by the system is explained and not replaced by another route.
     */
    public static void launchSystemSettings(Context context, String action) {
        launchSystemSettings(context, action, stopsOnRestriction(context));
    }

    /** {@link #launchSystemSettings(Context, String)} with the AAOS restriction rule given. */
    static void launchSystemSettings(Context context, String action, boolean stopOnRestriction) {
        try {
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (SecurityException e) {
            if (stopOnRestriction) {
                toast(context, I18n.tr(context, I18n.KEY_SYSTEM_RESTRICTED));
            } else {
                openMainSettings(context);
            }
        } catch (Exception e) {
            openMainSettings(context);
        }
    }

    private static void openMainSettings(Context context) {
        try {
            Intent fallback = new Intent(Settings.ACTION_SETTINGS);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallback);
        } catch (Exception ignored) {
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
        return new AndroidTransport(context).launchPackage(packageName).isLaunched();
    }

    /**
     * Component to launch: the selection's own, or for XYAuto installs the built-in component
     * when the selection names the built-in package without one.
     */
    private static String[] componentFor(ResolvedRole resolved) {
        RoleSelection selection = resolved.selection;
        String flattened = selection.componentName;
        if (flattened == null && resolved.xyAutoActionsAllowed) {
            RoleSelection builtIn = LauncherConfiguration.builtInRole(resolved.role);
            if (builtIn != null && builtIn.packageName.equals(selection.packageName)) {
                flattened = builtIn.componentName;
            }
        }
        if (flattened == null) {
            return null;
        }
        String[] component = unflatten(flattened);
        return component != null && selection.packageName.equals(component[0]) ? component : null;
    }

    /** Pure equivalent of ComponentName.unflattenFromString: {package, class} or null. */
    static String[] unflatten(String flattened) {
        int sep = flattened.indexOf('/');
        if (sep <= 0 || sep == flattened.length() - 1) {
            return null;
        }
        String pkg = flattened.substring(0, sep);
        String cls = flattened.substring(sep + 1);
        if (cls.charAt(0) == '.') {
            cls = pkg + cls;
        }
        return new String[] {pkg, cls};
    }

    /** Explains a failed launch; NOT_PERMITTED reads as a system block only on AAOS. */
    private static void toastFailure(Context context, LaunchResult result,
            boolean stopOnRestriction) {
        if (result.isLaunched()) {
            return;
        }
        int key;
        switch (result.reason) {
            case ROLE_UNAVAILABLE:
                key = I18n.KEY_ROLE_UNAVAILABLE;
                break;
            case INVALID_DESTINATION:
                key = I18n.KEY_INVALID_DESTINATION;
                break;
            case NOT_PERMITTED:
                key = stopOnRestriction ? I18n.KEY_SYSTEM_RESTRICTED : I18n.KEY_APP_NOT_FOUND;
                break;
            default:
                key = I18n.KEY_APP_NOT_FOUND;
                break;
        }
        toast(context, I18n.tr(context, key));
    }

    private static void toast(Context context, String text) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ignored) {
        }
    }

    public static boolean launchExplicit(Context context, String packageName, String className) {
        return new AndroidTransport(context).launchComponent(packageName, className).isLaunched();
    }

    /** Starts real activities; missing apps and security refusals become failed results. */
    static final class AndroidTransport implements Transport {
        private final Context context;

        AndroidTransport(Context context) {
            this.context = context;
        }

        @Override
        public LaunchResult startView(String uri, String packageName) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            intent.setPackage(packageName);
            return start(intent);
        }

        @Override
        public LaunchResult startAction(String action) {
            return start(new Intent(action));
        }

        @Override
        public LaunchResult launchComponent(String packageName, String className) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(new ComponentName(packageName, className));
            return start(intent);
        }

        @Override
        public LaunchResult launchPackage(String packageName) {
            if (packageName == null || packageName.isEmpty()) {
                return LaunchResult.failed(LaunchResult.Reason.NOT_FOUND);
            }
            Intent launch;
            try {
                launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
            } catch (RuntimeException e) {
                launch = null;
            }
            if (launch == null) {
                return LaunchResult.failed(LaunchResult.Reason.NOT_FOUND);
            }
            return start(launch);
        }

        private LaunchResult start(Intent intent) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
                return LaunchResult.launched();
            } catch (SecurityException e) {
                return LaunchResult.failed(LaunchResult.Reason.NOT_PERMITTED);
            } catch (RuntimeException e) {
                return LaunchResult.failed(LaunchResult.Reason.NOT_FOUND);
            }
        }
    }
}
