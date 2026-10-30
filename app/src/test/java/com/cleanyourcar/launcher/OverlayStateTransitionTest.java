package com.cleanyourcar.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class OverlayStateTransitionTest {
    @Test
    public void overlayController_collapsesExpandsAndImmediatelyYieldsToReverseGear() {
        OverlayHudService.StateMachine sm = new OverlayHudService.StateMachine();
        assertFalse(sm.isActive());

        sm.onCommand(OverlayHudService.ACTION_SHOW_EXPANDED);
        assertTrue(sm.isActive());
        assertFalse(sm.isCollapsed());
        assertFalse(sm.shouldShowLeftDock());
        assertTrue(sm.shouldShowExpandedRightCard());
        assertFalse(sm.shouldShowCollapsedPill());
        assertEquals("0,0,260,0", sm.computeOverscanSpec(true));

        sm.onCommand(OverlayHudService.ACTION_SHOW_COLLAPSED);
        assertTrue(sm.isActive());
        assertTrue(sm.isCollapsed());
        assertFalse(sm.shouldShowLeftDock());
        assertFalse(sm.shouldShowExpandedRightCard());
        assertTrue(sm.shouldShowCollapsedPill());
        assertEquals("0,0,0,0", sm.computeOverscanSpec(true));

        // Enter reverse gear -> all overlay windows must hide immediately
        sm.onReverseGear(true);
        assertFalse(sm.shouldShowLeftDock());
        assertFalse(sm.shouldShowExpandedRightCard());
        assertFalse(sm.shouldShowCollapsedPill());
        assertEquals("0,0,0,0", sm.computeOverscanSpec(true));

        // Exit reverse gear -> restores exact prior collapsed state
        sm.onReverseGear(false);
        assertFalse(sm.shouldShowLeftDock());
        assertTrue(sm.shouldShowCollapsedPill());

        // Verify shouldHideForCarEvent for all 5 reverse/AV-in actions and exit actions
        assertTrue(OverlayHudService.shouldHideForCarEvent("xy.android.mcu.backcar.in"));
        assertTrue(OverlayHudService.shouldHideForCarEvent("xy.android.mcu.backcar.in2"));
        assertTrue(OverlayHudService.shouldHideForCarEvent("xy.android.force.back.in"));
        assertTrue(OverlayHudService.shouldHideForCarEvent("android.intent.action.START_ARM1_BACKCAR"));
        assertTrue(OverlayHudService.shouldHideForCarEvent("xy.android.avin.come"));
        assertFalse(OverlayHudService.shouldHideForCarEvent("xy.android.mcu.backcar.out"));
        assertFalse(OverlayHudService.shouldHideForCarEvent("com.autochips.avin.quit"));
        assertFalse(OverlayHudService.shouldHideForCarEvent("xy.android.avin.leave"));
    }
}
