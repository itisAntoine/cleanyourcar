package com.cleanyourcar.navbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Handles boot, package upgrade and XYAuto ACC/Bluetooth broadcasts. It never starts an
 * unconfigured bridge: automatic start requires setup, explicit auto-start consent and a trigger
 * from which the OS allows a foreground service start. An unconfigured bridge instead explains
 * once that automatic startup is paused until setup.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!BridgeLifecyclePolicy.ACTION_BOOT_COMPLETED.equals(action)
                && !BridgeLifecyclePolicy.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !BridgeLifecyclePolicy.ACTION_XY_ACC_ON.equals(action)
                && !BridgeLifecyclePolicy.ACTION_XY_BLUETOOTH_CONNECTED.equals(action)) {
            return;
        }
        try {
            BridgeConfiguration config = BridgeConfiguration.load(context);
            if (BridgeLifecyclePolicy.shouldExplainPause(action, config,
                    NavBridgeService.pauseNoticeShown(context))) {
                NavBridgeService.postPauseNoticeOnce(context);
                return;
            }
            if (BridgeLifecyclePolicy.shouldAutoStart(Build.VERSION.SDK_INT, action, config)) {
                NavBridgeService.requestStart(context, false);
            }
        } catch (RuntimeException ignored) {
            // A failed automatic start stays off; the settings screen shows the real state.
        }
    }
}
