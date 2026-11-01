package com.cleanyourcar.navbridge;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * BLE GATT peripheral ("CarPlayNav"). Accepts only versioned, authorized envelopes
 * ({@code CYC-NAV/1}); success is reported only after advertising actually starts.
 * Callers check runtime Bluetooth permissions through {@link BridgeLifecyclePolicy} first.
 */
@SuppressLint("MissingPermission")
public final class BleGattNavServer implements BridgeSession.Transport {
    private static final int ATT_INSUFFICIENT_AUTHORIZATION = 0x08;
    private static final int ATT_INVALID_ATTRIBUTE_LENGTH = 0x0d;
    private static final int ATT_INVALID_OFFSET = 0x07;

    private final Context context;
    private final BridgeRequestHandler handler;
    private final Map<String, ByteArrayOutputStream> preparedWrites = new HashMap<>();
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private volatile BridgeSession.TransportCallback callback;

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            BridgeSession.TransportCallback cb = callback;
            if (cb != null) cb.onStarted("advertising");
        }

        @Override
        public void onStartFailure(int errorCode) {
            BridgeSession.TransportCallback cb = callback;
            if (cb != null) cb.onFailed("BLE advertising failed (code " + errorCode + ")");
        }
    };

    public BleGattNavServer(Context context, BridgeRequestHandler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
    }

    /** Whether this device exposes a BLE advertiser right now (adapter present, on and capable). */
    public static boolean isAdvertisingSupported(BluetoothAdapter adapter) {
        try {
            return adapter != null && adapter.isEnabled() && adapter.isMultipleAdvertisementSupported()
                    && adapter.getBluetoothLeAdvertiser() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public synchronized void start(BridgeSession.TransportCallback cb) {
        callback = cb;
        try {
            BluetoothManager btManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = btManager == null ? null : btManager.getAdapter();
            if (adapter == null) {
                cb.onFailed("Bluetooth unavailable");
                return;
            }
            if (!adapter.isEnabled()) {
                cb.onFailed("Bluetooth is off");
                return;
            }
            if (!isAdvertisingSupported(adapter)) {
                cb.onFailed("BLE advertising unsupported on this device");
                return;
            }
            advertiser = adapter.getBluetoothLeAdvertiser();
            gattServer = btManager.openGattServer(context, gattCallback);
            if (gattServer == null) {
                cb.onFailed("GATT server could not be opened");
                return;
            }
            BluetoothGattService service = new BluetoothGattService(
                    NavBridgeProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            BluetoothGattCharacteristic navChar = new BluetoothGattCharacteristic(
                    NavBridgeProtocol.CHAR_NAV_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);
            service.addCharacteristic(navChar);
            if (!gattServer.addService(service)) {
                cb.onFailed("GATT service could not be registered");
            }
            // Advertising starts from onServiceAdded once the service is really registered.
        } catch (SecurityException e) {
            cb.onFailed("Bluetooth permission denied");
        } catch (RuntimeException e) {
            cb.onFailed("BLE start failed: " + e.getClass().getSimpleName());
        }
    }

    private synchronized void startAdvertising() {
        BridgeSession.TransportCallback cb = callback;
        if (cb == null) return;
        try {
            if (advertiser == null) {
                cb.onFailed("BLE advertising unsupported on this device");
                return;
            }
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(new ParcelUuid(NavBridgeProtocol.SERVICE_UUID))
                    .build();
            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (SecurityException e) {
            cb.onFailed("Bluetooth advertise permission denied");
        } catch (RuntimeException e) {
            cb.onFailed("BLE advertising failed: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void stop() {
        callback = null;
        try {
            if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
        } catch (RuntimeException ignored) {
        }
        advertiser = null;
        try {
            if (gattServer != null) gattServer.close();
        } catch (RuntimeException ignored) {
        }
        gattServer = null;
        synchronized (preparedWrites) {
            preparedWrites.clear();
        }
    }

    private void respond(BluetoothDevice device, int requestId, boolean responseNeeded, int status,
                         int offset, byte[] value) {
        BluetoothGattServer server = gattServer;
        if (!responseNeeded || server == null) return;
        try {
            server.sendResponse(device, requestId, status, offset, value);
        } catch (RuntimeException ignored) {
        }
    }

    private static int attStatus(BridgeRequestHandler.Outcome outcome) {
        switch (outcome) {
            case ACCEPTED:
                return BluetoothGatt.GATT_SUCCESS;
            case UNAUTHORIZED:
                return ATT_INSUFFICIENT_AUTHORIZATION;
            case TOO_LARGE:
                return ATT_INVALID_ATTRIBUTE_LENGTH;
            default:
                return BluetoothGatt.GATT_FAILURE;
        }
    }

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            if (service == null || !NavBridgeProtocol.SERVICE_UUID.equals(service.getUuid())) return;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                startAdvertising();
            } else {
                BridgeSession.TransportCallback cb = callback;
                if (cb != null) cb.onFailed("GATT service registration failed (status " + status + ")");
            }
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED && device != null) {
                synchronized (preparedWrites) {
                    preparedWrites.remove(device.getAddress());
                }
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            if (characteristic == null || !NavBridgeProtocol.CHAR_NAV_UUID.equals(characteristic.getUuid())) {
                respond(device, requestId, responseNeeded, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null);
                return;
            }
            byte[] bytes = value == null ? new byte[0] : value;
            if (preparedWrite) {
                int status = BluetoothGatt.GATT_SUCCESS;
                synchronized (preparedWrites) {
                    String key = device == null ? "" : device.getAddress();
                    ByteArrayOutputStream buffer = preparedWrites.get(key);
                    if (buffer == null) {
                        buffer = new ByteArrayOutputStream();
                        preparedWrites.put(key, buffer);
                    }
                    if (offset != buffer.size()) {
                        status = ATT_INVALID_OFFSET;
                        preparedWrites.remove(key);
                    } else if (buffer.size() + bytes.length > NavBridgeProtocol.MAX_REQUEST_BYTES) {
                        status = ATT_INVALID_ATTRIBUTE_LENGTH;
                        preparedWrites.remove(key);
                    } else {
                        buffer.write(bytes, 0, bytes.length);
                    }
                }
                respond(device, requestId, responseNeeded, status, offset, bytes);
                return;
            }
            if (offset != 0) {
                respond(device, requestId, responseNeeded, ATT_INVALID_OFFSET, offset, null);
                return;
            }
            BridgeRequestHandler.Outcome outcome = handler.handleBleWrite(bytes);
            respond(device, requestId, responseNeeded, attStatus(outcome), 0, null);
        }

        @Override
        public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
            ByteArrayOutputStream buffer;
            synchronized (preparedWrites) {
                buffer = preparedWrites.remove(device == null ? "" : device.getAddress());
            }
            int status = BluetoothGatt.GATT_SUCCESS;
            if (execute && buffer != null) {
                status = attStatus(handler.handleBleWrite(buffer.toByteArray()));
            }
            respond(device, requestId, true, status, 0, null);
        }
    };
}
