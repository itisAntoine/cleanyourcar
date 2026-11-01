package com.cleanyourcar.launcher;

import com.cleanyourcar.launcher.CarIntentRouter.LaunchResult;
import com.cleanyourcar.launcher.CarIntentRouter.ResolvedRole;
import com.cleanyourcar.launcher.config.ConfigurationStore;
import com.cleanyourcar.launcher.config.LauncherConfiguration;
import com.cleanyourcar.launcher.config.NavigationReference;
import com.cleanyourcar.launcher.config.RoleSelection;
import com.cleanyourcar.launcher.config.RoleSelection.Role;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RoleRoutingTest {

    private static final PlusCodeCodec.LatLon BEN_THANH =
            new PlusCodeCodec.LatLon(10.7725, 106.6980, "Ben Thanh");

    /** Records every emitted request; URIs are plain strings so no Android Uri stub runs. */
    private static final class FakeTransport implements CarIntentRouter.Transport {
        final List<String> calls = new ArrayList<>();
        final List<String> viewUris = new ArrayList<>();
        final List<String> viewPackages = new ArrayList<>();
        /** Result returned for every request; tests switch it to simulate a missing app. */
        LaunchResult outcome = LaunchResult.launched();

        @Override
        public LaunchResult startView(String uri, String packageName) {
            calls.add("view " + uri + " " + packageName);
            viewUris.add(uri);
            viewPackages.add(packageName);
            return outcome;
        }

        @Override
        public LaunchResult startAction(String action) {
            calls.add("action " + action);
            return outcome;
        }

        @Override
        public LaunchResult launchComponent(String packageName, String className) {
            calls.add("component " + packageName + "/" + className);
            return outcome;
        }

        @Override
        public LaunchResult launchPackage(String packageName) {
            calls.add("package " + packageName);
            return outcome;
        }
    }

    @Test
    public void geoProviderDoesNotReceiveGoogleNavigationScheme() throws IOException {
        ConfigurationStore store = newInstallStore();
        store.setRole(Role.NAVIGATION, new RoleSelection("net.osmand", null, "geo", null));

        ResolvedRole navigation = CarIntentRouter.resolveRole(store.current(), Role.NAVIGATION);
        assertTrue(navigation.isAvailable());
        FakeTransport transport = new FakeTransport();
        LaunchResult result = CarIntentRouter.launchNavigation(navigation, BEN_THANH, transport);

        assertEquals(LaunchResult.Status.LAUNCHED, result.status);
        assertEquals(1, transport.viewUris.size());
        String launchedUriString = transport.viewUris.get(0);
        assertTrue(launchedUriString.startsWith("geo:"));
        assertFalse(launchedUriString.contains("google.navigation"));
        assertEquals("net.osmand", transport.viewPackages.get(0));

        // The google-navigation strategy emits only its own scheme to its own package.
        store.setRole(Role.NAVIGATION, new RoleSelection(CarIntentRouter.PKG_GOOGLE_MAPS, null,
                "google-navigation", null));
        FakeTransport google = new FakeTransport();
        CarIntentRouter.launchNavigation(
                CarIntentRouter.resolveRole(store.current(), Role.NAVIGATION), BEN_THANH, google);
        assertEquals(1, google.viewUris.size());
        assertTrue(google.viewUris.get(0).startsWith("google.navigation:q="));
        assertEquals(CarIntentRouter.PKG_GOOGLE_MAPS, google.viewPackages.get(0));

        // A launch-only navigation app is opened without a destination URI.
        store.setRole(Role.NAVIGATION, new RoleSelection("com.example.nav", null,
                "launch-package", null));
        FakeTransport launchOnly = new FakeTransport();
        LaunchResult opened = CarIntentRouter.launchNavigation(
                CarIntentRouter.resolveRole(store.current(), Role.NAVIGATION), BEN_THANH,
                launchOnly);
        assertEquals(LaunchResult.Status.LAUNCHED, opened.status);
        assertEquals(LaunchResult.Reason.DESTINATION_NOT_SENT, opened.reason);
        assertTrue(launchOnly.viewUris.isEmpty());
        assertEquals(Collections.singletonList("package com.example.nav"), launchOnly.calls);
    }

    @Test
    public void missingRoleDoesNotLaunchXyautoDefault() throws IOException {
        LauncherConfiguration generic = newInstallStore().current();
        assertFalse(generic.legacyXyAutoUser);

        for (Role role : Role.values()) {
            ResolvedRole resolved = CarIntentRouter.resolveRole(generic, role);
            assertFalse(role.key(), resolved.isAvailable());
            assertEquals(ResolvedRole.Unavailable.NOT_CONFIGURED, resolved.reason);
            assertNull(resolved.selection);

            FakeTransport transport = new FakeTransport();
            LaunchResult result = CarIntentRouter.launchRole(resolved, transport);
            assertEquals(LaunchResult.Status.BLOCKED, result.status);
            assertEquals(LaunchResult.Reason.ROLE_UNAVAILABLE, result.reason);
            assertTrue(transport.calls.isEmpty());
        }
        FakeTransport navTransport = new FakeTransport();
        LaunchResult nav = CarIntentRouter.launchNavigation(
                CarIntentRouter.resolveRole(generic, Role.NAVIGATION), BEN_THANH, navTransport);
        assertEquals(LaunchResult.Status.BLOCKED, nav.status);
        assertTrue(navTransport.calls.isEmpty());

        // Upgraded installs without an accepted profile use generic behavior and no vendor roles.
        ConfigurationStore legacyStore = store();
        legacyStore.migrateLegacy(Collections.<String, Object>emptyMap(), true);
        LauncherConfiguration legacy = legacyStore.current();
        assertFalse(legacy.legacyXyAutoUser);
        ResolvedRole equalizer = CarIntentRouter.resolveRole(legacy, Role.EQUALIZER);
        assertFalse(equalizer.isAvailable());
        assertNull(equalizer.selection);
        FakeTransport legacyTransport = new FakeTransport();
        CarIntentRouter.launchRole(CarIntentRouter.resolveRole(legacy, Role.DASHCAM),
                legacyTransport);
        assertTrue(legacyTransport.calls.isEmpty());

        // A generic install may not use an XYAuto-only strategy, nor a foreign component.
        ConfigurationStore genericStore = newInstallStore();
        genericStore.setRole(Role.EQUALIZER, new RoleSelection(CarIntentRouter.PKG_EQUALIZER,
                null, "xyauto-equalizer", null));
        assertEquals(ResolvedRole.Unavailable.ADAPTER_REQUIRED, CarIntentRouter.resolveRole(
                genericStore.current(), Role.EQUALIZER).reason);
        genericStore.setRole(Role.DASHCAM, new RoleSelection("com.example.cam",
                "com.other.app/.Main", "launch-component", null));
        assertEquals(ResolvedRole.Unavailable.COMPONENT_MISMATCH, CarIntentRouter.resolveRole(
                genericStore.current(), Role.DASHCAM).reason);
        genericStore.setRole(Role.RADIO, new RoleSelection("com.example.radio", null, "geo",
                null));
        assertEquals(ResolvedRole.Unavailable.STRATEGY_NOT_ALLOWED, CarIntentRouter.resolveRole(
                genericStore.current(), Role.RADIO).reason);
    }

    @Test
    public void deniedHomeLeavesForegroundUsable() throws IOException {
        SetupFlow flow = new SetupFlow(EnumSet.noneOf(SetupFlow.Feature.class));
        flow.onHomeResult(SetupFlow.HomeResult.DECLINED);
        assertFalse(flow.isConsented(SetupFlow.Feature.HOME));
        assertEquals(SetupFlow.Entry.FOREGROUND_APP, flow.entry());
        assertTrue(flow.canFinish());

        flow.onHomeResult(SetupFlow.HomeResult.CANCELLED);
        assertEquals(SetupFlow.Entry.FOREGROUND_APP, flow.entry());
        assertTrue(flow.canFinish());

        // Setup is offered once to generic installs and never forced again after a refusal.
        assertTrue(SetupFlow.shouldAutoOpen(false, false, false));
        assertFalse(SetupFlow.shouldAutoOpen(true, false, false));
        assertTrue(SetupFlow.shouldAutoOpen(false, true, false));
        assertFalse(SetupFlow.shouldAutoOpen(false, false, true));

        // Location permission is requested only for the feature that needs it.
        assertFalse(SetupFlow.requiresLocationPermission(SetupFlow.ReferenceChoice.CITY));
        assertFalse(SetupFlow.requiresLocationPermission(SetupFlow.ReferenceChoice.NONE));
        assertTrue(SetupFlow.requiresLocationPermission(
                SetupFlow.ReferenceChoice.CURRENT_LOCATION));

        // The ordinary launcher entry stays declared, so the app opens without HOME.
        String manifest = new String(Files.readAllBytes(
                new File("src/main/AndroidManifest.xml").toPath()), StandardCharsets.UTF_8);
        int main = manifest.indexOf("android:name=\".MainActivity\"");
        int mainEnd = manifest.indexOf("</activity>", main);
        assertTrue(main >= 0);
        assertTrue(manifest.substring(main, mainEnd)
                .contains("android.intent.category.LAUNCHER"));

        // Granting HOME is recorded as a consent only.
        flow.onHomeResult(SetupFlow.HomeResult.SET_AS_HOME);
        assertTrue(flow.isConsented(SetupFlow.Feature.HOME));
        assertEquals(SetupFlow.Entry.HOME, flow.entry());
        assertEquals(EnumSet.of(SetupFlow.Feature.HOME), flow.consentedFeatures());
    }

    @Test
    public void importingCurrentLocationModeRequiresFreshLocalConsent() {
        SetupFlow flow = new SetupFlow(EnumSet.of(SetupFlow.Feature.CURRENT_LOCATION));
        flow.onImportedReferenceMode(NavigationReference.Mode.CURRENT_LOCATION);
        assertFalse(flow.isConsented(SetupFlow.Feature.CURRENT_LOCATION));
    }

    /** Counts overlay requests so tests can prove the HUD only follows a real launch. */
    private static final class FakeOverlay implements CarIntentRouter.Overlay {
        int shown;

        @Override
        public void show() {
            shown++;
        }
    }

    @Test
    public void navigationOverlayStartsOnlyAfterSuccessfulLaunch() throws IOException {
        // Generic install with no navigation role: nothing launches and no overlay appears.
        ResolvedRole unset = CarIntentRouter.resolveRole(newInstallStore().current(),
                Role.NAVIGATION);
        FakeTransport transport = new FakeTransport();
        FakeOverlay overlay = new FakeOverlay();
        assertEquals(LaunchResult.Reason.ROLE_UNAVAILABLE,
                CarIntentRouter.openNavigationApp(unset, transport, overlay).reason);
        assertEquals(LaunchResult.Reason.ROLE_UNAVAILABLE, CarIntentRouter.launchNavigation(
                unset, BEN_THANH, transport, overlay).reason);
        assertTrue(transport.calls.isEmpty());
        assertEquals(0, overlay.shown);

        ConfigurationStore store = newInstallStore();
        store.setRole(Role.NAVIGATION, new RoleSelection("net.osmand", null, "geo", null));
        ResolvedRole osmand = CarIntentRouter.resolveRole(store.current(), Role.NAVIGATION);

        // The chosen app is missing: the launch fails and the overlay stays hidden.
        FakeTransport missing = new FakeTransport();
        missing.outcome = LaunchResult.failed(LaunchResult.Reason.NOT_FOUND);
        assertFalse(CarIntentRouter.openNavigationApp(osmand, missing, overlay).isLaunched());
        assertFalse(CarIntentRouter.launchNavigation(osmand, BEN_THANH, missing, overlay)
                .isLaunched());
        assertFalse(CarIntentRouter.launchGridApp("net.osmand", osmand, missing, overlay)
                .isLaunched());
        assertEquals(0, overlay.shown);

        // Only real launches of the navigation app bring up the overlay.
        FakeTransport working = new FakeTransport();
        assertTrue(CarIntentRouter.openNavigationApp(osmand, working, overlay).isLaunched());
        assertEquals(1, overlay.shown);
        assertTrue(CarIntentRouter.launchNavigation(osmand, BEN_THANH, working, overlay)
                .isLaunched());
        assertEquals(2, overlay.shown);
        assertTrue(CarIntentRouter.launchGridApp("net.osmand", osmand, working, overlay)
                .isLaunched());
        assertEquals(3, overlay.shown);

        // Other grid apps, or any grid app when navigation is unset, never show the overlay.
        assertTrue(CarIntentRouter.launchGridApp("com.example.music", osmand, working, overlay)
                .isLaunched());
        assertTrue(CarIntentRouter.launchGridApp("net.osmand", unset, working, overlay)
                .isLaunched());
        assertEquals(3, overlay.shown);
    }

    @Test
    public void restrictedLaunchIsReportedWithoutTryingAnotherRouteOnAaos() throws Exception {
        ConfigurationStore legacyStore = acceptedXyAutoStore();
        FakeTransport restricted = new FakeTransport();
        restricted.outcome = LaunchResult.failed(LaunchResult.Reason.NOT_PERMITTED);

        LaunchResult dashcam = CarIntentRouter.launchRole(
                CarIntentRouter.resolveRole(legacyStore.current(), Role.DASHCAM), restricted,
                true);
        assertEquals(LaunchResult.Reason.NOT_PERMITTED, dashcam.reason);
        assertEquals(Collections.singletonList("component " + CarIntentRouter.PKG_DASHCAM + "/"
                + CarIntentRouter.CLS_DASHCAM_MAIN), restricted.calls);

        restricted.calls.clear();
        LaunchResult equalizer = CarIntentRouter.launchRole(
                CarIntentRouter.resolveRole(legacyStore.current(), Role.EQUALIZER), restricted,
                true);
        assertEquals(LaunchResult.Reason.NOT_PERMITTED, equalizer.reason);
        assertEquals(1, restricted.calls.size());

        // A missing component (not a restriction) still falls back to the package entry.
        FakeTransport missing = new FakeTransport();
        missing.outcome = LaunchResult.failed(LaunchResult.Reason.NOT_FOUND);
        CarIntentRouter.launchRole(
                CarIntentRouter.resolveRole(legacyStore.current(), Role.DASHCAM), missing, true);
        assertEquals(2, missing.calls.size());
    }

    @Test
    public void refusedRouteStillFallsBackOffAaos() throws Exception {
        ConfigurationStore legacyStore = acceptedXyAutoStore();

        // On aftermarket firmware NOT_PERMITTED usually means a protected component.
        FakeTransport refused = new FakeTransport();
        refused.outcome = LaunchResult.failed(LaunchResult.Reason.NOT_PERMITTED);
        LaunchResult dashcam = CarIntentRouter.launchRole(
                CarIntentRouter.resolveRole(legacyStore.current(), Role.DASHCAM), refused,
                false);
        assertEquals(LaunchResult.Reason.NOT_PERMITTED, dashcam.reason);
        assertEquals(Arrays.asList("component " + CarIntentRouter.PKG_DASHCAM + "/"
                + CarIntentRouter.CLS_DASHCAM_MAIN, "package " + CarIntentRouter.PKG_DASHCAM),
                refused.calls);

        refused.calls.clear();
        CarIntentRouter.launchRole(
                CarIntentRouter.resolveRole(legacyStore.current(), Role.EQUALIZER), refused,
                false);
        assertEquals(3, refused.calls.size());
        assertTrue(refused.calls.get(2).startsWith("package "));

        // The public entry point with a test transport keeps the non-AAOS fallbacks.
        refused.calls.clear();
        CarIntentRouter.launchRole(
                CarIntentRouter.resolveRole(legacyStore.current(), Role.DASHCAM), refused);
        assertEquals(2, refused.calls.size());
    }

    @Test
    public void deniedHudRequestRemovesAnAlreadyShowingOverlay() {
        final HudSessionPolicy shipped = new HudSessionPolicy(
                com.cleanyourcar.launcher.compat.EnvironmentFacts.PLATFORM_AFTERMARKET,
                com.cleanyourcar.launcher.integration.HeadUnitAdapter.ReverseSupport
                        .UNVERIFIED_EVENTS);
        FakeOverlay start = new FakeOverlay();
        FakeOverlay stop = new FakeOverlay();
        CarIntentRouter.gatedOverlay(new CarIntentRouter.HudGate() {
            @Override
            public HudSessionPolicy.Decision requestShow() {
                return shipped.requestShow(HudSessionPolicyTest.readyOverlay(),
                        com.cleanyourcar.launcher.integration.MediaState.ReverseState.INACTIVE,
                        true);
            }

            @Override
            public void explainDenial(HudSessionPolicy.Reason reason) {
            }
        }, start, stop).show();
        assertEquals(0, start.shown);
        assertEquals(1, stop.shown);

        final HudSessionPolicy verified = HudSessionPolicyTest.verifiedPolicy();
        CarIntentRouter.gatedOverlay(new CarIntentRouter.HudGate() {
            @Override
            public HudSessionPolicy.Decision requestShow() {
                return verified.requestShow(HudSessionPolicyTest.readyOverlay(),
                        com.cleanyourcar.launcher.integration.MediaState.ReverseState.INACTIVE,
                        true);
            }

            @Override
            public void explainDenial(HudSessionPolicy.Reason reason) {
            }
        }, start, stop).show();
        assertEquals(1, start.shown);
        assertEquals(1, stop.shown);
    }

    private static ConfigurationStore newInstallStore() throws IOException {
        ConfigurationStore store = store();
        store.migrateLegacy(Collections.<String, Object>emptyMap(), false);
        return store;
    }

    private static ConfigurationStore acceptedXyAutoStore() throws Exception {
        ConfigurationStore store = store();
        store.migrateLegacy(Collections.<String, Object>emptyMap(), true);
        try (InputStream profile = RoleRoutingTest.class.getClassLoader()
                .getResourceAsStream("valid-xyauto.json")) {
            if (profile == null) throw new IOException("missing XYAuto profile fixture");
            store.applyProfile(store.importProfile(profile));
        }
        return store;
    }

    private static ConfigurationStore store() {
        return new ConfigurationStore(new ConfigurationStore.Storage() {
            byte[] bytes;

            @Override
            public byte[] read() {
                return bytes;
            }

            @Override
            public void writeAtomically(byte[] data) {
                bytes = data.clone();
            }

            @Override
            public void moveAside(String suffix) {
                bytes = null;
            }
        }, 1);
    }

    @Test
    public void navigationHudStartsOnlyWhenSessionPolicyGrantsIt() {
        final List<HudSessionPolicy.Reason> explained = new ArrayList<>();
        final HudSessionPolicy shipped = new HudSessionPolicy(
                com.cleanyourcar.launcher.compat.EnvironmentFacts.PLATFORM_AFTERMARKET,
                com.cleanyourcar.launcher.integration.HeadUnitAdapter.ReverseSupport
                        .UNVERIFIED_EVENTS);
        FakeOverlay start = new FakeOverlay();
        CarIntentRouter.gatedOverlay(new CarIntentRouter.HudGate() {
            @Override
            public HudSessionPolicy.Decision requestShow() {
                return shipped.requestShow(HudSessionPolicyTest.readyOverlay(),
                        com.cleanyourcar.launcher.integration.MediaState.ReverseState.INACTIVE,
                        true);
            }

            @Override
            public void explainDenial(HudSessionPolicy.Reason reason) {
                explained.add(reason);
            }
        }, start).show();
        assertEquals(0, start.shown);
        assertEquals(Collections.singletonList(HudSessionPolicy.Reason.REVERSE_NOT_VERIFIED),
                explained);

        final HudSessionPolicy verified = HudSessionPolicyTest.verifiedPolicy();
        CarIntentRouter.gatedOverlay(new CarIntentRouter.HudGate() {
            @Override
            public HudSessionPolicy.Decision requestShow() {
                return verified.requestShow(HudSessionPolicyTest.readyOverlay(),
                        com.cleanyourcar.launcher.integration.MediaState.ReverseState.INACTIVE,
                        true);
            }

            @Override
            public void explainDenial(HudSessionPolicy.Reason reason) {
                explained.add(reason);
            }
        }, start).show();
        assertEquals(1, start.shown);
        assertEquals(1, explained.size());
    }
}
