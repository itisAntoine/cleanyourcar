package com.cleanyourcar.launcher.integration;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.view.KeyEvent;

import com.cleanyourcar.launcher.integration.HeadUnitAdapter.CommandResult;
import com.cleanyourcar.launcher.integration.HeadUnitAdapter.MediaCommand;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class IntegrationControllerTest {

    private static final String XYAUTO = "xyauto-yt3518c";
    private static final String GENERIC = "generic";

    static final class FakeBroadcastTransport implements XyAutoAdapter.BroadcastTransport {
        final List<String> sentCommands = new ArrayList<>();
        Set<String> registeredActions;
        XyAutoAdapter.Receiver receiver;
        boolean unregistered;
        RuntimeException failOnSend;
        RuntimeException failOnRegister;

        @Override
        public void register(Context context, Set<String> actions, XyAutoAdapter.Receiver r) {
            if (failOnRegister != null) {
                throw failOnRegister;
            }
            registeredActions = new LinkedHashSet<>(actions);
            receiver = r;
        }

        @Override
        public void unregister(Context context) {
            unregistered = true;
            receiver = null;
        }

        @Override
        public void send(Context context, String action) {
            if (failOnSend != null) {
                throw failOnSend;
            }
            sentCommands.add(action);
        }

        void deliver(String action, Map<String, Object> extras) {
            receiver.onBroadcast(action, extras);
        }
    }

    static final class FakeMediaKeyTransport implements GenericAndroidAdapter.MediaKeyTransport {
        final List<Integer> sentCommands = new ArrayList<>();

        @Override
        public void dispatch(Context context, int keyCode) {
            sentCommands.add(keyCode);
        }
    }

    static final class RecordingListener implements HeadUnitAdapter.Listener {
        final List<MediaState> mediaStates = new ArrayList<>();
        final List<MediaState.ReverseState> reverseStates = new ArrayList<>();
        final List<HeadUnitAdapter.Health> health = new ArrayList<>();

        @Override
        public void onMediaStateChanged(MediaState state) {
            mediaStates.add(state);
        }

        @Override
        public void onReverseStateChanged(MediaState.ReverseState state) {
            reverseStates.add(state);
        }

        @Override
        public void onHealthChanged(HeadUnitAdapter.Health state, String reason) {
            health.add(state);
        }

        MediaState lastMedia() {
            return mediaStates.get(mediaStates.size() - 1);
        }
    }

    private static IntegrationController controller(String adapterId,
            FakeBroadcastTransport broadcasts, FakeMediaKeyTransport keys, final boolean ready) {
        return IntegrationController.forAdapterId(adapterId, MediaState.unknown(), broadcasts,
                keys, new GenericAndroidAdapter.ReadinessCheck() {
                    @Override
                    public boolean isMediaTransportReady() {
                        return ready;
                    }
                });
    }

    private static Map<String, Object> extras(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    public void generic_sendsNoVendorBroadcasts() {
        FakeBroadcastTransport broadcasts = new FakeBroadcastTransport();
        FakeMediaKeyTransport keys = new FakeMediaKeyTransport();
        IntegrationController ready = controller(GENERIC, broadcasts, keys, true);
        ready.start(null, new RecordingListener());

        assertEquals(CommandResult.Status.SENT, ready.execute(MediaCommand.PLAY_PAUSE).status);
        assertEquals(CommandResult.Status.SENT, ready.execute(MediaCommand.NEXT).status);
        assertEquals(CommandResult.Status.SENT, ready.execute(MediaCommand.PREVIOUS).status);
        CommandResult bt = ready.execute(MediaCommand.SELECT_BLUETOOTH);
        CommandResult local = ready.execute(MediaCommand.SELECT_LOCAL);
        assertEquals(CommandResult.Status.UNAVAILABLE, bt.status);
        assertEquals(CommandResult.Status.UNAVAILABLE, local.status);
        assertNotNull(bt.reason);

        assertTrue(broadcasts.sentCommands.isEmpty());
        assertNull("generic must not listen for vendor broadcasts", broadcasts.registeredActions);
        assertEquals(Arrays.asList(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS), keys.sentCommands);

        FakeMediaKeyTransport unvalidatedKeys = new FakeMediaKeyTransport();
        IntegrationController unvalidated = controller(GENERIC, broadcasts, unvalidatedKeys, false);
        unvalidated.start(null, new RecordingListener());
        for (MediaCommand command : MediaCommand.values()) {
            assertEquals(CommandResult.Status.UNAVAILABLE, unvalidated.execute(command).status);
        }
        assertTrue(unvalidatedKeys.sentCommands.isEmpty());
        assertTrue(broadcasts.sentCommands.isEmpty());
    }

    @Test
    public void mediaTap_usesExactlyOneRoute() {
        FakeBroadcastTransport fakeTransport = new FakeBroadcastTransport();
        FakeMediaKeyTransport xyKeys = new FakeMediaKeyTransport();
        IntegrationController xy = controller(XYAUTO, fakeTransport, xyKeys, true);
        RecordingListener xyListener = new RecordingListener();
        xy.start(null, xyListener);
        int statesBeforeTap = xyListener.mediaStates.size();

        assertEquals(CommandResult.Status.SENT, xy.execute(MediaCommand.PLAY_PAUSE).status);
        assertEquals(1, fakeTransport.sentCommands.size());
        assertEquals(XyAutoAdapter.ACTION_XY_PLAY_PAUSE, fakeTransport.sentCommands.get(0));
        assertTrue("XYAuto tap must not also dispatch a media key", xyKeys.sentCommands.isEmpty());
        // No optimistic state update after a command.
        assertEquals(statesBeforeTap, xyListener.mediaStates.size());
        assertEquals(MediaState.Playback.UNKNOWN, xy.mediaState().playback);

        assertEquals(CommandResult.Status.SENT, xy.execute(MediaCommand.SELECT_LOCAL).status);
        assertEquals(XyAutoAdapter.ACTION_XY_SOURCE_USB, fakeTransport.sentCommands.get(1));
        assertNull("source is not fabricated after a command", xy.mediaState().source);
        assertEquals(statesBeforeTap, xyListener.mediaStates.size());

        FakeBroadcastTransport genericBroadcasts = new FakeBroadcastTransport();
        FakeMediaKeyTransport genericKeys = new FakeMediaKeyTransport();
        IntegrationController generic = controller(GENERIC, genericBroadcasts, genericKeys, true);
        RecordingListener genericListener = new RecordingListener();
        generic.start(null, genericListener);
        int genericStatesBefore = genericListener.mediaStates.size();
        assertEquals(CommandResult.Status.SENT, generic.execute(MediaCommand.PLAY_PAUSE).status);
        assertEquals(1, genericKeys.sentCommands.size());
        assertTrue(genericBroadcasts.sentCommands.isEmpty());
        assertEquals(genericStatesBefore, genericListener.mediaStates.size());
        assertEquals(MediaState.Playback.UNKNOWN, generic.mediaState().playback);
    }

    @Test
    public void adapterSelection_usesXyAutoOnlyForXyAutoProfileOrLegacyUser() {
        assertEquals(XYAUTO, IntegrationController.selectAdapterId(XYAUTO, false));
        assertEquals(XYAUTO, IntegrationController.selectAdapterId(XYAUTO, true));
        assertEquals(GENERIC, IntegrationController.selectAdapterId(GENERIC, false));
        assertEquals(GENERIC, IntegrationController.selectAdapterId(GENERIC, true));
        // Existing users without an accepted profile keep today's XYAuto behavior.
        assertEquals(XYAUTO, IntegrationController.selectAdapterId(null, true));
        assertEquals(GENERIC, IntegrationController.selectAdapterId(null, false));
        assertEquals(GENERIC, IntegrationController.selectAdapterId("unknown-adapter", true));
    }

    @Test
    public void initialState_isUnknown() {
        for (String adapterId : new String[] {XYAUTO, GENERIC}) {
            IntegrationController controller = controller(adapterId, new FakeBroadcastTransport(),
                    new FakeMediaKeyTransport(), true);
            RecordingListener listener = new RecordingListener();
            controller.start(null, listener);

            MediaState initial = listener.lastMedia();
            assertEquals(MediaState.Connection.UNKNOWN, initial.connection);
            assertEquals(MediaState.Playback.UNKNOWN, initial.playback);
            assertNull(initial.title);
            assertNull(initial.artist);
            assertNull(initial.source);
            assertEquals(Arrays.asList(MediaState.ReverseState.UNKNOWN), listener.reverseStates);
            assertEquals(MediaState.ReverseState.UNKNOWN, controller.reverseState());
            assertEquals(HeadUnitAdapter.Health.HEALTHY, listener.health.get(0));
        }
    }

    @Test
    public void shippedAdapters_neverClaimVerifiedReverseButKeepMediaFeatures() {
        FakeBroadcastTransport broadcasts = new FakeBroadcastTransport();
        IntegrationController xyauto = controller(XYAUTO, broadcasts,
                new FakeMediaKeyTransport(), true);
        assertEquals(HeadUnitAdapter.ReverseSupport.UNVERIFIED_EVENTS, xyauto.reverseSupport());
        assertEquals(HeadUnitAdapter.ReverseSupport.NONE,
                controller(GENERIC, new FakeBroadcastTransport(), new FakeMediaKeyTransport(),
                        true).reverseSupport());

        // HUD unavailability does not disable XYAuto media commands or source selection.
        xyauto.start(null, new RecordingListener());
        assertEquals(HeadUnitAdapter.CommandResult.Status.SENT,
                xyauto.execute(HeadUnitAdapter.MediaCommand.PLAY_PAUSE).status);
        assertEquals(HeadUnitAdapter.CommandResult.Status.SENT,
                xyauto.execute(HeadUnitAdapter.MediaCommand.SELECT_LOCAL).status);
    }

    @Test
    public void missingPlaybackExtraDoesNotToggleState() {
        MediaState playing = MediaState.unknown().withPlayback(MediaState.Playback.PLAYING);
        MediaState afterMissing = XyAutoAdapter.applyBroadcast(playing,
                XyAutoAdapter.ACTION_WIDGET_PLAYSTATE, extras());
        assertEquals(MediaState.Playback.PLAYING, afterMissing.playback);

        MediaState unknownAfterMissing = XyAutoAdapter.applyBroadcast(MediaState.unknown(),
                XyAutoAdapter.ACTION_WIDGET_PLAYSTATE, null);
        assertEquals(MediaState.Playback.UNKNOWN, unknownAfterMissing.playback);

        FakeBroadcastTransport fakeTransport = new FakeBroadcastTransport();
        IntegrationController xy = controller(XYAUTO, fakeTransport, new FakeMediaKeyTransport(),
                true);
        RecordingListener listener = new RecordingListener();
        xy.start(null, listener);
        fakeTransport.deliver(XyAutoAdapter.ACTION_WIDGET_PLAYSTATE, extras());
        assertEquals(MediaState.Playback.UNKNOWN, xy.mediaState().playback);
        assertEquals(MediaState.Playback.UNKNOWN, listener.lastMedia().playback);
    }

    @Test
    public void xyauto_preservesKnownMetadataParsing() {
        MediaState s = MediaState.unknown();
        s = XyAutoAdapter.applyBroadcast(s, XyAutoAdapter.ACTION_WIDGET_SONGNAME,
                extras("songname", "Chạy Ngay Đi", "artist", "Sơn Tùng M-TP"));
        assertEquals("Chạy Ngay Đi", s.title);
        assertEquals("Sơn Tùng M-TP", s.artist);

        // Empty, blank and "null" values preserve the last valid metadata.
        s = XyAutoAdapter.applyBroadcast(s, XyAutoAdapter.ACTION_WIDGET_ALL,
                extras("songname", "  ", "title", "null", "artist", ""));
        assertEquals("Chạy Ngay Đi", s.title);
        assertEquals("Sơn Tùng M-TP", s.artist);

        // Alternate keys, in their historical priority order.
        s = XyAutoAdapter.applyBroadcast(s, XyAutoAdapter.ACTION_WIDGET_ALL,
                extras("musicname", "Song B", "name", "ignored", "widget_artist", "Artist B",
                        "album", "ignored"));
        assertEquals("Song B", s.title);
        assertEquals("Artist B", s.artist);

        s = XyAutoAdapter.applyBroadcast(s, XyAutoAdapter.ACTION_WIDGET_PLAYSTATE,
                extras("playstate", 1));
        assertEquals(MediaState.Playback.PLAYING, s.playback);
        s = XyAutoAdapter.applyBroadcast(s, XyAutoAdapter.ACTION_WIDGET_PLAYSTATE,
                extras("playbtnstate", "0"));
        assertEquals(MediaState.Playback.PAUSED, s.playback);
        s = XyAutoAdapter.applyBroadcast(s, XyAutoAdapter.ACTION_WIDGET_PLAYSTATE,
                extras("state", "Playing"));
        assertEquals(MediaState.Playback.PLAYING, s.playback);
        s = XyAutoAdapter.applyBroadcast(s, XyAutoAdapter.ACTION_WIDGET_PLAYSTATE,
                extras("isPlaying", Boolean.FALSE));
        assertEquals(MediaState.Playback.PAUSED, s.playback);

        s = XyAutoAdapter.applyBroadcast(s, XyAutoAdapter.ACTION_XY_BT_CONNECTED, null);
        assertEquals(MediaState.Connection.CONNECTED, s.connection);
        s = XyAutoAdapter.applyBroadcast(s, XyAutoAdapter.ACTION_XY_BT_DISCONNECTED, null);
        assertEquals(MediaState.Connection.DISCONNECTED, s.connection);
        s = XyAutoAdapter.applyBroadcast(s, BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                extras(BluetoothAdapter.EXTRA_CONNECTION_STATE, BluetoothAdapter.STATE_CONNECTED));
        assertEquals(MediaState.Connection.CONNECTED, s.connection);
        s = XyAutoAdapter.applyBroadcast(s, BluetoothAdapter.ACTION_STATE_CHANGED,
                extras(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_TURNING_OFF));
        assertEquals(MediaState.Connection.DISCONNECTED, s.connection);

        MediaState unchanged = XyAutoAdapter.applyBroadcast(s, XyAutoAdapter.ACTION_WIDGET_MUSICINIT,
                extras("songname", "ignored"));
        assertSame(s, unchanged);

        // Receiver wiring: same filter actions as before, reverse only from trusted actions.
        FakeBroadcastTransport fakeTransport = new FakeBroadcastTransport();
        IntegrationController xy = controller(XYAUTO, fakeTransport, new FakeMediaKeyTransport(),
                true);
        RecordingListener listener = new RecordingListener();
        xy.start(null, listener);
        assertTrue(fakeTransport.registeredActions.containsAll(Arrays.asList(
                XyAutoAdapter.ACTION_WIDGET_SONGNAME, XyAutoAdapter.ACTION_WIDGET_ALL,
                XyAutoAdapter.ACTION_WIDGET_PLAYSTATE, XyAutoAdapter.ACTION_WIDGET_MUSICINIT,
                XyAutoAdapter.ACTION_XY_BT_CONNECTED, XyAutoAdapter.ACTION_XY_BT_DISCONNECTED,
                BluetoothAdapter.ACTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                "xy.android.mcu.backcar.in", "xy.android.mcu.backcar.out")));

        fakeTransport.deliver("xy.android.mcu.backcar.in", extras());
        assertEquals(MediaState.ReverseState.ACTIVE, xy.reverseState());
        fakeTransport.deliver("com.autochips.avin.quit", extras());
        assertEquals(MediaState.ReverseState.INACTIVE, xy.reverseState());
        assertEquals(Arrays.asList(MediaState.ReverseState.UNKNOWN,
                MediaState.ReverseState.ACTIVE, MediaState.ReverseState.INACTIVE),
                listener.reverseStates);

        fakeTransport.deliver(XyAutoAdapter.ACTION_WIDGET_SONGNAME,
                extras("songname", "Live Song", "artist", "Live Artist"));
        assertEquals("Live Song", listener.lastMedia().title);
        assertEquals("Live Artist", xy.mediaState().artist);

        xy.stop();
        assertTrue(fakeTransport.unregistered);
        assertFalse(xy.isStarted());
    }

    @Test
    public void adapterFailures_areSurfacedExplicitly() {
        FakeBroadcastTransport failingSend = new FakeBroadcastTransport();
        failingSend.failOnSend = new SecurityException("denied");
        IntegrationController xy = controller(XYAUTO, failingSend, new FakeMediaKeyTransport(),
                true);
        xy.start(null, new RecordingListener());
        CommandResult result = xy.execute(MediaCommand.NEXT);
        assertEquals(CommandResult.Status.FAILED, result.status);
        assertNotNull(result.reason);

        FakeBroadcastTransport failingRegister = new FakeBroadcastTransport();
        failingRegister.failOnRegister = new IllegalStateException("no receiver");
        IntegrationController broken = controller(XYAUTO, failingRegister,
                new FakeMediaKeyTransport(), true);
        RecordingListener listener = new RecordingListener();
        broken.start(null, listener);
        assertEquals(HeadUnitAdapter.Health.FAILED,
                listener.health.get(listener.health.size() - 1));
        assertEquals(MediaState.ReverseState.UNKNOWN, broken.reverseState());

        IntegrationController notStarted = controller(XYAUTO, new FakeBroadcastTransport(),
                new FakeMediaKeyTransport(), true);
        assertEquals(CommandResult.Status.UNAVAILABLE,
                notStarted.execute(MediaCommand.PLAY_PAUSE).status);
    }
}
