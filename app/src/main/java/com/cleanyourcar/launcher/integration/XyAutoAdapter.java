package com.cleanyourcar.launcher.integration;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * XYAuto YT3518C head unit: parses its media/reverse broadcasts and sends its vendor command
 * broadcasts. Android wiring lives behind {@link BroadcastTransport}; parsing here is pure.
 */
public final class XyAutoAdapter implements HeadUnitAdapter {

    public static final String ADAPTER_ID = "xyauto-yt3518c";

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

    private static final Set<String> REVERSE_ENTER_ACTIONS = setOf(
            "xy.android.mcu.backcar.in",
            "xy.android.mcu.backcar.in2",
            "xy.android.force.back.in",
            "android.intent.action.START_ARM1_BACKCAR",
            "xy.android.avin.come",
            "xy.android.avin.come.for.video",
            "xy.android.rightavin.come");

    private static final Set<String> REVERSE_EXIT_ACTIONS = setOf(
            "xy.android.mcu.backcar.out",
            "xy.android.force.back.out",
            "com.autochips.avin.quit",
            "xy.android.avin.leave",
            "xy.android.avin.leave.for.video",
            "xy.android.rightavin.leave");

    private static final Set<String> LISTENED_ACTIONS = listenedActions();

    /** Receives broadcasts delivered by a {@link BroadcastTransport}. */
    public interface Receiver {
        void onBroadcast(String action, Map<String, Object> extras);
    }

    /** Android broadcast wiring, replaced by a fake in tests. */
    public interface BroadcastTransport {
        void register(Context context, Set<String> actions, Receiver receiver);

        void unregister(Context context);

        void send(Context context, String action);
    }

    private final BroadcastTransport transport;
    private final MediaState seed;
    private Context context;
    private Listener listener;
    private MediaState state;
    private boolean started;

    /** @param seed last state this process received from the head unit; never invented. */
    public XyAutoAdapter(BroadcastTransport transport, MediaState seed) {
        this.transport = transport;
        this.seed = seed == null ? MediaState.unknown() : seed;
        this.state = this.seed;
    }

    public static boolean isReverseEnterAction(String action) {
        return action != null && REVERSE_ENTER_ACTIONS.contains(action);
    }

    public static boolean isReverseExitAction(String action) {
        return action != null && REVERSE_EXIT_ACTIONS.contains(action);
    }

    /** Returns ACTIVE/INACTIVE for a trusted reverse action, or null for any other action. */
    public static MediaState.ReverseState reverseStateFor(String action) {
        if (isReverseEnterAction(action)) {
            return MediaState.ReverseState.ACTIVE;
        }
        if (isReverseExitAction(action)) {
            return MediaState.ReverseState.INACTIVE;
        }
        return null;
    }

