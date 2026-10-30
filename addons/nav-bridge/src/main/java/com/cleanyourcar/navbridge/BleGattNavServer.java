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
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.nio.charset.StandardCharsets;

/**
 * Bluetooth BLE GATT Peripheral server ("CarPlayNav") allowing iPhone to push destination
 * coordinates or Plus Codes directly without interrupting Bluetooth A2DP music playback.
 */
@SuppressLint("MissingPermission")
public final class BleGattNavServer {
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
        }

        @Override
        public void onStartFailure(int errorCode) {
        }
    };

    public BleGattNavServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() {
        try {
            BluetoothManager btManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (btManager == null) return;
            BluetoothAdapter adapter = btManager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return;

            if (gattServer == null) {
                gattServer = btManager.openGattServer(context, new BluetoothGattServerCallback() {
                    @Override
                    public void onCharacteristicWriteRequest(
                            BluetoothDevice device,
                            int requestId,
                            BluetoothGattCharacteristic characteristic,
                            boolean preparedWrite,
                            boolean responseNeeded,
                            int offset,
                            byte[] value) {
                        if (responseNeeded && gattServer != null) {
                            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                        }
                        if (value != null && value.length > 0
                                && NavBridgeProtocol.CHAR_NAV_UUID.equals(characteristic.getUuid())) {
                            final String raw = new String(value, StandardCharsets.UTF_8);
                            final String query = NavBridgeProtocol.extractQueryFromHttpOrRaw(raw);
                            if (!query.isEmpty()) {
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        NavBridgeProtocol.dispatchToLauncher(context, query);
                                    }
                                });
                            }
                        }
                    }
                });

                if (gattServer != null) {
                    BluetoothGattService service = new BluetoothGattService(
                            NavBridgeProtocol.SERVICE_UUID,
                            BluetoothGattService.SERVICE_TYPE_PRIMARY);
                    BluetoothGattCharacteristic navChar = new BluetoothGattCharacteristic(
                            NavBridgeProtocol.CHAR_NAV_UUID,
                            BluetoothGattCharacteristic.PROPERTY_WRITE
                                    | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                            BluetoothGattCharacteristic.PERMISSION_WRITE);
                    service.addCharacteristic(navChar);
                    gattServer.addService(service);
                }
            }

            if (adapter.isMultipleAdvertisementSupported()) {
                advertiser = adapter.getBluetoothLeAdvertiser();
                if (advertiser != null) {
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
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public void stop() {
        try {
            if (advertiser != null) {
                advertiser.stopAdvertising(advertiseCallback);
                advertiser = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (gattServer != null) {
                gattServer.close();
                gattServer = null;
            }
        } catch (Throwable ignored) {
        }
    }
}
