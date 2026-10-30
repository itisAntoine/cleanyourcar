package com.cleanyourcar.navbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts NavBridgeService automatically when the head unit boots, turns ACC on, or connects to BT/Wi-Fi.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        try {
            context.startService(new Intent(context, NavBridgeService.class));
        } catch (Throwable ignored) {
        }
    }
}
