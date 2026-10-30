package com.cleanyourcar.launcher;

import com.cleanyourcar.launcher.compat.EnvironmentFacts;
import com.cleanyourcar.launcher.integration.HeadUnitAdapter.ReverseSupport;
import com.cleanyourcar.launcher.integration.MediaState.ReverseState;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OverlayStateTransitionTest {

    private static HudSessionPolicy verifiedPolicy() {
        return new HudSessionPolicy(EnvironmentFacts.PLATFORM_AFTERMARKET,
                ReverseSupport.VERIFIED);
    }

    @Test
    public void overlayController_collapsesExpandsAndYieldsToReverseWithoutAutoResume() {
        HudSessionPolicy policy = verifiedPolicy();
        OverlayHudService.StateMachine sm = new OverlayHudService.StateMachine();
        assertFalse(sm.isActive());

        sm.apply(policy.requestShow(HudSessionPolicyTest.readyOverlay(),
                ReverseState.INACTIVE, true));
        assertTrue(sm.isActive());
        assertFalse(sm.isCollapsed());
        assertFalse(sm.shouldShowLeftDock());
        assertTrue(sm.shouldShowExpandedRightCard());
        assertFalse(sm.shouldShowCollapsedPill());

        sm.apply(policy.requestCollapsed(true));
        assertTrue(sm.isActive());
        assertTrue(sm.isCollapsed());
        assertFalse(sm.shouldShowExpandedRightCard());
        assertTrue(sm.shouldShowCollapsedPill());

        // Enter reverse gear -> every overlay window goes away.
        sm.apply(policy.onReverseState(ReverseState.ACTIVE));
        assertFalse(sm.isActive());
        assertFalse(sm.shouldShowLeftDock());
        assertFalse(sm.shouldShowExpandedRightCard());
        assertFalse(sm.shouldShowCollapsedPill());

        // Exit reverse gear -> stays hidden; the user must explicitly resume.
        sm.apply(policy.onReverseState(ReverseState.INACTIVE));
        assertFalse(sm.isActive());
        assertFalse(sm.shouldShowExpandedRightCard());
        assertFalse(sm.shouldShowCollapsedPill());

        sm.apply(policy.requestShow(HudSessionPolicyTest.readyOverlay(),
                ReverseState.INACTIVE, true));
        assertTrue(sm.shouldShowExpandedRightCard());

        // Every reverse/AV-in enter action hides; exit actions do not count as enter.
        assertTrue(OverlayHudService.shouldHideForCarEvent("xy.android.mcu.backcar.in"));
        assertTrue(OverlayHudService.shouldHideForCarEvent("xy.android.mcu.backcar.in2"));
        assertTrue(OverlayHudService.shouldHideForCarEvent("xy.android.force.back.in"));
        assertTrue(OverlayHudService.shouldHideForCarEvent(
                "android.intent.action.START_ARM1_BACKCAR"));
        assertTrue(OverlayHudService.shouldHideForCarEvent("xy.android.avin.come"));
        assertFalse(OverlayHudService.shouldHideForCarEvent("xy.android.mcu.backcar.out"));
        assertFalse(OverlayHudService.shouldHideForCarEvent("com.autochips.avin.quit"));
        assertFalse(OverlayHudService.shouldHideForCarEvent("xy.android.avin.leave"));
    }

    @Test
    public void overlayController_unknownReverseAndDenialKeepWindowsRemoved() {
        HudSessionPolicy policy = verifiedPolicy();
        OverlayHudService.StateMachine sm = new OverlayHudService.StateMachine();

        sm.apply(policy.requestShow(HudSessionPolicyTest.readyOverlay(),
                ReverseState.UNKNOWN, true));
        assertFalse(sm.isActive());

        sm.apply(policy.requestShow(HudSessionPolicyTest.readyOverlay(),
                ReverseState.INACTIVE, true));
        assertTrue(sm.isActive());
        sm.apply(policy.onReverseState(ReverseState.UNKNOWN));
        assertFalse(sm.shouldShowExpandedRightCard());
        assertFalse(sm.shouldShowCollapsedPill());

        // Collapse/expand taps cannot start a session that the policy did not grant.
        sm.apply(policy.requestCollapsed(false));
        assertFalse(sm.isActive());
    }

    @Test
    public void overlayService_hasNoSystemOverscanPath() {
        assertNoOverscanMethod(OverlayHudService.StateMachine.class);
        assertNoOverscanMethod(OverlayHudService.class);
        assertNoOverscanMethod(HudSessionPolicy.class);
    }

    private static void assertNoOverscanMethod(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            assertFalse(type.getSimpleName() + "." + method.getName(),
                    method.getName().toLowerCase(Locale.US).contains("overscan"));
        }
    }
}
