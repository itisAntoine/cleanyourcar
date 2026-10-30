package com.cleanyourcar.navbridge;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Background companion service hosting both the BLE GATT Server and the Hotspot Burst receiver.
 */
public class NavBridgeService extends Service {
    private BleGattNavServer bleServer;
    private HotspotBurstServer hotspotServer;

    @Override
    public void onCreate() {
        super.onCreate();
        bleServer = new BleGattNavServer(this);
        hotspotServer = new HotspotBurstServer(this);
        bleServer.start();
        hotspotServer.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (bleServer != null) bleServer.start();
        if (hotspotServer != null) hotspotServer.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (bleServer != null) bleServer.stop();
        if (hotspotServer != null) hotspotServer.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
