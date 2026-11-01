package com.cleanyourcar.launcher;

import com.cleanyourcar.launcher.integration.MediaState;
import com.cleanyourcar.launcher.integration.XyAutoAdapter;

/**
 * Temporary compatibility facade over {@link XyAutoAdapter}. Media commands, state, and
 * receivers now live in {@code com.cleanyourcar.launcher.integration}; remove this class once
 * the remaining static callers migrate.
 */
public final class HeadUnitMediaBridge {
    public static final int SOURCE_BT = 0;
    public static final int SOURCE_USB = 1;

    private HeadUnitMediaBridge() {
    }

    public static boolean isReverseEnterAction(String action) {
        return XyAutoAdapter.isReverseEnterAction(action);
    }

    public static boolean isReverseExitAction(String action) {
        return XyAutoAdapter.isReverseExitAction(action);
    }

    /** Maps a media source to the legacy source constant used by {@link CarIntentRouter}. */
    public static int toLegacySource(MediaState.Source source) {
        return source == MediaState.Source.LOCAL ? SOURCE_USB : SOURCE_BT;
    }
}
