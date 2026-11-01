package com.cleanyourcar.navbridge;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable bridge setup. BLE and LAN reception are independent and both off by default; automatic
 * start needs its own consent. The paired-client credential lives only in this app's private
 * preferences (backup and device transfer excluded) and has no export path.
 */
public final class BridgeConfiguration {
    public static final String PREFS_NAME = "navbridge_config";

    static final String KEY_BLE_ENABLED = "ble_enabled";
    static final String KEY_LAN_ENABLED = "lan_enabled";
    static final String KEY_AUTO_START = "auto_start_enabled";
    static final String KEY_CLIENT_ID = "client_id";
    static final String KEY_TOKEN = "client_token";

    /** Minimal persistence seam so tests can use an in-memory fake. */
    public interface KeyValueStore {
        boolean getBoolean(String key, boolean defaultValue);

        String getString(String key);

        /** Writes all entries atomically; a null value removes the key. */
        void write(Map<String, Object> entries);
    }

    public final boolean bleEnabled;
    public final boolean lanEnabled;
    public final boolean autoStartEnabled;
    public final String clientId;
    public final String token;

    private BridgeConfiguration(boolean bleEnabled, boolean lanEnabled, boolean autoStartEnabled,
                                String clientId, String token) {
        this.bleEnabled = bleEnabled;
        this.lanEnabled = lanEnabled;
        this.autoStartEnabled = autoStartEnabled;
        boolean validCredential = BridgeAuthorization.isValidClientId(clientId)
                && BridgeAuthorization.isValidToken(token);
        this.clientId = validCredential ? clientId : null;
        this.token = validCredential ? token : null;
    }

    public static BridgeConfiguration defaults() {
        return new BridgeConfiguration(false, false, false, null, null);
    }

    public static BridgeConfiguration load(KeyValueStore store) {
        return new BridgeConfiguration(
                store.getBoolean(KEY_BLE_ENABLED, false),
                store.getBoolean(KEY_LAN_ENABLED, false),
                store.getBoolean(KEY_AUTO_START, false),
                store.getString(KEY_CLIENT_ID),
                store.getString(KEY_TOKEN));
    }

    public static BridgeConfiguration load(Context context) {
        return load(preferences(context));
    }

    public void save(KeyValueStore store) {
        Map<String, Object> entries = new HashMap<>();
        entries.put(KEY_BLE_ENABLED, bleEnabled);
        entries.put(KEY_LAN_ENABLED, lanEnabled);
        entries.put(KEY_AUTO_START, autoStartEnabled);
        entries.put(KEY_CLIENT_ID, clientId);
        entries.put(KEY_TOKEN, token);
        store.write(entries);
    }

    public void save(Context context) {
        save(preferences(context));
    }

    public BridgeConfiguration withBleEnabled(boolean enabled) {
        return new BridgeConfiguration(enabled, lanEnabled, autoStartEnabled, clientId, token);
    }

    public BridgeConfiguration withLanEnabled(boolean enabled) {
        return new BridgeConfiguration(bleEnabled, enabled, autoStartEnabled, clientId, token);
    }

    public BridgeConfiguration withAutoStartEnabled(boolean enabled) {
        return new BridgeConfiguration(bleEnabled, lanEnabled, enabled, clientId, token);
    }

    public BridgeConfiguration withCredential(String newClientId, String newToken) {
        return new BridgeConfiguration(bleEnabled, lanEnabled, autoStartEnabled, newClientId, newToken);
    }

    public BridgeConfiguration withoutCredential() {
        return new BridgeConfiguration(bleEnabled, lanEnabled, autoStartEnabled, null, null);
    }

    public boolean hasCredential() {
        return clientId != null && token != null;
    }

    public boolean anyTransportEnabled() {
        return bleEnabled || lanEnabled;
    }

    /** True only when the user opted into at least one transport and paired a client. */
    public boolean isConfigured() {
        return anyTransportEnabled() && hasCredential();
    }

    public BridgeAuthorization authorization() {
        return hasCredential() ? BridgeAuthorization.forClient(clientId, token) : BridgeAuthorization.none();
    }

    /** Value placed after {@code Bearer } in HTTP and inside the BLE envelope, or null. */
    public String bearerValue() {
        return hasCredential() ? clientId + "." + token : null;
    }

    @Override
    public String toString() {
        return "BridgeConfiguration{ble=" + bleEnabled + ", lan=" + lanEnabled + ", autoStart="
                + autoStartEnabled + ", paired=" + hasCredential() + "}";
    }

    static SharedPreferencesStore preferences(Context context) {
        return new SharedPreferencesStore(
                context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    static final class SharedPreferencesStore implements KeyValueStore {
        private final SharedPreferences prefs;

        SharedPreferencesStore(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            try {
                return prefs.getBoolean(key, defaultValue);
            } catch (ClassCastException e) {
                return defaultValue;
            }
        }

        @Override
        public String getString(String key) {
            try {
                return prefs.getString(key, null);
            } catch (ClassCastException e) {
                return null;
            }
        }

        @Override
        public void write(Map<String, Object> entries) {
            SharedPreferences.Editor editor = prefs.edit();
            for (Map.Entry<String, Object> e : entries.entrySet()) {
                Object v = e.getValue();
                if (v == null) editor.remove(e.getKey());
                else if (v instanceof Boolean) editor.putBoolean(e.getKey(), (Boolean) v);
                else editor.putString(e.getKey(), String.valueOf(v));
            }
            editor.apply();
        }
    }
}
