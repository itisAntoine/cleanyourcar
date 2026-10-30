package com.cleanyourcar.navbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Foreground host for the optional BLE and LAN receivers. Starts only when the user explicitly
 * starts it (or a consented, legal automatic trigger fires), runs visibly with a notification, and
 * publishes the real per-transport state, including failures.
 */
public class NavBridgeService extends Service {
    public static final String ACTION_START = "com.cleanyourcar.navbridge.action.START";
    public static final String ACTION_STOP = "com.cleanyourcar.navbridge.action.STOP";
    public static final String EXTRA_USER_INITIATED = "user_initiated";

    private static final String CHANNEL_STATUS = "navbridge_status";
    private static final String CHANNEL_NOTICE = "navbridge_notice";
    private static final int NOTIFICATION_ID = 4892;
    private static final int NOTICE_ID = 4893;
    private static final String KEY_PAUSE_NOTICE_SHOWN = "pause_notice_shown";
    private static final String KEY_LAST_ERROR = "last_error";

    /** Immutable status snapshot shown by the settings screen. */
    public static final class Status {
        public final boolean running;
        public final BridgeSession.TransportState ble;
        public final BridgeSession.TransportState lan;
        public final String bleDetail;
        public final String lanDetail;
        public final Set<BridgeLifecyclePolicy.Reason> reasons;

        Status(boolean running, BridgeSession.TransportState ble, BridgeSession.TransportState lan,
               String bleDetail, String lanDetail, Set<BridgeLifecyclePolicy.Reason> reasons) {
            this.running = running;
            this.ble = ble;
            this.lan = lan;
            this.bleDetail = bleDetail;
            this.lanDetail = lanDetail;
            this.reasons = reasons;
        }

        static Status stopped(Set<BridgeLifecyclePolicy.Reason> reasons) {
            return new Status(false, BridgeSession.TransportState.OFF, BridgeSession.TransportState.OFF,
                    "", "", reasons);
        }
    }

    public interface StatusObserver {
        void onStatus(Status status);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Status latest =
            Status.stopped(Collections.<BridgeLifecyclePolicy.Reason>emptySet());
    private static StatusObserver observer;

    private BridgeSession session;

    public static Status latestStatus() {
        return latest;
    }

    public static void setObserver(StatusObserver o) {
        observer = o;
        if (o != null) o.onStatus(latest);
    }

    private static void publish(final Status status) {
        latest = status;
        MAIN.post(() -> {
            StatusObserver o = observer;
            if (o != null) o.onStatus(latest);
        });
    }

    public static BridgeLifecyclePolicy.PermissionState permissions(Context context) {
        int sdk = Build.VERSION.SDK_INT;
        boolean legacy = granted(context, Manifest.permission.BLUETOOTH)
                && granted(context, Manifest.permission.BLUETOOTH_ADMIN);
        boolean connect = sdk >= Build.VERSION_CODES.S && granted(context, Manifest.permission.BLUETOOTH_CONNECT);
        boolean advertise = sdk >= Build.VERSION_CODES.S
                && granted(context, Manifest.permission.BLUETOOTH_ADVERTISE);
        boolean notifications = sdk < Build.VERSION_CODES.TIRAMISU
                || granted(context, Manifest.permission.POST_NOTIFICATIONS);
        return BridgeLifecyclePolicy.PermissionState.builder()
                .legacyBluetooth(legacy)
                .bluetoothConnect(connect)
                .bluetoothAdvertise(advertise)
                .notifications(notifications)
                .build();
    }

    public static BridgeLifecyclePolicy.FeatureState features(Context context, BridgeConfiguration config) {
        boolean present = false;
        boolean enabled = false;
        boolean advertising = false;
        try {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            present = adapter != null
                    && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
            enabled = present && adapter.isEnabled();
            advertising = enabled && BleGattNavServer.isAdvertisingSupported(adapter);
        } catch (RuntimeException ignored) {
            // A SecurityException or vendor failure means BLE is not usable; report it as such.
        }
        return BridgeLifecyclePolicy.FeatureState.builder(config)
                .bluetoothAdapterPresent(present)
                .bluetoothEnabled(enabled)
                .bleAdvertisingSupported(advertising)
                .build();
    }

