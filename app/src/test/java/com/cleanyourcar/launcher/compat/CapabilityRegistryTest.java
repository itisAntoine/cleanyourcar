package com.cleanyourcar.launcher.compat;

import com.cleanyourcar.launcher.compat.CapabilityRegistry.Availability;
import com.cleanyourcar.launcher.compat.CapabilityRegistry.Capability;
import com.cleanyourcar.launcher.compat.CapabilityRegistry.CapabilityStatus;
import com.cleanyourcar.launcher.compat.CapabilityRegistry.ChangeSet;
import com.cleanyourcar.launcher.compat.CapabilityRegistry.Evidence;
import com.cleanyourcar.launcher.compat.CapabilityRegistry.RuntimeConditions;
import com.cleanyourcar.launcher.compat.CapabilityRegistry.Validation;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CapabilityRegistryTest {

    private static final List<String> NO_BLOCKERS = Collections.emptyList();

    private static Map<String, String> deps() {
        Map<String, String> deps = new LinkedHashMap<>();
        deps.put(CapabilityRegistry.KEY_ENVIRONMENT, "env-1");
        deps.put(CapabilityRegistry.KEY_ADAPTER_REVISION, "xyauto-yt3518c@1");
        deps.put(CapabilityRegistry.KEY_SAFETY_PROFILE, "safety-1");
        deps.put(CapabilityRegistry.KEY_NAVIGATION_APP, "com.google.android.apps.maps@1");
        deps.put(CapabilityRegistry.KEY_MEDIA_APP, "com.autochips.bluetooth@1");
        deps.put(CapabilityRegistry.KEY_PERMISSIONS, "overlay+notifications");
        return deps;
    }

    private static RuntimeConditions available(Map<String, String> current) {
        return new RuntimeConditions(Availability.AVAILABLE, current, NO_BLOCKERS, false, false);
    }

    @Test
    public void capability_idsMapOneToOneToProfileContract() {
        Set<String> ids = new HashSet<>();
        for (Capability capability : Capability.values()) {
            assertTrue(ids.add(capability.id()));
            assertSame(capability, Capability.fromId(capability.id()));
        }
        assertEquals(DeviceProfile.CAPABILITY_IDS, ids);
        assertNull(Capability.fromId("REVERSE_DETECTION"));
        assertNull(Capability.fromId(null));
    }

    @Test
    public void capability_localEvidenceWithMatchingKeysIsReady() {
        CapabilityStatus status = CapabilityRegistry.evaluate(Capability.REVERSE_DETECTION,
                Evidence.verified(deps()), available(deps()));

        assertEquals(Availability.AVAILABLE, status.availability);
        assertEquals(Validation.VERIFIED, status.validation);
        assertTrue(status.blockers.isEmpty());
        assertTrue(status.isReady());
    }

    @Test
    public void capability_profileClaimNeverVerifies() throws Exception {
        DeviceProfile profile = ProfileCodec.parse(
                "{\"schemaVersion\":1,\"profileId\":\"claims\",\"revision\":1,"
                        + "\"minAppVersionCode\":1,\"platform\":\"aftermarket\","
                        + "\"adapterId\":\"generic\",\"match\":{\"sdkMin\":23,\"sdkMax\":37,"
                        + "\"buildProperties\":{}},\"roleDefaults\":{},"
                        + "\"featureClaims\":{\"overlay\":\"requires-local-check\","
                        + "\"reverse-detection\":\"unsupported\"},"
                        + "\"protectedPackages\":[],"
                        + "\"evidence\":{\"classification\":\"device-tested\"}}", 1);

        assertEquals(Availability.UNKNOWN,
                CapabilityRegistry.claimedAvailability(profile, Capability.OVERLAY));
        assertEquals(Availability.UNSUPPORTED,
                CapabilityRegistry.claimedAvailability(profile, Capability.REVERSE_DETECTION));
        assertEquals(Availability.UNKNOWN,
                CapabilityRegistry.claimedAvailability(profile, Capability.BRIGHTNESS));

        RuntimeConditions claimed = new RuntimeConditions(
                CapabilityRegistry.claimedAvailability(profile, Capability.OVERLAY),
                deps(), NO_BLOCKERS, false, false);
        CapabilityStatus profileClaimWithoutLocalEvidence = CapabilityRegistry.evaluate(
                Capability.OVERLAY, Evidence.untested(), claimed);
        assertEquals(Validation.UNTESTED, profileClaimWithoutLocalEvidence.validation);
        assertFalse(profileClaimWithoutLocalEvidence.isReady());

        CapabilityStatus observedButUntested = CapabilityRegistry.evaluate(
                Capability.OVERLAY, Evidence.untested(), available(deps()));
        assertEquals(Validation.UNTESTED, observedButUntested.validation);
        assertFalse(observedButUntested.isReady());
    }

    @Test
    public void capability_unknownOrBlockedCannotBeReady() {
        Evidence verified = Evidence.verified(deps());

        CapabilityStatus unknown = CapabilityRegistry.evaluate(Capability.OVERLAY, verified,
                new RuntimeConditions(Availability.UNKNOWN, deps(), NO_BLOCKERS, false, false));
        assertFalse(unknown.isReady());

        CapabilityStatus unsupported = CapabilityRegistry.evaluate(Capability.OVERLAY, verified,
                new RuntimeConditions(Availability.UNSUPPORTED, deps(), NO_BLOCKERS, false,
                        false));
        assertFalse(unsupported.isReady());

        CapabilityStatus blocked = CapabilityRegistry.evaluate(Capability.OVERLAY, verified,
                new RuntimeConditions(Availability.AVAILABLE, deps(),
                        Collections.singletonList("permission-denied:overlay"), false, false));
        assertEquals(Validation.VERIFIED, blocked.validation);
        assertEquals(Collections.singletonList("permission-denied:overlay"), blocked.blockers);
        assertFalse(blocked.isReady());

        CapabilityStatus noConsent = CapabilityRegistry.evaluate(Capability.BLE_RECEIVE,
                verified, new RuntimeConditions(Availability.AVAILABLE, deps(), NO_BLOCKERS,
                        true, false));
        assertTrue(noConsent.blockers.contains(CapabilityRegistry.BLOCKER_CONSENT_REQUIRED));
        assertFalse(noConsent.isReady());

        CapabilityStatus consented = CapabilityRegistry.evaluate(Capability.BLE_RECEIVE,
                verified, new RuntimeConditions(Availability.AVAILABLE, deps(), NO_BLOCKERS,
                        true, true));
        assertTrue(consented.isReady());

        CapabilityStatus failed = CapabilityRegistry.evaluate(Capability.OVERLAY,
                Evidence.failed(deps()), available(deps()));
        assertEquals(Validation.FAILED, failed.validation);
        assertFalse(failed.isReady());
    }

    @Test
    public void capability_staleEvidenceIsUntested() {
        Map<String, String> current = deps();
        current.put(CapabilityRegistry.KEY_SAFETY_PROFILE, "safety-2");

        CapabilityStatus stale = CapabilityRegistry.evaluate(Capability.REVERSE_DETECTION,
                Evidence.verified(deps()), available(current));
        assertEquals(Validation.UNTESTED, stale.validation);
        assertEquals(Collections.singletonList(CapabilityRegistry.KEY_SAFETY_PROFILE),
                stale.staleDependencies);
        assertFalse(stale.isReady());

        CapabilityStatus unaffected = CapabilityRegistry.evaluate(Capability.HOME,
                Evidence.verified(deps()), available(current));
        assertTrue(unaffected.isReady());

        Map<String, String> missing = deps();
        missing.remove(CapabilityRegistry.KEY_ENVIRONMENT);
        CapabilityStatus missingKey = CapabilityRegistry.evaluate(Capability.HOME,
                Evidence.verified(missing), available(deps()));
        assertEquals(Validation.UNTESTED, missingKey.validation);
        assertFalse(missingKey.isReady());
    }

    @Test
    public void invalidate_firmwareClearsSafetyEvidence() {
        Set<String> affected = CapabilityRegistry.invalidate(
                ChangeSet.of(CapabilityRegistry.KEY_ENVIRONMENT));

        assertTrue(affected.contains(Capability.REVERSE_DETECTION.id()));
        assertTrue(affected.contains(Capability.OVERLAY.id()));
        assertTrue(affected.contains(Capability.BRIGHTNESS.id()));

        Set<String> safetyHash = CapabilityRegistry.invalidate(
                ChangeSet.of(CapabilityRegistry.KEY_SAFETY_PROFILE));
        assertEquals(new HashSet<>(Arrays.asList(Capability.REVERSE_DETECTION.id(),
                Capability.OVERLAY.id())), safetyHash);
    }

    @Test
    public void invalidate_mediaRolePreservesThemeAndHomeEvidence() {
        Set<String> affected = CapabilityRegistry.invalidate(
                ChangeSet.of(CapabilityRegistry.KEY_MEDIA_APP));

        assertEquals(new HashSet<>(Arrays.asList(Capability.MEDIA_TRANSPORT.id(),
                Capability.MEDIA_METADATA.id(), Capability.SOURCE_SELECTION.id())), affected);
        assertFalse(affected.contains(Capability.HOME.id()));
        assertFalse(affected.contains(Capability.APP_LAUNCH.id()));
        assertFalse(affected.contains(Capability.NAVIGATION.id()));
    }

    @Test
    public void invalidate_permissionAndUnknownKeys() {
        Set<String> permission = CapabilityRegistry.invalidate(
                ChangeSet.of(CapabilityRegistry.KEY_PERMISSIONS));
        assertTrue(permission.contains(Capability.OVERLAY.id()));
        assertFalse(permission.contains(Capability.HOME.id()));
        assertFalse(permission.contains(Capability.REVERSE_DETECTION.id()));

        assertTrue(CapabilityRegistry.invalidate(ChangeSet.of()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ChangeSet.of("theme"));
    }
}
