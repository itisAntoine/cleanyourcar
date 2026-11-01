package com.cleanyourcar.launcher;

import com.cleanyourcar.launcher.compat.CapabilityRegistry;
import com.cleanyourcar.launcher.compat.CapabilityRegistry.CapabilityStatus;
import com.cleanyourcar.launcher.compat.EnvironmentFacts;
import com.cleanyourcar.launcher.integration.HeadUnitAdapter.ReverseSupport;
import com.cleanyourcar.launcher.integration.MediaState.ReverseState;

/**
 * Fail-closed HUD session policy. A session starts only on an explicit user request when the
 * locally verified overlay (HUD) capability is READY, the adapter's reverse state is verified
 * and currently known INACTIVE, and the map layout was verified. Reverse, unknown reverse,
 * integration loss, permission revocation, and environment changes remove every overlay window
 * and overlay dialog; nothing is shown again until the user asks.
 *
 * <p>This is a session policy, not a safety certification; it does not measure removal latency.
 */
public final class HudSessionPolicy {

    public enum Action {
        /** Remove all overlay windows and dismiss overlay dialogs. */
        HIDE_ALL,
        SHOW_EXPANDED,
        SHOW_COLLAPSED,
        /** The request is refused; any overlay is removed. */
        DENY
    }

    public enum Reason {
        NONE,
        /** Generic AAOS mode never enables the HUD in this release. */
        AAOS_PLATFORM,
        /** The adapter has no verified reverse initialization/resynchronization. */
        REVERSE_NOT_VERIFIED,
        /** The HUD capability lacks local verification, is stale, or is unavailable. */
        CAPABILITY_NOT_READY,
        OVERLAY_PERMISSION_MISSING,
        REVERSE_UNKNOWN,
        REVERSE_ACTIVE,
        MAP_LAYOUT_NOT_VERIFIED,
        /** A collapse/expand request arrived without a granted session. */
        NOT_SHOWN,
        REVERSE_ENTERED,
        REVERSE_EXITED,
        INTEGRATION_LOST,
        PERMISSION_REVOKED,
        ENVIRONMENT_CHANGED,
        USER_HIDDEN
    }

    public static final class Decision {
        public final Action action;
        public final Reason reason;

        private Decision(Action action, Reason reason) {
            this.action = action;
            this.reason = reason;
        }

        public boolean isShow() {
            return action == Action.SHOW_EXPANDED || action == Action.SHOW_COLLAPSED;
        }

        @Override
        public String toString() {
            return action + "(" + reason + ")";
        }
    }

    /** Where decisions take effect; implemented by the overlay service. */
    public interface Surface {
        void removeOverlayWindows();

        void dismissOverlayDialogs();

        void showExpanded();

        void showCollapsed();

        /** Explains a denied request to the user; HIDE_ALL is never explained here. */
        void explainDenial(Reason reason);
    }

    private final boolean aaos;
    private final ReverseSupport reverseSupport;
    private boolean showing;
    private boolean collapsed;

    public HudSessionPolicy(String platform, ReverseSupport reverseSupport) {
        this.aaos = EnvironmentFacts.PLATFORM_AAOS.equals(platform);
        this.reverseSupport = reverseSupport == null ? ReverseSupport.NONE : reverseSupport;
    }

    /**
     * Explicit user request to show the HUD.
     *
     * @param hud status of {@link CapabilityRegistry.Capability#OVERLAY} (the HUD capability);
     *     any other capability is refused
     */
    public synchronized Decision requestShow(CapabilityStatus hud, ReverseState reverse,
            boolean mapLayoutVerified) {
        Reason refusal = prerequisiteRefusal(hud, mapLayoutVerified);
        if (refusal == Reason.NONE) {
            if (reverse == ReverseState.ACTIVE) {
                refusal = Reason.REVERSE_ACTIVE;
            } else if (reverse != ReverseState.INACTIVE) {
                refusal = Reason.REVERSE_UNKNOWN;
            }
        }
        if (refusal != Reason.NONE) {
            return close(Action.DENY, refusal);
        }
        showing = true;
        collapsed = false;
        return new Decision(Action.SHOW_EXPANDED, Reason.NONE);
    }

    /** User collapse/expand within a granted session; never starts a session. */
    public synchronized Decision requestCollapsed(boolean collapse) {
        if (!showing) {
            return close(Action.DENY, Reason.NOT_SHOWN);
        }
        collapsed = collapse;
        return new Decision(collapse ? Action.SHOW_COLLAPSED : Action.SHOW_EXPANDED, Reason.NONE);
    }

