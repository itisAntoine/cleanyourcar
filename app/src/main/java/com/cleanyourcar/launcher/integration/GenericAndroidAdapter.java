package com.cleanyourcar.launcher.integration;

import android.content.Context;
import android.view.KeyEvent;

/**
 * Public-API adapter for head units without a vendor integration. Sends no vendor broadcasts,
 * receives no metadata or reverse signal, and dispatches standard media keys only after the
 * media-transport capability has been validated locally.
 */
public final class GenericAndroidAdapter implements HeadUnitAdapter {

    public static final String ADAPTER_ID = "generic";

    /** Android media key dispatch, replaced by a fake in tests. */
    public interface MediaKeyTransport {
        void dispatch(Context context, int keyCode);
    }

    /** Re-evaluated before every command. */
    public interface ReadinessCheck {
        boolean isMediaTransportReady();
    }

    private final MediaKeyTransport transport;
    private final ReadinessCheck readiness;
    private Context context;
    private boolean started;

    public GenericAndroidAdapter(MediaKeyTransport transport, ReadinessCheck readiness) {
        this.transport = transport;
        this.readiness = readiness;
    }

    @Override
    public String id() {
        return ADAPTER_ID;
    }

    @Override
    public void start(Context context, Listener listener) {
        this.context = context;
        started = true;
        listener.onMediaStateChanged(MediaState.unknown());
        listener.onReverseStateChanged(MediaState.ReverseState.UNKNOWN);
        listener.onHealthChanged(Health.HEALTHY, null);
    }

    @Override
    public void stop() {
        started = false;
        context = null;
    }

    @Override
    public CommandResult execute(MediaCommand command) {
        if (!started) {
            return CommandResult.unavailable(CommandResult.REASON_NOT_STARTED);
        }
        int keyCode = keyCodeFor(command);
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return CommandResult.unavailable(CommandResult.REASON_NOT_SUPPORTED);
        }
        if (readiness == null || !readiness.isMediaTransportReady()) {
            return CommandResult.unavailable(CommandResult.REASON_NOT_VALIDATED);
        }
        try {
            transport.dispatch(context, keyCode);
            return CommandResult.sent();
        } catch (RuntimeException e) {
            return CommandResult.failed("dispatch-failed: " + XyAutoAdapter.describe(e));
        }
    }

    private static int keyCodeFor(MediaCommand command) {
        if (command == null) {
            return KeyEvent.KEYCODE_UNKNOWN;
        }
        switch (command) {
            case PLAY_PAUSE:
                return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case NEXT:
                return KeyEvent.KEYCODE_MEDIA_NEXT;
            case PREVIOUS:
                return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            default:
                return KeyEvent.KEYCODE_UNKNOWN;
        }
    }
}
