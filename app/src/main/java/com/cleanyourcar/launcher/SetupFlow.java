package com.cleanyourcar.launcher;

import com.cleanyourcar.launcher.config.NavigationReference;

import java.util.EnumSet;
import java.util.Set;

/**
 * Pure setup decisions. Every feature is optional and consented individually; refusing HOME
 * keeps the app usable as an ordinary foreground app. Consent is not verification.
 */
public final class SetupFlow {

    public enum Feature { HOME, CURRENT_LOCATION }

    public enum HomeResult { SET_AS_HOME, DECLINED, CANCELLED, UNAVAILABLE }

    public enum ReferenceChoice { CITY, CURRENT_LOCATION, NONE }

    public enum Entry { HOME, FOREGROUND_APP }

    private final EnumSet<Feature> consented;

    public SetupFlow(Set<Feature> previouslyConsented) {
        consented = previouslyConsented.isEmpty()
                ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(previouslyConsented);
    }

    public void onHomeResult(HomeResult result) {
        if (result == HomeResult.SET_AS_HOME) {
            consented.add(Feature.HOME);
        } else {
            consented.remove(Feature.HOME);
        }
    }

    public void onLocationPermissionResult(boolean granted) {
        if (granted) {
            consented.add(Feature.CURRENT_LOCATION);
        } else {
            consented.remove(Feature.CURRENT_LOCATION);
        }
    }

    public boolean isConsented(Feature feature) {
        return consented.contains(feature);
    }

    /** Imported reference mode never transfers the user's local location consent. */
    public void onImportedReferenceMode(NavigationReference.Mode mode) {
        if (mode == NavigationReference.Mode.CURRENT_LOCATION) {
            consented.remove(Feature.CURRENT_LOCATION);
        }
    }

    public Set<Feature> consentedFeatures() {
        return consented.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(consented);
    }

    public Entry entry() {
        return consented.contains(Feature.HOME) ? Entry.HOME : Entry.FOREGROUND_APP;
    }

    /** Every step is optional, so setup can always be finished. */
    public boolean canFinish() {
        return true;
    }

    /** Location permission is requested only when current location is chosen as reference. */
    public static boolean requiresLocationPermission(ReferenceChoice choice) {
        return choice == ReferenceChoice.CURRENT_LOCATION;
    }

    /** Opens setup until the user has seen it or explicitly accepted a matching profile. */
    public static boolean shouldAutoOpen(boolean setupSeen, boolean legacyXyAutoUser,
            boolean hasAcceptedProfile) {
        return !setupSeen && !hasAcceptedProfile;
    }
}
