package com.cleanyourcar.launcher.integration;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Android receiver registration and broadcast sending for {@link XyAutoAdapter}. */
public final class AndroidBroadcastTransport implements XyAutoAdapter.BroadcastTransport {

    private BroadcastReceiver registered;

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void register(Context context, Set<String> actions, final XyAutoAdapter.Receiver receiver) {
        if (registered != null) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        for (String action : actions) {
            filter.addAction(action);
        }
        BroadcastReceiver androidReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || intent.getAction() == null) {
                    return;
                }
                receiver.onBroadcast(intent.getAction(), bundleToMap(intent.getExtras()));
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(androidReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(androidReceiver, filter);
        }
        registered = androidReceiver;
    }

    @Override
    public void unregister(Context context) {
        if (registered != null) {
            BroadcastReceiver receiver = registered;
            registered = null;
            context.unregisterReceiver(receiver);
        }
    }

    @Override
    public void send(Context context, String action) {
        context.sendBroadcast(new Intent(action));
    }

    private static Map<String, Object> bundleToMap(Bundle bundle) {
        Map<String, Object> map = new HashMap<>();
        if (bundle == null) {
            return map;
        }
        try {
            for (String key : bundle.keySet()) {
                map.put(key, bundle.get(key));
            }
        } catch (RuntimeException ignored) {
            // Malformed extras (e.g. unknown Parcelable) are treated as missing.
        }
        return map;
    }
}
