package com.cleanyourcar.launcher.integration;

import android.content.Context;

/**
 * Head-unit integration. Implementations publish only state they actually received and never
 * update state as a side effect of a command.
 */
public interface HeadUnitAdapter {

    /** Adapter ID from {@code DeviceProfile.ADAPTER_IDS}. */
    String id();

    /** Registers listeners; publishes the initial (unknown) state and health to the listener. */
    void start(Context context, Listener listener);

    void stop();

    CommandResult execute(MediaCommand command);

    /** How far this adapter's reverse state can be trusted for gating the HUD. */
    ReverseSupport reverseSupport();

    enum ReverseSupport {
        /** The adapter receives no reverse signal; reverse stays UNKNOWN. */
        NONE,
        /**
         * Reverse enter/exit events are forwarded (and still remove overlays), but no verified
         * mechanism establishes the initial state or resynchronizes it, so the HUD stays
         * unavailable on this adapter.
         */
        UNVERIFIED_EVENTS,
        /** Initial state and resynchronization were verified on the device. */
        VERIFIED
    }

    enum MediaCommand { PLAY_PAUSE, NEXT, PREVIOUS, SELECT_BLUETOOTH, SELECT_LOCAL }

    enum Health { HEALTHY, FAILED }

    interface Listener {
        void onMediaStateChanged(MediaState state);

        void onReverseStateChanged(MediaState.ReverseState state);

        /** Reason is null when healthy. */
        void onHealthChanged(Health health, String reason);
    }

    /** Outcome of sending a command; SENT means dispatched, not that the head unit obeyed. */
    final class CommandResult {

        public enum Status { SENT, UNAVAILABLE, FAILED }

        public static final String REASON_NOT_STARTED = "not-started";
        public static final String REASON_NOT_SUPPORTED = "not-supported";
        public static final String REASON_NOT_VALIDATED = "media-transport-not-validated";

        private static final CommandResult SENT = new CommandResult(Status.SENT, null);

        public final Status status;
        /** Null when SENT. */
        public final String reason;

        private CommandResult(Status status, String reason) {
            this.status = status;
            this.reason = reason;
        }

        public static CommandResult sent() {
            return SENT;
        }

        public static CommandResult unavailable(String reason) {
            return new CommandResult(Status.UNAVAILABLE, reason);
        }

        public static CommandResult failed(String reason) {
            return new CommandResult(Status.FAILED, reason);
        }
    }
}
