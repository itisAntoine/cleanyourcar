package com.cleanyourcar.launcher;

import com.cleanyourcar.launcher.integration.MediaState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OverlayReverseStateTest {

    @Test
    public void unknownReverse_requestsNoWindowSyncAndKeepsOverscan() {
        OverlayHudService.StateMachine sm = new OverlayHudService.StateMachine();
        sm.onCommand(OverlayHudService.ACTION_SHOW_EXPANDED);

        assertFalse(sm.onReverseState(MediaState.ReverseState.UNKNOWN));
        assertEquals("0,0,260,0", sm.computeOverscanSpec(true));
        assertTrue(sm.shouldShowExpandedRightCard());
    }

    @Test
    public void unknownReverse_doesNotClearActiveReverse() {
        OverlayHudService.StateMachine sm = new OverlayHudService.StateMachine();
        sm.onCommand(OverlayHudService.ACTION_SHOW_EXPANDED);

        assertTrue(sm.onReverseState(MediaState.ReverseState.ACTIVE));
        assertFalse(sm.onReverseState(MediaState.ReverseState.UNKNOWN));
        assertEquals("0,0,0,0", sm.computeOverscanSpec(true));

        assertTrue(sm.onReverseState(MediaState.ReverseState.INACTIVE));
        assertEquals("0,0,260,0", sm.computeOverscanSpec(true));
    }
}