    public static BridgeLifecyclePolicy.StartDecision evaluate(Context context, boolean userInitiated) {
        BridgeConfiguration config = BridgeConfiguration.load(context);
        return BridgeLifecyclePolicy.canStart(Build.VERSION.SDK_INT, permissions(context),
                features(context, config), userInitiated);
    }

    /**
     * Starts the bridge only when the policy allows it. Returns the decision so callers can show
     * why nothing started; reception is confirmed later through {@link Status}.
     */
    public static BridgeLifecyclePolicy.StartDecision requestStart(Context context, boolean userInitiated) {
        BridgeLifecyclePolicy.StartDecision decision = evaluate(context, userInitiated);
        if (!decision.allowed()) {
            publish(Status.stopped(decision.reasons()));
            return decision;
        }
        Intent intent = new Intent(context, NavBridgeService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_USER_INITIATED, userInitiated);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
            recordError(context, null);
        } catch (RuntimeException e) {
            // E.g. ForegroundServiceStartNotAllowedException: report instead of retrying another way.
            recordError(context, e.getClass().getSimpleName());
            publish(Status.stopped(decision.reasons()));
        }
        return decision;
    }

    public static void requestStop(Context context) {
        context.stopService(new Intent(context, NavBridgeService.class));
    }

    public static String lastError(Context context) {
        return prefs(context).getString(KEY_LAST_ERROR, null);
    }

