package com.cleanyourcar.launcher;

import com.cleanyourcar.launcher.HudSessionPolicy.Action;
import com.cleanyourcar.launcher.HudSessionPolicy.Decision;
import com.cleanyourcar.launcher.HudSessionPolicy.Reason;
import com.cleanyourcar.launcher.compat.CapabilityRegistry;
import com.cleanyourcar.launcher.compat.CapabilityRegistry.Availability;
import com.cleanyourcar.launcher.compat.CapabilityRegistry.Capability;
import com.cleanyourcar.launcher.compat.CapabilityRegistry.CapabilityStatus;
import com.cleanyourcar.launcher.compat.CapabilityRegistry.Evidence;
import com.cleanyourcar.launcher.compat.CapabilityRegistry.RuntimeConditions;
import com.cleanyourcar.launcher.compat.DeviceProfile;
import com.cleanyourcar.launcher.compat.EnvironmentFacts;
import com.cleanyourcar.launcher.compat.ProfileCodec;
import com.cleanyourcar.launcher.integration.HeadUnitAdapter.ReverseSupport;
import com.cleanyourcar.launcher.integration.MediaState.ReverseState;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HudSessionPolicyTest {

    private static Map<String, String> deps() {
        Map<String, String> deps = new LinkedHashMap<>();
        deps.put(CapabilityRegistry.KEY_ENVIRONMENT, "env-1");
        deps.put(CapabilityRegistry.KEY_SAFETY_PROFILE, "safety-1");
        deps.put(CapabilityRegistry.KEY_PERMISSION_OVERLAY, "granted");
        return deps;
    }

    /** HUD capability locally verified on the current environment with no blocker. */
    static CapabilityStatus readyOverlay() {
        return CapabilityRegistry.evaluate(Capability.OVERLAY, Evidence.verified(deps()),
                new RuntimeConditions(Availability.AVAILABLE, deps(),
                        Collections.<String>emptyList(), false, false));
    }

    /** A hypothetical adapter with verified reverse initialization on an aftermarket unit. */
    static HudSessionPolicy verifiedPolicy() {
        return new HudSessionPolicy(EnvironmentFacts.PLATFORM_AFTERMARKET,
                ReverseSupport.VERIFIED);
    }

    /** Records which surface operations a decision performed. */
    static final class RecordingSurface implements HudSessionPolicy.Surface {
        final List<String> calls = new ArrayList<>();

        @Override
        public void removeOverlayWindows() {
            calls.add("remove-windows");
        }

        @Override
        public void dismissOverlayDialogs() {
            calls.add("dismiss-dialogs");
        }

        @Override
        public void showExpanded() {
            calls.add("show-expanded");
        }

        @Override
        public void showCollapsed() {
            calls.add("show-collapsed");
        }

        @Override
        public void explainDenial(Reason reason) {
            calls.add("explain:" + reason);
        }
    }

    @Test
    public void verifiedPrerequisitesShowExpanded() {
        HudSessionPolicy policy = verifiedPolicy();
        Decision shown = policy.requestShow(readyOverlay(), ReverseState.INACTIVE, true);
        assertEquals(Action.SHOW_EXPANDED, shown.action);
        assertTrue(policy.isShowing());

        assertEquals(Action.SHOW_COLLAPSED, policy.requestCollapsed(true).action);
        assertEquals(Action.SHOW_EXPANDED, policy.requestCollapsed(false).action);
    }

    @Test
    public void unknownReverseDeniesShow() {
        HudSessionPolicy policy = verifiedPolicy();
        Decision unknownReverseRequest =
                policy.requestShow(readyOverlay(), ReverseState.UNKNOWN, true);
        assertEquals(Action.DENY, unknownReverseRequest.action);
        assertEquals(Reason.REVERSE_UNKNOWN, unknownReverseRequest.reason);
        assertFalse(policy.isShowing());

        Decision duringReverse = policy.requestShow(readyOverlay(), ReverseState.ACTIVE, true);
        assertEquals(Action.DENY, duringReverse.action);
        assertEquals(Reason.REVERSE_ACTIVE, duringReverse.reason);

        Decision nullReverse = policy.requestShow(readyOverlay(), null, true);
        assertEquals(Action.DENY, nullReverse.action);

        // A shown HUD whose reverse state becomes unknown is removed, not kept.
        policy.requestShow(readyOverlay(), ReverseState.INACTIVE, true);
        Decision lostState = policy.onReverseState(ReverseState.UNKNOWN);
        assertEquals(Action.HIDE_ALL, lostState.action);
        assertFalse(policy.isShowing());
    }

    @Test
    public void unverifiedMapLayoutDeniesShow() {
        HudSessionPolicy policy = verifiedPolicy();
        Decision decision = policy.requestShow(readyOverlay(), ReverseState.INACTIVE, false);
        assertEquals(Action.DENY, decision.action);
        assertEquals(Reason.MAP_LAYOUT_NOT_VERIFIED, decision.reason);
    }

    @Test
    public void reverseEnterDismissesWindowsAndDialog() {
        HudSessionPolicy policy = verifiedPolicy();
        policy.requestShow(readyOverlay(), ReverseState.INACTIVE, true);

        Decision enter = policy.onReverseState(ReverseState.ACTIVE);
        assertEquals(Action.HIDE_ALL, enter.action);
        assertEquals(Reason.REVERSE_ENTERED, enter.reason);
        assertFalse(policy.isShowing());

        RecordingSurface surface = new RecordingSurface();
        HudSessionPolicy.apply(enter, surface);
        assertTrue(surface.calls.contains("remove-windows"));
        assertTrue(surface.calls.contains("dismiss-dialogs"));
        assertFalse(surface.calls.contains("show-expanded"));
        assertFalse(surface.calls.contains("show-collapsed"));

        // Even with no HUD visible, reverse still removes any overlay dialog.
        HudSessionPolicy idle = verifiedPolicy();
        RecordingSurface idleSurface = new RecordingSurface();
        HudSessionPolicy.apply(idle.onReverseState(ReverseState.ACTIVE), idleSurface);
        assertTrue(idleSurface.calls.contains("dismiss-dialogs"));

        // A collapse/expand tap while reversing cannot bring windows back.
        assertEquals(Action.DENY, policy.requestCollapsed(false).action);
    }

    @Test
    public void reverseExitDoesNotAutoResume() {
        HudSessionPolicy policy = verifiedPolicy();
        policy.requestShow(readyOverlay(), ReverseState.INACTIVE, true);
        policy.requestCollapsed(true);
        policy.onReverseState(ReverseState.ACTIVE);

        Decision afterReverseExit = policy.onReverseState(ReverseState.INACTIVE);
        assertEquals(Action.HIDE_ALL, afterReverseExit.action);
        assertEquals(Reason.REVERSE_EXITED, afterReverseExit.reason);
        assertFalse(policy.isShowing());

        RecordingSurface surface = new RecordingSurface();
        HudSessionPolicy.apply(afterReverseExit, surface);
        assertFalse(surface.calls.contains("show-expanded"));
        assertFalse(surface.calls.contains("show-collapsed"));

        // Only a new explicit user request resumes the HUD.
        assertEquals(Action.SHOW_EXPANDED,
                policy.requestShow(readyOverlay(), ReverseState.INACTIVE, true).action);
    }

    @Test
    public void revokedPermissionOrFirmwareChangeHidesAll() {
        HudSessionPolicy policy = verifiedPolicy();
        policy.requestShow(readyOverlay(), ReverseState.INACTIVE, true);
        Decision revoked = policy.onPermissionRevoked();
        assertEquals(Action.HIDE_ALL, revoked.action);
        assertEquals(Reason.PERMISSION_REVOKED, revoked.reason);
        assertFalse(policy.isShowing());

        policy.requestShow(readyOverlay(), ReverseState.INACTIVE, true);
        Decision firmware = policy.onEnvironmentChanged();
        assertEquals(Action.HIDE_ALL, firmware.action);
        assertEquals(Reason.ENVIRONMENT_CHANGED, firmware.reason);
        assertFalse(policy.isShowing());

        policy.requestShow(readyOverlay(), ReverseState.INACTIVE, true);
        Decision lost = policy.onIntegrationLost();
        assertEquals(Action.HIDE_ALL, lost.action);
        assertEquals(Reason.INTEGRATION_LOST, lost.reason);

        RecordingSurface surface = new RecordingSurface();
        HudSessionPolicy.apply(firmware, surface);
        assertTrue(surface.calls.contains("remove-windows"));
        assertTrue(surface.calls.contains("dismiss-dialogs"));

        // Evidence recorded before the firmware update is stale afterwards: request denied.
        Map<String, String> updated = deps();
        updated.put(CapabilityRegistry.KEY_ENVIRONMENT, "env-2");
        CapabilityStatus stale = CapabilityRegistry.evaluate(Capability.OVERLAY,
                Evidence.verified(deps()), new RuntimeConditions(Availability.AVAILABLE,
                        updated, Collections.<String>emptyList(), false, false));
        assertEquals(Action.DENY,
                policy.requestShow(stale, ReverseState.INACTIVE, true).action);

        // A revoked overlay permission blocks the next request with an explicit reason.
        Map<String, String> revokedDeps = deps();
        revokedDeps.put(CapabilityRegistry.KEY_PERMISSION_OVERLAY, "denied");
        CapabilityStatus blocked = CapabilityRegistry.evaluate(Capability.OVERLAY,
                Evidence.verified(deps()), new RuntimeConditions(Availability.AVAILABLE,
                        revokedDeps,
                        Collections.singletonList(CapabilityRegistry.KEY_PERMISSION_OVERLAY),
                        false, false));
        Decision denied = policy.requestShow(blocked, ReverseState.INACTIVE, true);
        assertEquals(Action.DENY, denied.action);
        assertEquals(Reason.OVERLAY_PERMISSION_MISSING, denied.reason);
    }

    @Test
    public void genericAaosNeverEnablesHud() {
        HudSessionPolicy aaos = new HudSessionPolicy(EnvironmentFacts.PLATFORM_AAOS,
                ReverseSupport.VERIFIED);
        Decision aaosDecision = aaos.requestShow(readyOverlay(), ReverseState.INACTIVE, true);
        assertEquals(Action.DENY, aaosDecision.action);
        assertEquals(Reason.AAOS_PLATFORM, aaosDecision.reason);

        HudSessionPolicy generic = new HudSessionPolicy(EnvironmentFacts.PLATFORM_AFTERMARKET,
                ReverseSupport.NONE);
        Decision genericDecision =
                generic.requestShow(readyOverlay(), ReverseState.INACTIVE, true);
        assertEquals(Action.DENY, genericDecision.action);
        assertEquals(Reason.REVERSE_NOT_VERIFIED, genericDecision.reason);

        HudSessionPolicy genericAaos = new HudSessionPolicy(EnvironmentFacts.PLATFORM_AAOS,
                ReverseSupport.NONE);
        assertEquals(Action.DENY,
                genericAaos.requestShow(readyOverlay(), ReverseState.INACTIVE, true).action);
        assertFalse(genericAaos.isShowing());
    }

    @Test
    public void xyAutoEventsWithoutVerifiedInitializationNeverEnableHud() {
        HudSessionPolicy xyauto = new HudSessionPolicy(EnvironmentFacts.PLATFORM_AFTERMARKET,
                ReverseSupport.UNVERIFIED_EVENTS);
        Decision decision = xyauto.requestShow(readyOverlay(), ReverseState.INACTIVE, true);
        assertEquals(Action.DENY, decision.action);
        assertEquals(Reason.REVERSE_NOT_VERIFIED, decision.reason);

        // Its reverse events still remove overlay dialogs.
        RecordingSurface surface = new RecordingSurface();
        HudSessionPolicy.apply(xyauto.onReverseState(ReverseState.ACTIVE), surface);
        assertTrue(surface.calls.contains("dismiss-dialogs"));
    }

    @Test
    public void profileClaimsAloneNeverEnableHud() throws Exception {
        DeviceProfile profile = ProfileCodec.parse(readFixture("valid-xyauto.json"), 1);
        Availability claimed = CapabilityRegistry.claimedAvailability(profile,
                Capability.OVERLAY);
        // Even with the overlay permission granted, a claim without local evidence is not READY.
        CapabilityStatus claimOnly = CapabilityRegistry.evaluate(Capability.OVERLAY,
                Evidence.untested(), new RuntimeConditions(claimed, deps(),
                        Collections.<String>emptyList(), false, false));
        HudSessionPolicy policy = verifiedPolicy();
        Decision decision = policy.requestShow(claimOnly, ReverseState.INACTIVE, true);
        assertEquals(Action.DENY, decision.action);
        assertEquals(Reason.CAPABILITY_NOT_READY, decision.reason);

        // A claim of availability without evidence is still not enough.
        CapabilityStatus availableUntested = CapabilityRegistry.evaluate(Capability.OVERLAY,
                Evidence.untested(), new RuntimeConditions(Availability.AVAILABLE, deps(),
                        Collections.<String>emptyList(), false, false));
        assertEquals(Action.DENY,
                policy.requestShow(availableUntested, ReverseState.INACTIVE, true).action);

        // Readiness of a different capability cannot stand in for the HUD capability.
        CapabilityStatus reverseReady = CapabilityRegistry.evaluate(
                Capability.REVERSE_DETECTION, Evidence.verified(allDeps()),
                new RuntimeConditions(Availability.AVAILABLE, allDeps(),
                        Collections.<String>emptyList(), false, false));
        assertTrue(reverseReady.isReady());
        assertEquals(Action.DENY,
                policy.requestShow(reverseReady, ReverseState.INACTIVE, true).action);
        assertFalse(policy.isShowing());
    }

    @Test
    public void deniedRequestWhileShowingRemovesWindows() {
        HudSessionPolicy policy = verifiedPolicy();
        policy.requestShow(readyOverlay(), ReverseState.INACTIVE, true);
        Decision denied = policy.requestShow(readyOverlay(), ReverseState.UNKNOWN, true);
        assertEquals(Action.DENY, denied.action);
        assertFalse(policy.isShowing());

        RecordingSurface surface = new RecordingSurface();
        HudSessionPolicy.apply(denied, surface);
        assertTrue(surface.calls.contains("remove-windows"));
        assertTrue(surface.calls.contains("dismiss-dialogs"));
        assertTrue(surface.calls.contains("explain:" + Reason.REVERSE_UNKNOWN));
    }

    private static Map<String, String> allDeps() {
        Map<String, String> deps = deps();
        deps.put(CapabilityRegistry.KEY_ADAPTER_REVISION, "xyauto-yt3518c@1");
        return deps;
    }

    private static String readFixture(String name) throws IOException {
        InputStream in = HudSessionPolicyTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull("missing fixture resource " + name, in);
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void denialsAreExplainedWithI18nMessagesAndLegacyUsersSeeThePause() {
        assertEquals(I18n.KEY_HUD_PAUSED,
                HudSessionPolicy.messageKey(Reason.REVERSE_NOT_VERIFIED));
        assertEquals(I18n.KEY_HUD_NO_PERMISSION,
                HudSessionPolicy.messageKey(Reason.OVERLAY_PERMISSION_MISSING));
        assertEquals(I18n.KEY_HUD_REVERSE, HudSessionPolicy.messageKey(Reason.REVERSE_UNKNOWN));
        assertEquals(-1, HudSessionPolicy.messageKey(Reason.AAOS_PLATFORM));

        assertTrue(HudSessionPolicy.shouldExplain(Reason.REVERSE_NOT_VERIFIED, true, false));
        assertFalse(HudSessionPolicy.shouldExplain(Reason.REVERSE_NOT_VERIFIED, true, true));
        assertFalse(HudSessionPolicy.shouldExplain(Reason.REVERSE_NOT_VERIFIED, false, false));
        assertTrue(HudSessionPolicy.shouldExplain(Reason.REVERSE_ACTIVE, false, true));
        assertFalse(HudSessionPolicy.shouldExplain(Reason.AAOS_PLATFORM, true, false));

        for (int lang = I18n.LANG_VI; lang <= I18n.LANG_ES; lang++) {
            for (int key = I18n.KEY_HUD_PAUSED; key <= I18n.KEY_HUD_REVERSE; key++) {
                assertFalse(I18n.t(lang, key).isEmpty());
            }
        }
        assertFalse(I18n.t(I18n.LANG_EN, I18n.KEY_HUD_PAUSED)
                .equals(I18n.t(I18n.LANG_VI, I18n.KEY_HUD_PAUSED)));
    }
}
