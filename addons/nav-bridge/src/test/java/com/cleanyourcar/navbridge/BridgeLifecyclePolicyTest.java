package com.cleanyourcar.navbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

public class BridgeLifecyclePolicyTest {

    static final class FakeTransport implements BridgeSession.Transport {
        int startCalls;
        int stopCalls;
        BridgeSession.TransportCallback callback;

        @Override
        public void start(BridgeSession.TransportCallback callback) {
            startCalls++;
            this.callback = callback;
        }

        @Override
        public void stop() {
            stopCalls++;
        }
    }

    static final class MapStore implements BridgeConfiguration.KeyValueStore {
        final Map<String, Object> values = new HashMap<>();

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            Object v = values.get(key);
            return v instanceof Boolean ? (Boolean) v : defaultValue;
        }

        @Override
        public String getString(String key) {
            Object v = values.get(key);
            return v instanceof String ? (String) v : null;
        }

        @Override
        public void write(Map<String, Object> entries) {
            for (Map.Entry<String, Object> e : entries.entrySet()) {
                if (e.getValue() == null) values.remove(e.getKey());
                else values.put(e.getKey(), e.getValue());
            }
        }
    }

    private static BridgeConfiguration pairedBleAndLan() {
        BridgeAuthorization.Credential c = BridgeAuthorization.newCredential(new SecureRandom());
        return BridgeConfiguration.defaults()
                .withBleEnabled(true)
                .withLanEnabled(true)
                .withCredential(c.clientId, c.token);
    }

    private static BridgeLifecyclePolicy.FeatureState.Builder workingHardware(BridgeConfiguration config) {
        return BridgeLifecyclePolicy.FeatureState.builder(config)
                .bluetoothAdapterPresent(true)
                .bluetoothEnabled(true)
                .bleAdvertisingSupported(true);
    }

    @Test
    public void noOptInStartsNoListener() {
        BridgeConfiguration defaults = BridgeConfiguration.defaults();
        assertFalse(defaults.bleEnabled);
        assertFalse(defaults.lanEnabled);
        assertFalse(defaults.autoStartEnabled);
        assertFalse(defaults.hasCredential());
        assertFalse(defaults.isConfigured());

        BridgeConfiguration fromEmptyStore = BridgeConfiguration.load(new MapStore());
        assertFalse(fromEmptyStore.bleEnabled);
        assertFalse(fromEmptyStore.lanEnabled);
        assertFalse(fromEmptyStore.isConfigured());

        for (int sdk : new int[]{23, 25, 26, 30, 31, 33, 34, 35, 37}) {
            BridgeLifecyclePolicy.StartDecision decision = BridgeLifecyclePolicy.canStart(
                    sdk,
                    BridgeLifecyclePolicy.PermissionState.allGranted(),
                    workingHardware(defaults).build(),
                    true);
            assertFalse(decision.allowed());
            assertFalse(decision.startBle());
            assertFalse(decision.startLan());
            assertTrue(decision.reasons().contains(BridgeLifecyclePolicy.Reason.NOT_OPTED_IN));
        }

        // Opting into a transport without a paired credential still starts nothing.
        BridgeConfiguration noToken = defaults.withLanEnabled(true);
        BridgeLifecyclePolicy.StartDecision noTokenDecision = BridgeLifecyclePolicy.canStart(
                30, BridgeLifecyclePolicy.PermissionState.allGranted(), workingHardware(noToken).build(), true);
        assertFalse(noTokenDecision.allowed());
        assertTrue(noTokenDecision.reasons().contains(BridgeLifecyclePolicy.Reason.NO_CREDENTIAL));

        FakeTransport ble = new FakeTransport();
        FakeTransport lan = new FakeTransport();
        BridgeSession session = new BridgeSession(ble, lan);
        BridgeLifecyclePolicy.StartDecision sessionDecision = session.start(
                30, BridgeLifecyclePolicy.PermissionState.allGranted(), workingHardware(defaults).build(), true);
        assertFalse(sessionDecision.allowed());
        assertEquals(0, ble.startCalls);
        assertEquals(0, lan.startCalls);
        assertEquals(BridgeSession.TransportState.OFF, session.bleState());
        assertEquals(BridgeSession.TransportState.OFF, session.lanState());
        assertFalse(session.isReceiving());

        // Credentials stay private: they never appear in the textual summary.
        BridgeConfiguration paired = pairedBleAndLan();
        assertFalse(paired.toString().contains(paired.token));
        MapStore store = new MapStore();
        paired.save(store);
        BridgeConfiguration reloaded = BridgeConfiguration.load(store);
        assertEquals(paired.clientId, reloaded.clientId);
        assertEquals(paired.token, reloaded.token);
        assertTrue(reloaded.authorization().verify(paired.clientId, paired.token));
        BridgeConfiguration revoked = reloaded.withoutCredential();
        revoked.save(store);
        assertNull(BridgeConfiguration.load(store).token);
        assertFalse(BridgeConfiguration.load(store).authorization().verify(paired.clientId, paired.token));
    }

    @Test
    public void bleUnavailableDoesNotClaimSuccess() {
        BridgeConfiguration bleOnly = pairedBleAndLan().withLanEnabled(false);
        BridgeLifecyclePolicy.PermissionState granted = BridgeLifecyclePolicy.PermissionState.allGranted();

        BridgeLifecyclePolicy.StartDecision noAdvertising = BridgeLifecyclePolicy.canStart(
                30, granted, workingHardware(bleOnly).bleAdvertisingSupported(false).build(), true);
        assertFalse(noAdvertising.allowed());
        assertFalse(noAdvertising.startBle());
        assertTrue(noAdvertising.reasons().contains(BridgeLifecyclePolicy.Reason.BLE_ADVERTISING_UNSUPPORTED));

        BridgeLifecyclePolicy.StartDecision noAdapter = BridgeLifecyclePolicy.canStart(
                30, granted, workingHardware(bleOnly).bluetoothAdapterPresent(false).build(), true);
        assertFalse(noAdapter.startBle());
        assertTrue(noAdapter.reasons().contains(BridgeLifecyclePolicy.Reason.BLUETOOTH_UNAVAILABLE));

        BridgeLifecyclePolicy.StartDecision disabled = BridgeLifecyclePolicy.canStart(
                30, granted, workingHardware(bleOnly).bluetoothEnabled(false).build(), true);
        assertFalse(disabled.startBle());
        assertTrue(disabled.reasons().contains(BridgeLifecyclePolicy.Reason.BLUETOOTH_DISABLED));

        BridgeLifecyclePolicy.PermissionState noRuntimeBt = BridgeLifecyclePolicy.PermissionState.builder()
                .legacyBluetooth(true).bluetoothConnect(false).bluetoothAdvertise(false).notifications(true).build();
        BridgeLifecyclePolicy.StartDecision modernDenied = BridgeLifecyclePolicy.canStart(
                31, noRuntimeBt, workingHardware(bleOnly).build(), true);
        assertFalse(modernDenied.startBle());
        assertTrue(modernDenied.reasons().contains(BridgeLifecyclePolicy.Reason.BLUETOOTH_PERMISSION_MISSING));
        // The same permissions are install-time on API 30, so BLE may start there.
        assertTrue(BridgeLifecyclePolicy.canStart(30, noRuntimeBt, workingHardware(bleOnly).build(), true).startBle());

        // LAN has no legal foreground service type on API 34+: reported unavailable, never faked.
        BridgeConfiguration lanOnly = pairedBleAndLan().withBleEnabled(false);
        BridgeLifecyclePolicy.StartDecision lan34 = BridgeLifecyclePolicy.canStart(
                34, granted, workingHardware(lanOnly).build(), true);
        assertFalse(lan34.allowed());
        assertTrue(lan34.reasons().contains(BridgeLifecyclePolicy.Reason.LAN_LIFECYCLE_UNAVAILABLE));
        BridgeLifecyclePolicy.StartDecision lan33 = BridgeLifecyclePolicy.canStart(
                33, granted, workingHardware(lanOnly).build(), true);
        assertTrue(lan33.startLan());
        assertFalse(lan33.useConnectedDeviceServiceType());
        assertTrue(BridgeLifecyclePolicy.canStart(34, granted, workingHardware(bleOnly).build(), true)
                .useConnectedDeviceServiceType());

        // Policy approval is not success: state stays STARTING until the transport confirms.
        FakeTransport ble = new FakeTransport();
        FakeTransport lan = new FakeTransport();
        BridgeSession session = new BridgeSession(ble, lan);
        BridgeLifecyclePolicy.StartDecision decision = session.start(
                30, granted, workingHardware(pairedBleAndLan()).build(), true);
        assertTrue(decision.allowed());
        assertEquals(1, ble.startCalls);
        assertEquals(1, lan.startCalls);
        assertEquals(BridgeSession.TransportState.STARTING, session.bleState());
        assertFalse(session.isReceiving());

        ble.callback.onFailed("advertise error 1");
        assertEquals(BridgeSession.TransportState.FAILED, session.bleState());
        assertEquals("advertise error 1", session.bleDetail());
        assertFalse(session.isReceiving());
        ble.callback.onStarted("late success must be ignored");
        assertEquals(BridgeSession.TransportState.FAILED, session.bleState());

        lan.callback.onStarted("port 48920");
        assertEquals(BridgeSession.TransportState.ACTIVE, session.lanState());
        assertTrue(session.isReceiving());
        assertFalse(session.allRequestedFailed());

        // Unavailable BLE on a session never invokes the BLE transport.
        FakeTransport ble2 = new FakeTransport();
        FakeTransport lan2 = new FakeTransport();
        BridgeSession session2 = new BridgeSession(ble2, lan2);
        session2.start(30, granted, workingHardware(bleOnly).bleAdvertisingSupported(false).build(), true);
        assertEquals(0, ble2.startCalls);
        assertEquals(BridgeSession.TransportState.UNAVAILABLE, session2.bleState());
        assertNotEquals(BridgeSession.TransportState.ACTIVE, session2.bleState());
        assertFalse(session2.isReceiving());

        session.stop();
        assertEquals(1, ble.stopCalls);
        assertEquals(1, lan.stopCalls);
        assertEquals(BridgeSession.TransportState.OFF, session.lanState());
        assertFalse(session.isReceiving());
    }

    @Test
    public void bootDoesNotEnableUnconfiguredBridge() {
        String[] triggers = {
                BridgeLifecyclePolicy.ACTION_BOOT_COMPLETED,
                BridgeLifecyclePolicy.ACTION_XY_ACC_ON,
                BridgeLifecyclePolicy.ACTION_XY_BLUETOOTH_CONNECTED,
                "android.net.wifi.STATE_CHANGE",
                BridgeLifecyclePolicy.ACTION_MY_PACKAGE_REPLACED,
        };
        BridgeConfiguration upgraded = BridgeConfiguration.load(new MapStore());
        for (int sdk : new int[]{23, 25, 26, 30, 31, 34, 35}) {
            for (String action : triggers) {
                assertFalse(sdk + " " + action, BridgeLifecyclePolicy.shouldAutoStart(sdk, action, upgraded));
            }
        }

        // Even a paired, opted-in bridge needs explicit auto-start consent.
        BridgeConfiguration paired = pairedBleAndLan().withLanEnabled(false);
        assertFalse(paired.autoStartEnabled);
        for (String action : triggers) {
            assertFalse(BridgeLifecyclePolicy.shouldAutoStart(25, action, paired));
        }
        BridgeLifecyclePolicy.StartDecision automatic = BridgeLifecyclePolicy.canStart(
                25, BridgeLifecyclePolicy.PermissionState.allGranted(), workingHardware(paired).build(), false);
        assertFalse(automatic.allowed());
        assertTrue(automatic.reasons().contains(BridgeLifecyclePolicy.Reason.AUTO_START_NOT_CONSENTED));

        // With consent: boot is a legal trigger; XYAuto broadcasts only where background starts are legal.
        BridgeConfiguration consented = paired.withAutoStartEnabled(true);
        assertTrue(BridgeLifecyclePolicy.shouldAutoStart(25, BridgeLifecyclePolicy.ACTION_BOOT_COMPLETED, consented));
        assertTrue(BridgeLifecyclePolicy.shouldAutoStart(34, BridgeLifecyclePolicy.ACTION_BOOT_COMPLETED, consented));
        assertTrue(BridgeLifecyclePolicy.shouldAutoStart(25, BridgeLifecyclePolicy.ACTION_XY_ACC_ON, consented));
        assertFalse(BridgeLifecyclePolicy.shouldAutoStart(31, BridgeLifecyclePolicy.ACTION_XY_ACC_ON, consented));
        assertFalse(BridgeLifecyclePolicy.shouldAutoStart(31,
                BridgeLifecyclePolicy.ACTION_XY_BLUETOOTH_CONNECTED, consented));
        assertFalse(BridgeLifecyclePolicy.shouldAutoStart(25, "android.net.wifi.STATE_CHANGE", consented));
        assertFalse(BridgeLifecyclePolicy.shouldAutoStart(25, BridgeLifecyclePolicy.ACTION_MY_PACKAGE_REPLACED,
                consented));
        assertFalse(BridgeLifecyclePolicy.shouldAutoStart(25, null, consented));
        assertTrue(BridgeLifecyclePolicy.canStart(25, BridgeLifecyclePolicy.PermissionState.allGranted(),
                workingHardware(consented).build(), false).allowed());

        // Automatic starts on API 33+ need notification permission so the bridge never runs invisibly.
        BridgeLifecyclePolicy.PermissionState noNotifications = BridgeLifecyclePolicy.PermissionState.builder()
                .legacyBluetooth(true).bluetoothConnect(true).bluetoothAdvertise(true).notifications(false).build();
        BridgeLifecyclePolicy.StartDecision silent = BridgeLifecyclePolicy.canStart(
                33, noNotifications, workingHardware(consented).build(), false);
        assertFalse(silent.allowed());
        assertTrue(silent.reasons().contains(BridgeLifecyclePolicy.Reason.NOTIFICATION_PERMISSION_MISSING));

        // Upgrade notice is shown only for an unconfigured bridge on package replacement or boot.
        assertTrue(BridgeLifecyclePolicy.shouldExplainPause(BridgeLifecyclePolicy.ACTION_MY_PACKAGE_REPLACED,
                upgraded, false));
        assertFalse(BridgeLifecyclePolicy.shouldExplainPause(BridgeLifecyclePolicy.ACTION_MY_PACKAGE_REPLACED,
                upgraded, true));
        assertFalse(BridgeLifecyclePolicy.shouldExplainPause(BridgeLifecyclePolicy.ACTION_BOOT_COMPLETED,
                consented, false));
    }
}
