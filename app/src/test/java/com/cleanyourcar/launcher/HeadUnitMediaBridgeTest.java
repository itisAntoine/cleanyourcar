package com.cleanyourcar.launcher;

import com.cleanyourcar.launcher.integration.MediaState;
import com.cleanyourcar.launcher.integration.XyAutoAdapter;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class HeadUnitMediaBridgeTest {
    @Test
    public void applyBroadcast_updatesSongTitleArtistAndPlayStateWithoutCrashingOnMissingExtras() {
        MediaState initial = MediaState.unknown();
        Map<String, Object> extras = new HashMap<>();
        extras.put("songname", "Chạy Ngay Đi");
        extras.put("artist", "Sơn Tùng M-TP");

        MediaState updated = XyAutoAdapter.applyBroadcast(
                initial, XyAutoAdapter.ACTION_WIDGET_SONGNAME, extras);
        assertEquals("Chạy Ngay Đi", updated.title);
        assertEquals("Sơn Tùng M-TP", updated.artist);

        // Empty songname broadcast must preserve last valid title
        Map<String, Object> emptyExtras = new HashMap<>();
        MediaState preserved = XyAutoAdapter.applyBroadcast(
                updated, XyAutoAdapter.ACTION_WIDGET_SONGNAME, emptyExtras);
        assertEquals("Chạy Ngay Đi", preserved.title);

        Map<String, Object> playExtras = new HashMap<>();
        playExtras.put("playstate", 1);
        MediaState playing = XyAutoAdapter.applyBroadcast(
                preserved, XyAutoAdapter.ACTION_WIDGET_PLAYSTATE, playExtras);
        assertEquals(MediaState.Playback.PLAYING, playing.playback);
    }

    @Test
    public void reverseGearActions_areRecognizedAccurately() {
        assertTrue(HeadUnitMediaBridge.isReverseEnterAction("xy.android.mcu.backcar.in"));
        assertTrue(HeadUnitMediaBridge.isReverseEnterAction("xy.android.mcu.backcar.in2"));
        assertTrue(HeadUnitMediaBridge.isReverseEnterAction("xy.android.force.back.in"));
        assertTrue(HeadUnitMediaBridge.isReverseEnterAction("android.intent.action.START_ARM1_BACKCAR"));
        assertTrue(HeadUnitMediaBridge.isReverseEnterAction("xy.android.avin.come"));

        assertTrue(HeadUnitMediaBridge.isReverseExitAction("xy.android.mcu.backcar.out"));
        assertTrue(HeadUnitMediaBridge.isReverseExitAction("com.autochips.avin.quit"));
        assertFalse(HeadUnitMediaBridge.isReverseEnterAction("xy.android.mcu.backcar.out"));

        assertEquals(MediaState.ReverseState.ACTIVE,
                XyAutoAdapter.reverseStateFor("xy.android.rightavin.come"));
        assertEquals(MediaState.ReverseState.INACTIVE,
                XyAutoAdapter.reverseStateFor("xy.android.rightavin.leave"));
        assertNull(XyAutoAdapter.reverseStateFor("update.widget.songname"));
        assertNull(XyAutoAdapter.reverseStateFor(null));

        Map<String, Object> btOff = new HashMap<>();
        btOff.put(android.bluetooth.BluetoothAdapter.EXTRA_CONNECTION_STATE,
                android.bluetooth.BluetoothAdapter.STATE_DISCONNECTED);
        MediaState disconnected = XyAutoAdapter.applyBroadcast(
                MediaState.unknown(),
                android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                btOff);
        assertEquals(MediaState.Connection.DISCONNECTED, disconnected.connection);
    }
}
