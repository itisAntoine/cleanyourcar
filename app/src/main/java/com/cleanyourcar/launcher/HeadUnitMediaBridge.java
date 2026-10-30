package com.cleanyourcar.launcher;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Handles XYAuto YT3518C head unit media broadcasts (update.widget.* / xy.android.*),
 * reverse-gear camera interrupts, and standard Android AudioManager media key dispatch.
 */
public final class HeadUnitMediaBridge {
    public static final int SOURCE_BT = 0;
    public static final int SOURCE_USB = 1;

    public static final String ACTION_WIDGET_SONGNAME = "update.widget.songname";
    public static final String ACTION_WIDGET_ALL = "update.widget.all";
    public static final String ACTION_WIDGET_PLAYSTATE = "update.widget.playbtnstate";
    public static final String ACTION_WIDGET_MUSICINIT = "update.widget.musicinit";

    public static final String ACTION_XY_PLAY_PAUSE = "xy.android.playpause";
    public static final String ACTION_XY_NEXT = "xy.android.nextmedia";
    public static final String ACTION_XY_PREV = "xy.android.previousmedia";
    public static final String ACTION_XY_SOURCE_BT = "xy.android.bluetooth";
    public static final String ACTION_XY_SOURCE_USB = "xy.android.music";
    public static final String ACTION_XY_BT_CONNECTED = "xy.android.bluetooth.connected";
    public static final String ACTION_XY_BT_DISCONNECTED = "xy.android.bluetooth.disconnected";

    private static final Set<String> REVERSE_ENTER_ACTIONS = new HashSet<>(Arrays.asList(
            "xy.android.mcu.backcar.in",
            "xy.android.mcu.backcar.in2",
            "xy.android.force.back.in",
            "android.intent.action.START_ARM1_BACKCAR",
            "xy.android.avin.come",
            "xy.android.avin.come.for.video",
            "xy.android.rightavin.come"
    ));

    private static final Set<String> REVERSE_EXIT_ACTIONS = new HashSet<>(Arrays.asList(
            "xy.android.mcu.backcar.out",
            "xy.android.force.back.out",
            "com.autochips.avin.quit",
            "xy.android.avin.leave",
            "xy.android.avin.leave.for.video",
            "xy.android.rightavin.leave"
    ));

    public interface Listener {
        void onMediaStateChanged(MediaState state);
        void onReverseGearChanged(boolean inReverse);
    }

    public static final class MediaState {
        public final int activeSource;
        public final String title;
        public final String subtitle;
        public final boolean playing;
        public final boolean btConnected;

        public MediaState(int activeSource, String title, String subtitle, boolean playing, boolean btConnected) {
            this.activeSource = activeSource;
            this.title = title;
            this.subtitle = subtitle;
            this.playing = playing;
            this.btConnected = btConnected;
        }

        public static MediaState initial() {
            return new MediaState(
                    SOURCE_BT,
                    "Sẵn sàng phát nhạc",
                    "YouTube Music • iPhone Bluetooth",
                    false,
                    true
            );
        }

        public MediaState withSource(int newSource) {
            String nextSub = (newSource == SOURCE_USB)
                    ? "GalaMusic • Ổ đĩa USB"
                    : "YouTube Music • iPhone Bluetooth";
            return new MediaState(newSource, this.title, nextSub, this.playing, this.btConnected);
        }
    }

    private static volatile MediaState sharedState = MediaState.initial();
    private BroadcastReceiver receiver;

    public static MediaState getSharedState() {
        return sharedState;
    }

    public static boolean isReverseEnterAction(String action) {
        return action != null && REVERSE_ENTER_ACTIONS.contains(action);
    }

    public static boolean isReverseExitAction(String action) {
        return action != null && REVERSE_EXIT_ACTIONS.contains(action);
    }

