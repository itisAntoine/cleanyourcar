package com.cleanyourcar.launcher.compat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure capability policy. Profile claims can only lower or leave availability unknown; only
 * local evidence whose dependency keys still match the current values can be VERIFIED.
 * Runtime conditions are re-evaluated on resume and before executing a capability.
 */
public final class CapabilityRegistry {

    /** Digest from {@link EnvironmentFacts#environmentKey()}; changes on firmware updates. */
    public static final String KEY_ENVIRONMENT = "environment";
    public static final String KEY_ADAPTER_REVISION = "adapter-revision";
    public static final String KEY_SAFETY_PROFILE = "safety-profile-hash";
    /** Identity and version of the navigation role app. */
    public static final String KEY_NAVIGATION_APP = "navigation-app";
    /** Identity and version of the media role apps. */
    public static final String KEY_MEDIA_APP = "media-app";
    public static final String KEY_PERMISSIONS = "permission-state";

    public static final Set<String> DEPENDENCY_KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(KEY_ENVIRONMENT, KEY_ADAPTER_REVISION,
                    KEY_SAFETY_PROFILE, KEY_NAVIGATION_APP, KEY_MEDIA_APP, KEY_PERMISSIONS)));

    public static final String BLOCKER_CONSENT_REQUIRED = "consent-required";

    private static final String CLAIM_UNSUPPORTED = "unsupported";

    private CapabilityRegistry() {
    }

    public enum Availability { AVAILABLE, UNSUPPORTED, UNKNOWN }

    public enum Validation { UNTESTED, VERIFIED, FAILED }

    /** Capabilities mapped 1:1 to {@link DeviceProfile#CAPABILITY_IDS} with their dependencies. */
    public enum Capability {
        APP_LAUNCH("app-launch", KEY_ENVIRONMENT),
        NAVIGATION("navigation", KEY_ENVIRONMENT, KEY_NAVIGATION_APP),
        HOME("home", KEY_ENVIRONMENT),
        MEDIA_TRANSPORT("media-transport", KEY_ENVIRONMENT, KEY_ADAPTER_REVISION, KEY_MEDIA_APP),
        MEDIA_METADATA("media-metadata", KEY_ENVIRONMENT, KEY_ADAPTER_REVISION, KEY_MEDIA_APP,
                KEY_PERMISSIONS),
        SOURCE_SELECTION("source-selection", KEY_ENVIRONMENT, KEY_ADAPTER_REVISION,
                KEY_MEDIA_APP),
        REVERSE_DETECTION("reverse-detection", KEY_ENVIRONMENT, KEY_ADAPTER_REVISION,
                KEY_SAFETY_PROFILE),
        OVERLAY("overlay", KEY_ENVIRONMENT, KEY_SAFETY_PROFILE, KEY_PERMISSIONS),
        BRIGHTNESS("brightness", KEY_ENVIRONMENT, KEY_PERMISSIONS),
        BLE_RECEIVE("ble-receive", KEY_ENVIRONMENT, KEY_PERMISSIONS),
        LAN_RECEIVE("lan-receive", KEY_ENVIRONMENT);

        private final String id;
        private final Set<String> dependencyKeys;

        Capability(String id, String... dependencyKeys) {
            this.id = id;
            this.dependencyKeys = Collections.unmodifiableSet(
                    new LinkedHashSet<>(Arrays.asList(dependencyKeys)));
        }

        public String id() {
            return id;
        }

        public Set<String> dependencyKeys() {
            return dependencyKeys;
        }

        /** Returns the capability for a profile capability ID, or null when unknown. */
        public static Capability fromId(String id) {
            for (Capability capability : values()) {
                if (capability.id.equals(id)) {
                    return capability;
                }
            }
            return null;
        }
    }

    /** Local validation result plus the dependency values observed when it was recorded. */
    public static final class Evidence {
        public final Validation validation;
        public final Map<String, String> dependencyValues;

        private Evidence(Validation validation, Map<String, String> dependencyValues) {
            this.validation = validation;
            this.dependencyValues = immutableKeys(dependencyValues);
        }

        public static Evidence untested() {
            return new Evidence(Validation.UNTESTED, Collections.<String, String>emptyMap());
        }

        public static Evidence verified(Map<String, String> dependencyValues) {
            return new Evidence(Validation.VERIFIED, dependencyValues);
        }

        public static Evidence failed(Map<String, String> dependencyValues) {
            return new Evidence(Validation.FAILED, dependencyValues);
        }
    }

    /** Conditions observed now; rechecked on resume and before executing a capability. */
    public static final class RuntimeConditions {
        public final Availability availability;
        public final Map<String, String> currentDependencyValues;
        /** Permission, restriction, or health blockers. */
        public final List<String> blockers;
        public final boolean consentRequired;
        public final boolean consentGranted;

        public RuntimeConditions(Availability availability,
                Map<String, String> currentDependencyValues, List<String> blockers,
                boolean consentRequired, boolean consentGranted) {
            if (availability == null) {
                throw new IllegalArgumentException("availability is required");
            }
            this.availability = availability;
            this.currentDependencyValues = immutableKeys(currentDependencyValues);
            this.blockers = Collections.unmodifiableList(new ArrayList<>(blockers));
            this.consentRequired = consentRequired;
            this.consentGranted = consentGranted;
        }
    }

    /** Names of dependency keys whose values changed. */
    public static final class ChangeSet {
        public final Set<String> changedKeys;

        private ChangeSet(Set<String> changedKeys) {
            this.changedKeys = Collections.unmodifiableSet(changedKeys);
        }

        public static ChangeSet of(String... keys) {
            Set<String> changed = new TreeSet<>();
            for (String key : keys) {
                requireKnownKey(key);
                changed.add(key);
            }
            return new ChangeSet(changed);
        }
    }

    public static final class CapabilityStatus {
        public final Capability capability;
        public final Availability availability;
        public final Validation validation;
        public final List<String> blockers;
        /** Dependency keys whose recorded evidence no longer matches current values. */
        public final List<String> staleDependencies;

        CapabilityStatus(Capability capability, Availability availability, Validation validation,
                List<String> blockers, List<String> staleDependencies) {
            this.capability = capability;
            this.availability = availability;
            this.validation = validation;
            this.blockers = Collections.unmodifiableList(new ArrayList<>(blockers));
            this.staleDependencies = Collections.unmodifiableList(
                    new ArrayList<>(staleDependencies));
        }

        public boolean isReady() {
            return availability == Availability.AVAILABLE && validation == Validation.VERIFIED
                    && blockers.isEmpty();
        }
    }

    /** Availability implied by a profile claim; never AVAILABLE and never evidence. */
    public static Availability claimedAvailability(DeviceProfile profile, Capability capability) {
        return CLAIM_UNSUPPORTED.equals(profile.featureClaims.get(capability.id()))
                ? Availability.UNSUPPORTED : Availability.UNKNOWN;
    }

    public static CapabilityStatus evaluate(Capability capability, Evidence evidence,
            RuntimeConditions conditions) {
        List<String> stale = new ArrayList<>();
        Validation validation = evidence.validation;
        if (validation != Validation.UNTESTED) {
            for (String key : capability.dependencyKeys()) {
                String recorded = evidence.dependencyValues.get(key);
                String current = conditions.currentDependencyValues.get(key);
                if (recorded == null || !recorded.equals(current)) {
                    stale.add(key);
                }
            }
            if (!stale.isEmpty()) {
                validation = Validation.UNTESTED;
            }
        }
        List<String> blockers = new ArrayList<>(conditions.blockers);
        if (conditions.consentRequired && !conditions.consentGranted) {
            blockers.add(BLOCKER_CONSENT_REQUIRED);
        }
        return new CapabilityStatus(capability, conditions.availability, validation, blockers,
                stale);
    }

    /** Returns IDs of capabilities whose evidence depends on any changed key, sorted. */
    public static Set<String> invalidate(ChangeSet changes) {
        Set<String> affected = new TreeSet<>();
        for (Capability capability : Capability.values()) {
            for (String key : changes.changedKeys) {
                if (capability.dependencyKeys().contains(key)) {
                    affected.add(capability.id());
                    break;
                }
            }
        }
        return Collections.unmodifiableSet(affected);
    }

    private static Map<String, String> immutableKeys(Map<String, String> values) {
        Map<String, String> copy = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            requireKnownKey(entry.getKey());
            if (entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void requireKnownKey(String key) {
        if (!DEPENDENCY_KEYS.contains(key)) {
            throw new IllegalArgumentException("unknown dependency key: " + key);
        }
    }
}