    /** Pure parsing of a received media broadcast; unknown or incomplete input keeps state. */
    public static MediaState applyBroadcast(MediaState current, String action,
            Map<String, Object> extras) {
        if (current == null) {
            current = MediaState.unknown();
        }
        if (action == null) {
            return current;
        }
        if (extras == null) {
            extras = new HashMap<>();
        }

        if (ACTION_XY_BT_CONNECTED.equals(action)) {
            return current.withConnection(MediaState.Connection.CONNECTED);
        }
        if (ACTION_XY_BT_DISCONNECTED.equals(action)) {
            return current.withConnection(MediaState.Connection.DISCONNECTED);
        }
        if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            Object connState = extras.get(BluetoothAdapter.EXTRA_CONNECTION_STATE);
            if (connState instanceof Number) {
                boolean connected =
                        ((Number) connState).intValue() == BluetoothAdapter.STATE_CONNECTED;
                return current.withConnection(connected
                        ? MediaState.Connection.CONNECTED : MediaState.Connection.DISCONNECTED);
            }
        }
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            Object btState = extras.get(BluetoothAdapter.EXTRA_STATE);
            if (btState instanceof Number) {
                int st = ((Number) btState).intValue();
                if (st == BluetoothAdapter.STATE_OFF || st == BluetoothAdapter.STATE_TURNING_OFF) {
                    return current.withConnection(MediaState.Connection.DISCONNECTED);
                }
            }
        }

        if (ACTION_WIDGET_SONGNAME.equals(action) || ACTION_WIDGET_ALL.equals(action)) {
            String rawSong = firstNonEmpty(extras, "songname", "title", "musicname", "name");
            String rawArtist = firstNonEmpty(extras, "artist", "artistName", "widget_artist",
                    "album");
            return current.withMetadata(rawSong != null ? rawSong : current.title,
                    rawArtist != null ? rawArtist : current.artist);
        }

        if (ACTION_WIDGET_PLAYSTATE.equals(action)) {
            Object val = firstPresent(extras, "playstate", "playbtnstate", "state", "isPlaying");
            Boolean playing = null;
            if (val instanceof Boolean) {
                playing = (Boolean) val;
            } else if (val instanceof Number) {
                playing = ((Number) val).intValue() > 0;
            } else if (val instanceof String) {
                String s = ((String) val).trim();
                playing = "1".equals(s) || "true".equalsIgnoreCase(s)
                        || "playing".equalsIgnoreCase(s);
            }
            if (playing == null) {
                return current;
            }
            return current.withPlayback(playing
                    ? MediaState.Playback.PLAYING : MediaState.Playback.PAUSED);
        }

        return current;
    }

    @Override
    public String id() {
        return ADAPTER_ID;
    }

    @Override
    public void start(Context context, Listener listener) {
        if (started) {
            return;
        }
        this.context = context;
        this.listener = listener;
        this.state = seed;
        listener.onMediaStateChanged(state);
        listener.onReverseStateChanged(MediaState.ReverseState.UNKNOWN);
        try {
            transport.register(context, LISTENED_ACTIONS, new Receiver() {
                @Override
                public void onBroadcast(String action, Map<String, Object> extras) {
                    handleBroadcast(action, extras);
                }
            });
        } catch (RuntimeException e) {
            listener.onHealthChanged(Health.FAILED, "register-failed: " + describe(e));
            return;
        }
        started = true;
        listener.onHealthChanged(Health.HEALTHY, null);
    }

    @Override
    public void stop() {
        if (started) {
            try {
                transport.unregister(context);
            } catch (RuntimeException ignored) {
                // Receiver already gone; nothing else to release.
            }
        }
        started = false;
        context = null;
        listener = null;
    }

    @Override
    public CommandResult execute(MediaCommand command) {
        if (!started) {
            return CommandResult.unavailable(CommandResult.REASON_NOT_STARTED);
        }
        String action = vendorAction(command);
        if (action == null) {
            return CommandResult.unavailable(CommandResult.REASON_NOT_SUPPORTED);
        }
        try {
            transport.send(context, action);
            return CommandResult.sent();
        } catch (RuntimeException e) {
            return CommandResult.failed("send-failed: " + describe(e));
        }
    }

    private void handleBroadcast(String action, Map<String, Object> extras) {
        Listener current = listener;
        if (!started || current == null || action == null) {
            return;
        }
        MediaState.ReverseState reverse = reverseStateFor(action);
        if (reverse != null) {
            current.onReverseStateChanged(reverse);
            return;
        }
        MediaState next = applyBroadcast(state, action, extras);
        if (!next.equals(state)) {
            state = next;
            current.onMediaStateChanged(state);
        }
    }

    private static String vendorAction(MediaCommand command) {
        if (command == null) {
            return null;
        }
        switch (command) {
            case PLAY_PAUSE:
                return ACTION_XY_PLAY_PAUSE;
            case NEXT:
                return ACTION_XY_NEXT;
            case PREVIOUS:
                return ACTION_XY_PREV;
            case SELECT_BLUETOOTH:
                return ACTION_XY_SOURCE_BT;
            case SELECT_LOCAL:
                return ACTION_XY_SOURCE_USB;
            default:
                return null;
        }
    }

    static String describe(RuntimeException e) {
        return e.getClass().getSimpleName();
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
            if (extras.get(k) != null) {
                return extras.get(k);
            }
        }
        return null;
    }

    private static Set<String> listenedActions() {
        Set<String> actions = new LinkedHashSet<>(Arrays.asList(
                ACTION_WIDGET_SONGNAME,
                ACTION_WIDGET_ALL,
                ACTION_WIDGET_PLAYSTATE,
                ACTION_WIDGET_MUSICINIT,
                ACTION_XY_BT_CONNECTED,
                ACTION_XY_BT_DISCONNECTED,
                BluetoothAdapter.ACTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED));
        actions.addAll(REVERSE_ENTER_ACTIONS);
        actions.addAll(REVERSE_EXIT_ACTIONS);
        return Collections.unmodifiableSet(actions);
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }
}
