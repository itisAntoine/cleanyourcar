package com.cleanyourcar.launcher;

import android.content.Context;
import android.widget.Toast;

import com.cleanyourcar.launcher.integration.HeadUnitAdapter;
import com.cleanyourcar.launcher.integration.MediaState;

/** Display text for {@link MediaState}; unknown fields are shown as absent, never invented. */
final class MediaStateText {

    private MediaStateText() {
    }

    static String title(MediaState state, String placeholder) {
        return state.title != null ? state.title : placeholder;
    }

    static String subtitle(MediaState state, String bluetoothTag, String localTag) {
        String tag = null;
        if (state.source == MediaState.Source.BLUETOOTH) {
            tag = bluetoothTag;
        } else if (state.source == MediaState.Source.LOCAL) {
            tag = localTag;
        }
        if (state.artist != null) {
            return tag != null ? state.artist + " • " + tag : state.artist;
        }
        return tag != null ? tag : "";
    }

    static String connection(MediaState state, String connected, String disconnected) {
        switch (state.connection) {
            case CONNECTED:
                return connected;
            case DISCONNECTED:
                return disconnected;
            default:
                return "";
        }
    }

    /** Shows a short message when a command was not sent. */
    static void reportResult(Context context, HeadUnitAdapter.CommandResult result) {
        if (result == null || result.status == HeadUnitAdapter.CommandResult.Status.SENT) {
            return;
        }
        String prefix = result.status == HeadUnitAdapter.CommandResult.Status.FAILED
                ? "Lỗi điều khiển nhạc" : "Điều khiển nhạc chưa khả dụng";
        Toast.makeText(context, prefix + " (" + result.reason + ")", Toast.LENGTH_SHORT).show();
    }

    /** Shows a short message when the head-unit integration fails. */
    static void reportHealth(Context context, HeadUnitAdapter.Health health, String reason) {
        if (health == HeadUnitAdapter.Health.FAILED) {
            Toast.makeText(context, "Lỗi kết nối head unit (" + reason + ")",
                    Toast.LENGTH_SHORT).show();
        }
    }
}
