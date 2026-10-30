package com.cleanyourcar.launcher.compat;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, validated device profile (schema version 1). Data only; never executable. */
public final class DeviceProfile {

    public static final int SCHEMA_VERSION = 1;

    public static final Set<String> PLATFORMS = setOf("aftermarket", "aaos");
    public static final Set<String> ADAPTER_IDS = setOf("generic", "xyauto-yt3518c");
    public static final Set<String> STRATEGY_IDS = setOf("launch-package", "launch-component",
            "google-navigation", "geo", "xyauto-equalizer");
    public static final Set<String> NAVIGATION_STRATEGY_IDS = setOf("google-navigation", "geo");
    public static final Set<String> XYAUTO_STRATEGY_IDS = setOf("xyauto-equalizer");
    public static final Set<String> ROLE_KEYS = setOf("navigation", "bluetooth-music",
            "local-music", "radio", "dashcam", "equalizer", "settings");
    public static final Set<String> CAPABILITY_IDS = setOf("app-launch", "navigation", "home",
            "media-transport", "media-metadata", "source-selection", "reverse-detection",
            "overlay", "brightness", "ble-receive", "lan-receive");
    public static final Set<String> CLAIM_VALUES = setOf("unsupported", "requires-local-check");
    public static final Set<String> BUILD_PROPERTY_KEYS = setOf("board", "product",
            "manufacturer", "display", "fingerprint");
    public static final Set<String> EVIDENCE_CLASSIFICATIONS = setOf("reported",
            "device-tested", "maintainer-reviewed");

    public static final String ROLE_NAVIGATION = "navigation";
    public static final String ROLE_EQUALIZER = "equalizer";
    public static final String ADAPTER_XYAUTO = "xyauto-yt3518c";
    public static final String STRATEGY_LAUNCH_COMPONENT = "launch-component";

    public final int schemaVersion;
    public final String profileId;
    public final int revision;
    public final int minAppVersionCode;
    public final String platform;
    public final String adapterId;
    public final Match match;
    public final Map<String, RoleDefault> roleDefaults;
    public final Map<String, String> featureClaims;
    public final List<String> protectedPackages;
    public final Evidence evidence;

    DeviceProfile(int schemaVersion, String profileId, int revision, int minAppVersionCode,
            String platform, String adapterId, Match match, Map<String, RoleDefault> roleDefaults,
            Map<String, String> featureClaims, List<String> protectedPackages, Evidence evidence) {
        this.schemaVersion = schemaVersion;
        this.profileId = profileId;
        this.revision = revision;
        this.minAppVersionCode = minAppVersionCode;
        this.platform = platform;
        this.adapterId = adapterId;
        this.match = match;
        this.roleDefaults = Collections.unmodifiableMap(new LinkedHashMap<>(roleDefaults));
        this.featureClaims = Collections.unmodifiableMap(new LinkedHashMap<>(featureClaims));
        this.protectedPackages = Collections.unmodifiableList(Arrays.asList(
                protectedPackages.toArray(new String[0])));
        this.evidence = evidence;
    }

    /** Environment facts a profile applies to. SDK bounds are inclusive. */
    public static final class Match {
        public final int sdkMin;
        public final int sdkMax;
        public final Map<String, String> buildProperties;

        Match(int sdkMin, int sdkMax, Map<String, String> buildProperties) {
            this.sdkMin = sdkMin;
            this.sdkMax = sdkMax;
            this.buildProperties = Collections.unmodifiableMap(new LinkedHashMap<>(buildProperties));
        }
    }

    /** Default app for a role, launched only through a built-in strategy. */
    public static final class RoleDefault {
        public final String packageName;
        /** Flattened component ("package/class"), or null when not specified. */
        public final String componentName;
        public final String strategyId;

        RoleDefault(String packageName, String componentName, String strategyId) {
            this.packageName = packageName;
            this.componentName = componentName;
            this.strategyId = strategyId;
        }
    }

    public static final class Evidence {
        public final String classification;
        public final String firmware;
        public final String notes;
        public final List<String> references;

        Evidence(String classification, String firmware, String notes, List<String> references) {
            this.classification = classification;
            this.firmware = firmware;
            this.notes = notes;
            this.references = Collections.unmodifiableList(Arrays.asList(
                    references.toArray(new String[0])));
        }
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }
}