    static void recordError(Context context, String error) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (error == null) editor.remove(KEY_LAST_ERROR);
        else editor.putString(KEY_LAST_ERROR, error);
        editor.apply();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopBridge(Collections.<BridgeLifecyclePolicy.Reason>emptySet());
            return START_NOT_STICKY;
        }
        boolean userInitiated = intent != null && intent.getBooleanExtra(EXTRA_USER_INITIATED, false);
        BridgeConfiguration config = BridgeConfiguration.load(this);
        BridgeLifecyclePolicy.PermissionState permissions = permissions(this);
        BridgeLifecyclePolicy.FeatureState features = features(this, config);
        BridgeLifecyclePolicy.StartDecision decision = BridgeLifecyclePolicy.canStart(
                Build.VERSION.SDK_INT, permissions, features, userInitiated);

        // startForegroundService() obliges startForeground() even when the re-check now denies.
        if (!enterForeground(decision)) {
            recordError(this, "foreground-service-denied");
            stopBridge(decision.reasons());
            return START_NOT_STICKY;
        }
        if (!decision.allowed()) {
            stopBridge(decision.reasons());
            return START_NOT_STICKY;
        }

        if (session != null) session.stop();
        Context app = getApplicationContext();
        BridgeRequestHandler handler = new BridgeRequestHandler(config.authorization(),
                destination -> MAIN.post(() -> NavBridgeProtocol.dispatchToLauncher(app, destination)));
        session = new BridgeSession(new BleGattNavServer(this, handler),
                new HotspotBurstServer(handler, NavBridgeProtocol.PORT, null));
        final BridgeSession current = session;
        current.setListener(changed -> MAIN.post(() -> onSessionChanged(current)));
        current.start(Build.VERSION.SDK_INT, permissions, features, userInitiated);
        return START_NOT_STICKY;
    }

    private void onSessionChanged(BridgeSession changed) {
        if (changed != session) return;
        BridgeLifecyclePolicy.StartDecision decision = changed.lastDecision();
        Set<BridgeLifecyclePolicy.Reason> reasons = decision == null
                ? Collections.<BridgeLifecyclePolicy.Reason>emptySet() : decision.reasons();
        if (changed.allRequestedFailed()) {
            StringBuilder error = new StringBuilder();
            if (!changed.bleDetail().isEmpty()) error.append("BLE: ").append(changed.bleDetail());
            if (!changed.lanDetail().isEmpty()) {
                if (error.length() > 0) error.append("; ");
                error.append("LAN: ").append(changed.lanDetail());
            }
            recordError(this, error.length() == 0 ? null : error.toString());
            Status failed = new Status(false, changed.bleState(), changed.lanState(),
                    changed.bleDetail(), changed.lanDetail(), reasons);
            session = null;
            changed.stop();
            leaveForeground();
            publish(failed);
            stopSelf();
            return;
        }
        publish(new Status(true, changed.bleState(), changed.lanState(), changed.bleDetail(),
                changed.lanDetail(), reasons));
        updateNotification(changed);
    }

    private void stopBridge(Set<BridgeLifecyclePolicy.Reason> reasons) {
        if (session != null) {
            BridgeSession old = session;
            session = null;
            old.stop();
        }
        leaveForeground();
        publish(Status.stopped(reasons.isEmpty()
                ? Collections.<BridgeLifecyclePolicy.Reason>emptySet() : EnumSet.copyOf(reasons)));
        stopSelf();
    }

    private boolean enterForeground(BridgeLifecyclePolicy.StartDecision decision) {
        Notification notification = buildNotification(getString(R.string.bridge_notification_starting));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                boolean connected = decision.useConnectedDeviceServiceType()
                        || Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
                startForeground(NOTIFICATION_ID, notification, connected
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        : ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void leaveForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (RuntimeException ignored) {
        }
    }

    private void updateNotification(BridgeSession current) {
        String text = getString(R.string.bridge_notification_status,
                stateLabel(this, current.bleState()), stateLabel(this, current.lanState()));
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || !canNotify(this)) return;
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (RuntimeException ignored) {
        }
    }

    static String stateLabel(Context context, BridgeSession.TransportState state) {
        switch (state) {
            case STARTING:
                return context.getString(R.string.bridge_state_starting);
            case ACTIVE:
                return context.getString(R.string.bridge_state_active);
            case FAILED:
                return context.getString(R.string.bridge_state_failed);
            case UNAVAILABLE:
                return context.getString(R.string.bridge_state_unavailable);
            default:
                return context.getString(R.string.bridge_state_off);
        }
    }

    private Notification buildNotification(String text) {
        ensureChannels(this);
        Notification.Builder builder = newBuilder(this, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(settingsIntent(this));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, NavBridgeService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        builder.addAction(new Notification.Action.Builder(null,
                getString(R.string.bridge_stop), stop).build());
        return builder.build();
    }

    /**
     * One-time explanation that automatic startup now waits for explicit setup. The notice is
     * marked shown only after it was actually posted; if notifications are not allowed yet
     * (e.g. API 33+ without POST_NOTIFICATIONS), it is retried on the next boot/update broadcast.
     */
    static void postPauseNoticeOnce(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean(KEY_PAUSE_NOTICE_SHOWN, false)) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || !canNotify(context)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) return;
        ensureChannels(context);
        Notification notification = newBuilder(context, CHANNEL_NOTICE)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(context.getString(R.string.bridge_pause_title))
                .setContentText(context.getString(R.string.bridge_pause_short))
                .setStyle(new Notification.BigTextStyle().bigText(context.getString(R.string.bridge_pause_body)))
                .setAutoCancel(true)
                .setContentIntent(settingsIntent(context))
                .build();
        try {
            nm.notify(NOTICE_ID, notification);
            prefs.edit().putBoolean(KEY_PAUSE_NOTICE_SHOWN, true).apply();
        } catch (RuntimeException ignored) {
        }
    }

    static boolean pauseNoticeShown(Context context) {
        return prefs(context).getBoolean(KEY_PAUSE_NOTICE_SHOWN, false);
    }

    private static boolean canNotify(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || granted(context, Manifest.permission.POST_NOTIFICATIONS);
    }

    private static PendingIntent settingsIntent(Context context) {
        Intent open = new Intent(context, BridgeSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder newBuilder(Context context, String channel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return new Notification.Builder(context, channel);
        return new Notification.Builder(context);
    }

    private static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_STATUS,
                context.getString(R.string.bridge_channel_status), NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_NOTICE,
                context.getString(R.string.bridge_channel_notice), NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(BridgeConfiguration.PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            BridgeSession old = session;
            session = null;
            old.stop();
            publish(Status.stopped(Collections.<BridgeLifecyclePolicy.Reason>emptySet()));
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
