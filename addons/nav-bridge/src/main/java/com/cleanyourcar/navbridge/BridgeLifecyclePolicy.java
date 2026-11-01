package com.cleanyourcar.navbridge;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Pure start policy for the optional bridge. A positive decision only permits an attempt; a
 * transport counts as receiving only after the real operation reports success (see
 * {@link BridgeSession}).
 */
public final class BridgeLifecyclePolicy {
    public static final String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    public static final String ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED";
    public static final String ACTION_XY_ACC_ON = "xy.android.acc.on";
    public static final String ACTION_XY_BLUETOOTH_CONNECTED = "xy.android.bluetooth.connected";

    static final int SDK_O = 26;
    static final int SDK_R = 30;
    static final int SDK_S = 31;
    static final int SDK_TIRAMISU = 33;
    static final int SDK_UPSIDE_DOWN_CAKE = 34;

    public enum Reason {
        NOT_OPTED_IN,
        NO_CREDENTIAL,
        AUTO_START_NOT_CONSENTED,
        NOTIFICATION_PERMISSION_MISSING,
        BLUETOOTH_PERMISSION_MISSING,
        BLUETOOTH_UNAVAILABLE,
        BLUETOOTH_DISABLED,
        BLE_ADVERTISING_UNSUPPORTED,
        LAN_LIFECYCLE_UNAVAILABLE
    }

    private BridgeLifecyclePolicy() {
    }

    public static StartDecision canStart(int sdk, PermissionState permissions, FeatureState features,
                                         boolean userInitiated) {
        EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
        if (!features.bleRequested && !features.lanRequested) {
            reasons.add(Reason.NOT_OPTED_IN);
            return StartDecision.deny(reasons);
        }
        if (!features.credentialPresent) {
            reasons.add(Reason.NO_CREDENTIAL);
            return StartDecision.deny(reasons);
        }
        if (!userInitiated && !features.autoStartConsented) {
            reasons.add(Reason.AUTO_START_NOT_CONSENTED);
            return StartDecision.deny(reasons);
        }
        boolean notificationsMissing = sdk >= SDK_TIRAMISU && !permissions.notifications;
        if (notificationsMissing) {
            reasons.add(Reason.NOTIFICATION_PERMISSION_MISSING);
            if (!userInitiated) return StartDecision.deny(reasons);
        }

        boolean ble = false;
        if (features.bleRequested) {
            boolean btPermission = sdk >= SDK_S
                    ? permissions.bluetoothConnect && permissions.bluetoothAdvertise
                    : permissions.legacyBluetooth;
            if (!btPermission) {
                reasons.add(Reason.BLUETOOTH_PERMISSION_MISSING);
            } else if (!features.bluetoothAdapterPresent) {
                reasons.add(Reason.BLUETOOTH_UNAVAILABLE);
            } else if (!features.bluetoothEnabled) {
                reasons.add(Reason.BLUETOOTH_DISABLED);
            } else if (!features.bleAdvertisingSupported) {
                reasons.add(Reason.BLE_ADVERTISING_UNSUPPORTED);
            } else {
                ble = true;
            }
        }

        boolean lan = false;
        if (features.lanRequested) {
            // No foreground service type legitimately covers an inbound LAN listener on API 34+.
            if (sdk >= SDK_UPSIDE_DOWN_CAKE) reasons.add(Reason.LAN_LIFECYCLE_UNAVAILABLE);
            else lan = true;
        }

        if (!ble && !lan) return StartDecision.deny(reasons);
        return new StartDecision(true, ble, lan, sdk >= SDK_O, ble && sdk >= 29, reasons);
    }

    /** Whether a broadcast may automatically start an already configured, consented bridge. */
    public static boolean shouldAutoStart(int sdk, String action, BridgeConfiguration config) {
        if (action == null || config == null) return false;
        if (!config.isConfigured() || !config.autoStartEnabled) return false;
        if (ACTION_BOOT_COMPLETED.equals(action)) return true;
        if (ACTION_XY_ACC_ON.equals(action) || ACTION_XY_BLUETOOTH_CONNECTED.equals(action)) {
            // Starting a foreground service from these background broadcasts is illegal on API 31+.
            return sdk < SDK_S;
        }
        return false;
    }

    /** Whether to show the one-time "bridge paused until set up" notice. */
    public static boolean shouldExplainPause(String action, BridgeConfiguration config, boolean alreadyShown) {
        if (alreadyShown || config == null || config.isConfigured()) return false;
        return ACTION_MY_PACKAGE_REPLACED.equals(action) || ACTION_BOOT_COMPLETED.equals(action);
    }

