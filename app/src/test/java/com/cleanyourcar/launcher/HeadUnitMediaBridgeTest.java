package com.cleanyourcar.launcher;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class HeadUnitMediaBridgeTest {
    @Test
    public void applyBroadcast_updatesSongTitleArtistAndPlayStateWithoutCrashingOnMissingExtras() {
        HeadUnitMediaBridge.MediaState initial = HeadUnitMediaBridge.MediaState.initial();
        Map<String, Object> extras = new HashMap<>();
        extras.put("songname", "Chạy Ngay Đi");
        extras.put("artist", "Sơn Tùng M-TP");

        HeadUnitMediaBridge.MediaState updated = HeadUnitMediaBridge.applyBroadcast(
                initial, HeadUnitMediaBridge.ACTION_WIDGET_SONGNAME, extras);
        assertEquals("Chạy Ngay Đi", updated.title);
        assertTrue(updated.subtitle.contains("Sơn Tùng M-TP"));

        // Empty songname broadcast must preserve last valid title
        Map<String, Object> emptyExtras = new HashMap<>();
        HeadUnitMediaBridge.MediaState preserved = HeadUnitMediaBridge.applyBroadcast(
                updated, HeadUnitMediaBridge.ACTION_WIDGET_SONGNAME, emptyExtras);
        assertEquals("Chạy Ngay Đi", preserved.title);

        Map<String, Object> playExtras = new HashMap<>();
        playExtras.put("playstate", 1);
        HeadUnitMediaBridge.MediaState playing = HeadUnitMediaBridge.applyBroadcast(
                preserved, HeadUnitMediaBridge.ACTION_WIDGET_PLAYSTATE, playExtras);
        assertTrue(playing.playing);
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

        Map<String, Object> btOff = new HashMap<>();
        btOff.put(android.bluetooth.BluetoothAdapter.EXTRA_CONNECTION_STATE,
                android.bluetooth.BluetoothAdapter.STATE_DISCONNECTED);
        HeadUnitMediaBridge.MediaState disconnected = HeadUnitMediaBridge.applyBroadcast(
                HeadUnitMediaBridge.MediaState.initial(),
                android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                btOff);
        assertFalse(disconnected.btConnected);
    }
}
