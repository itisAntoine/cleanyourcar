package com.cleanyourcar.launcher.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic matching of device profiles against observed environment facts.
 *
 * <p>A profile is a candidate when its platform and inclusive SDK range match and none of its
 * declared build properties contradicts a present fact. A candidate is exact when every declared
 * property is present and equal; otherwise it is partial and its absent keys are reported as
 * missing facts. A profile with a contradicting fact is never a candidate, so a shared
 * manufacturer alone never matches. Profiles that declare no build properties are generic
 * fallbacks: considered only when no profile with declared properties is a candidate, and then
 * reported as UNKNOWN with {@link MatchResult#fallback} set, never as EXACT.
 */
public final class ProfileMatcher {

    public enum Status { EXACT, PARTIAL, CONFLICT, UNKNOWN }

    private static final Comparator<DeviceProfile> BY_ID = new Comparator<DeviceProfile>() {
        @Override
        public int compare(DeviceProfile a, DeviceProfile b) {
            return a.profileId.compareTo(b.profileId);
        }
    };

    private ProfileMatcher() {
    }

    public static final class MatchResult {
        public final Status status;
        /** Candidates ordered by profile ID; a single profile when EXACT. */
        public final List<DeviceProfile> candidates;
        /** Declared property keys absent from the environment (PARTIAL only). */
        public final Set<String> missingFacts;
        /** True when candidates are only generic fallbacks; status is then UNKNOWN. */
        public final boolean fallback;

        MatchResult(Status status, List<DeviceProfile> candidates, Set<String> missingFacts,
                boolean fallback) {
            this.status = status;
            this.fallback = fallback;
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.missingFacts = Collections.unmodifiableSet(new TreeSet<>(missingFacts));
        }

        /** The selected profile when EXACT; otherwise null so callers must ask or fall back. */
        public DeviceProfile profile() {
            return status == Status.EXACT ? candidates.get(0) : null;
        }
    }

    public static MatchResult match(List<DeviceProfile> profiles, EnvironmentFacts facts) {
        List<DeviceProfile> sorted = new ArrayList<>(profiles);
        Collections.sort(sorted, BY_ID);

        List<DeviceProfile> exact = new ArrayList<>();
        List<DeviceProfile> partial = new ArrayList<>();
        List<DeviceProfile> fallbacks = new ArrayList<>();
        Set<String> missing = new TreeSet<>();
        for (DeviceProfile profile : sorted) {
            if (!profile.platform.equals(facts.platform)
                    || facts.sdk < profile.match.sdkMin || facts.sdk > profile.match.sdkMax) {
                continue;
            }
            if (profile.match.buildProperties.isEmpty()) {
                fallbacks.add(profile);
                continue;
            }
            Set<String> profileMissing = new TreeSet<>();
            boolean contradicted = false;
            for (Map.Entry<String, String> declared : profile.match.buildProperties.entrySet()) {
                String actual = facts.buildProperties.get(declared.getKey());
                if (actual == null) {
                    profileMissing.add(declared.getKey());
                } else if (!actual.equals(declared.getValue())) {
                    contradicted = true;
                    break;
                }
            }
            if (contradicted) {
                continue;
            }
            if (profileMissing.isEmpty()) {
                exact.add(profile);
            } else {
                partial.add(profile);
                missing.addAll(profileMissing);
            }
        }

        Set<String> none = Collections.emptySet();
        if (exact.size() == 1) {
            return new MatchResult(Status.EXACT, exact, none, false);
        }
        if (exact.size() > 1) {
            return new MatchResult(Status.CONFLICT, exact, none, false);
        }
        if (!partial.isEmpty()) {
            return new MatchResult(Status.PARTIAL, partial, missing, false);
        }
        return new MatchResult(Status.UNKNOWN, fallbacks, none, !fallbacks.isEmpty());
    }
}