    public static MediaState applyBroadcast(MediaState current, String action, Map<String, Object> extras) {
        if (current == null) current = MediaState.initial();
        if (action == null) return current;
        if (extras == null) extras = new HashMap<>();

        if (ACTION_XY_BT_CONNECTED.equals(action)) {
            return new MediaState(current.activeSource, current.title, current.subtitle, current.playing, true);
        }
        if (ACTION_XY_BT_DISCONNECTED.equals(action)) {
            return new MediaState(current.activeSource, current.title, current.subtitle, current.playing, false);
        }
        if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            Object connState = extras.get(BluetoothAdapter.EXTRA_CONNECTION_STATE);
            if (connState instanceof Number) {
                int st = ((Number) connState).intValue();
                boolean connected = (st == BluetoothAdapter.STATE_CONNECTED);
                return new MediaState(current.activeSource, current.title, current.subtitle, current.playing, connected);
            }
        }
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            Object btState = extras.get(BluetoothAdapter.EXTRA_STATE);
            if (btState instanceof Number) {
                int st = ((Number) btState).intValue();
                if (st == BluetoothAdapter.STATE_OFF || st == BluetoothAdapter.STATE_TURNING_OFF) {
                    return new MediaState(current.activeSource, current.title, current.subtitle, current.playing, false);
                }
            }
        }

        if (ACTION_WIDGET_SONGNAME.equals(action) || ACTION_WIDGET_ALL.equals(action)) {
            String rawSong = firstNonEmpty(extras, "songname", "title", "musicname", "name");
            String rawArtist = firstNonEmpty(extras, "artist", "artistName", "widget_artist", "album");
            String nextTitle = (rawSong != null && !rawSong.isEmpty()) ? rawSong : current.title;
            String sourceTag = (current.activeSource == SOURCE_USB) ? "Nhạc USB" : "Nhạc Bluetooth";
            String nextSub = (rawArtist != null && !rawArtist.isEmpty())
                    ? (rawArtist + " • " + sourceTag)
                    : current.subtitle;
            return new MediaState(current.activeSource, nextTitle, nextSub, current.playing, current.btConnected);
        }

        if (ACTION_WIDGET_PLAYSTATE.equals(action)) {
            boolean nextPlaying = current.playing;
            Object val = firstPresent(extras, "playstate", "playbtnstate", "state", "isPlaying");
            if (val instanceof Boolean) {
                nextPlaying = (Boolean) val;
            } else if (val instanceof Number) {
                nextPlaying = ((Number) val).intValue() > 0;
            } else if (val instanceof String) {
                String s = ((String) val).trim();
                nextPlaying = "1".equals(s) || "true".equalsIgnoreCase(s) || "playing".equalsIgnoreCase(s);
            } else {
                nextPlaying = !current.playing;
            }
            return new MediaState(current.activeSource, current.title, current.subtitle, nextPlaying, current.btConnected);
        }

        return current;
    }

    private static String firstNonEmpty(Map<String, Object> extras, String... keys) {
        for (String k : keys) {
            Object v = extras.get(k);
            if (v instanceof String) {
                String s = ((String) v).trim();
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                    return s;
                }
            }
        }
        return null;
    }

    private static Object firstPresent(Map<String, Object> extras, String... keys) {
        for (String k : keys) {
            if (extras.containsKey(k) && extras.get(k) != null) {
                return extras.get(k);
            }
        }
        return null;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public void register(Context context, final Listener listener) {
        if (receiver != null) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_WIDGET_SONGNAME);
        filter.addAction(ACTION_WIDGET_ALL);
        filter.addAction(ACTION_WIDGET_PLAYSTATE);
        filter.addAction(ACTION_WIDGET_MUSICINIT);
        filter.addAction(ACTION_XY_BT_CONNECTED);
        filter.addAction(ACTION_XY_BT_DISCONNECTED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        for (String act : REVERSE_ENTER_ACTIONS) {
            filter.addAction(act);
        }
        for (String act : REVERSE_EXIT_ACTIONS) {
            filter.addAction(act);
        }

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                String action = intent.getAction();
                if (isReverseEnterAction(action)) {
                    if (listener != null) listener.onReverseGearChanged(true);
                    return;
                }
                if (isReverseExitAction(action)) {
                    if (listener != null) listener.onReverseGearChanged(false);
                    return;
                }

                Map<String, Object> map = bundleToMap(intent.getExtras());
                sharedState = applyBroadcast(sharedState, action, map);
                if (listener != null) {
                    listener.onMediaStateChanged(sharedState);
                }
            }
        };

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    public void unregister(Context context) {
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiver = null;
        }
    }

    private static Map<String, Object> bundleToMap(Bundle bundle) {
        Map<String, Object> map = new HashMap<>();
        if (bundle == null) return map;
        for (String key : bundle.keySet()) {
            map.put(key, bundle.get(key));
        }
        return map;
    }

    public void setSource(Context context, int source, Listener listener) {
        sharedState = sharedState.withSource(source);
        try {
            String action = (source == SOURCE_USB) ? ACTION_XY_SOURCE_USB : ACTION_XY_SOURCE_BT;
            context.sendBroadcast(new Intent(action));
        } catch (Exception ignored) {
        }
        if (listener != null) {
            listener.onMediaStateChanged(sharedState);
        }
    }

    public void sendPlayPause(Context context, Listener listener) {
        sendXyAndMediaKey(context, ACTION_XY_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        sharedState = new MediaState(
                sharedState.activeSource,
                sharedState.title,
                sharedState.subtitle,
                !sharedState.playing,
                sharedState.btConnected);
        if (listener != null) {
            listener.onMediaStateChanged(sharedState);
        }
    }

    public void sendNext(Context context) {
        sendXyAndMediaKey(context, ACTION_XY_NEXT, KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public void sendPrevious(Context context) {
        sendXyAndMediaKey(context, ACTION_XY_PREV, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    private static void sendXyAndMediaKey(Context context, String xyAction, int keyCode) {
        try {
            context.sendBroadcast(new Intent(xyAction));
        } catch (Exception ignored) {
        }
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                long now = SystemClock.uptimeMillis();
                KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
                KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
                am.dispatchMediaKeyEvent(down);
                am.dispatchMediaKeyEvent(up);
            }
        } catch (Exception ignored) {
        }
    }
}