    public synchronized Decision hide() {
        return close(Action.HIDE_ALL, Reason.USER_HIDDEN);
    }

    /** Every reverse report closes the session; exiting reverse never resumes it. */
    public synchronized Decision onReverseState(ReverseState state) {
        if (state == ReverseState.ACTIVE) {
            return close(Action.HIDE_ALL, Reason.REVERSE_ENTERED);
        }
        if (state == ReverseState.INACTIVE) {
            return close(Action.HIDE_ALL, Reason.REVERSE_EXITED);
        }
        return close(Action.HIDE_ALL, Reason.REVERSE_UNKNOWN);
    }

    public synchronized Decision onIntegrationLost() {
        return close(Action.HIDE_ALL, Reason.INTEGRATION_LOST);
    }

    public synchronized Decision onPermissionRevoked() {
        return close(Action.HIDE_ALL, Reason.PERMISSION_REVOKED);
    }

    /** Firmware/environment, adapter revision, or safety-profile change. */
    public synchronized Decision onEnvironmentChanged() {
        return close(Action.HIDE_ALL, Reason.ENVIRONMENT_CHANGED);
    }

    public synchronized boolean isShowing() {
        return showing;
    }

    public synchronized boolean isCollapsed() {
        return showing && collapsed;
    }

    /** Applies a decision; every non-show decision removes windows and overlay dialogs. */
    public static void apply(Decision decision, Surface surface) {
        if (decision.action == Action.SHOW_EXPANDED) {
            surface.showExpanded();
            return;
        }
        if (decision.action == Action.SHOW_COLLAPSED) {
            surface.showCollapsed();
            return;
        }
        surface.removeOverlayWindows();
        surface.dismissOverlayDialogs();
        if (decision.action == Action.DENY && decision.reason != Reason.NOT_SHOWN) {
            surface.explainDenial(decision.reason);
        }
    }

    /** I18n key explaining a denial or closure reason, or -1 when nothing is shown. */
    public static int messageKey(Reason reason) {
        if (reason == null) {
            return -1;
        }
        switch (reason) {
            case OVERLAY_PERMISSION_MISSING:
            case PERMISSION_REVOKED:
                return I18n.KEY_HUD_NO_PERMISSION;
            case REVERSE_ACTIVE:
            case REVERSE_UNKNOWN:
                return I18n.KEY_HUD_REVERSE;
            case REVERSE_NOT_VERIFIED:
            case CAPABILITY_NOT_READY:
            case MAP_LAYOUT_NOT_VERIFIED:
                return I18n.KEY_HUD_PAUSED;
            default:
                return -1;
        }
    }

    /**
     * Whether a denial is explained to the user. Unverified prerequisites are a one-time upgrade
     * notice for users who had the HUD before (legacy XYAuto installs); other users were never
     * offered a HUD. Permission and reverse reasons are always explained. AAOS stays silent.
     */
    public static boolean shouldExplain(Reason reason, boolean legacyHudUser,
            boolean pauseAlreadyExplained) {
        if (messageKey(reason) < 0) {
            return false;
        }
        if (messageKey(reason) == I18n.KEY_HUD_PAUSED) {
            return legacyHudUser && !pauseAlreadyExplained;
        }
        return true;
    }

    private Reason prerequisiteRefusal(CapabilityStatus hud, boolean mapLayoutVerified) {
        if (aaos) {
            return Reason.AAOS_PLATFORM;
        }
        if (reverseSupport != ReverseSupport.VERIFIED) {
            return Reason.REVERSE_NOT_VERIFIED;
        }
        if (hud == null || hud.capability != CapabilityRegistry.Capability.OVERLAY) {
            return Reason.CAPABILITY_NOT_READY;
        }
        if (hud.blockers.contains(CapabilityRegistry.KEY_PERMISSION_OVERLAY)) {
            return Reason.OVERLAY_PERMISSION_MISSING;
        }
        if (!hud.isReady()) {
            return Reason.CAPABILITY_NOT_READY;
        }
        if (!mapLayoutVerified) {
            return Reason.MAP_LAYOUT_NOT_VERIFIED;
        }
        return Reason.NONE;
    }

    private Decision close(Action action, Reason reason) {
        showing = false;
        collapsed = false;
        return new Decision(action, reason);
    }
}