    public static final class PermissionState {
        public final boolean legacyBluetooth;
        public final boolean bluetoothConnect;
        public final boolean bluetoothAdvertise;
        public final boolean notifications;

        private PermissionState(Builder b) {
            legacyBluetooth = b.legacyBluetooth;
            bluetoothConnect = b.bluetoothConnect;
            bluetoothAdvertise = b.bluetoothAdvertise;
            notifications = b.notifications;
        }

        public static PermissionState allGranted() {
            return builder().legacyBluetooth(true).bluetoothConnect(true).bluetoothAdvertise(true)
                    .notifications(true).build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private boolean legacyBluetooth;
            private boolean bluetoothConnect;
            private boolean bluetoothAdvertise;
            private boolean notifications;

            public Builder legacyBluetooth(boolean v) {
                legacyBluetooth = v;
                return this;
            }

            public Builder bluetoothConnect(boolean v) {
                bluetoothConnect = v;
                return this;
            }

            public Builder bluetoothAdvertise(boolean v) {
                bluetoothAdvertise = v;
                return this;
            }

            public Builder notifications(boolean v) {
                notifications = v;
                return this;
            }

            public PermissionState build() {
                return new PermissionState(this);
            }
        }
    }

    public static final class FeatureState {
        public final boolean bleRequested;
        public final boolean lanRequested;
        public final boolean credentialPresent;
        public final boolean autoStartConsented;
        public final boolean bluetoothAdapterPresent;
        public final boolean bluetoothEnabled;
        public final boolean bleAdvertisingSupported;

        private FeatureState(Builder b) {
            bleRequested = b.bleRequested;
            lanRequested = b.lanRequested;
            credentialPresent = b.credentialPresent;
            autoStartConsented = b.autoStartConsented;
            bluetoothAdapterPresent = b.bluetoothAdapterPresent;
            bluetoothEnabled = b.bluetoothEnabled;
            bleAdvertisingSupported = b.bleAdvertisingSupported;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static Builder builder(BridgeConfiguration config) {
            Builder b = new Builder();
            b.bleRequested = config.bleEnabled;
            b.lanRequested = config.lanEnabled;
            b.credentialPresent = config.hasCredential();
            b.autoStartConsented = config.autoStartEnabled;
            return b;
        }

        public static final class Builder {
            private boolean bleRequested;
            private boolean lanRequested;
            private boolean credentialPresent;
            private boolean autoStartConsented;
            private boolean bluetoothAdapterPresent;
            private boolean bluetoothEnabled;
            private boolean bleAdvertisingSupported;

            public Builder bluetoothAdapterPresent(boolean v) {
                bluetoothAdapterPresent = v;
                return this;
            }

            public Builder bluetoothEnabled(boolean v) {
                bluetoothEnabled = v;
                return this;
            }

            public Builder bleAdvertisingSupported(boolean v) {
                bleAdvertisingSupported = v;
                return this;
            }

            public FeatureState build() {
                return new FeatureState(this);
            }
        }
    }

    public static final class StartDecision {
        private final boolean allowed;
        private final boolean startBle;
        private final boolean startLan;
        private final boolean useForegroundService;
        private final boolean useConnectedDeviceServiceType;
        private final Set<Reason> reasons;

        StartDecision(boolean allowed, boolean startBle, boolean startLan, boolean useForegroundService,
                      boolean useConnectedDeviceServiceType, EnumSet<Reason> reasons) {
            this.allowed = allowed;
            this.startBle = startBle;
            this.startLan = startLan;
            this.useForegroundService = useForegroundService;
            this.useConnectedDeviceServiceType = useConnectedDeviceServiceType;
            this.reasons = Collections.unmodifiableSet(EnumSet.copyOf(reasons));
        }

        static StartDecision deny(EnumSet<Reason> reasons) {
            return new StartDecision(false, false, false, false, false, reasons);
        }

        public boolean allowed() {
            return allowed;
        }

        public boolean startBle() {
            return startBle;
        }

        public boolean startLan() {
            return startLan;
        }

        public boolean useForegroundService() {
            return useForegroundService;
        }

        /** True only when BLE runs (API 29+), the sole case with a legitimate service type. */
        public boolean useConnectedDeviceServiceType() {
            return useConnectedDeviceServiceType;
        }

        /** Denial reasons plus warnings for transports that will not start. */
        public Set<Reason> reasons() {
            return reasons;
        }

        @Override
        public String toString() {
            return "StartDecision{allowed=" + allowed + ", ble=" + startBle + ", lan=" + startLan
                    + ", reasons=" + reasons + "}";
        }
    }
}
