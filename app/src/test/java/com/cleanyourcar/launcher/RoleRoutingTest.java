package com.cleanyourcar.launcher;

import com.cleanyourcar.launcher.CarIntentRouter.LaunchResult;
import com.cleanyourcar.launcher.CarIntentRouter.ResolvedRole;
import com.cleanyourcar.launcher.config.ConfigurationStore;
import com.cleanyourcar.launcher.config.LauncherConfiguration;
import com.cleanyourcar.launcher.config.RoleSelection;
import com.cleanyourcar.launcher.config.RoleSelection.Role;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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

        @Override
        public LaunchResult startView(String uri, String packageName) {
            calls.add("view " + uri + " " + packageName);
            viewUris.add(uri);
            viewPackages.add(packageName);
            return LaunchResult.launched();
        }

        @Override
        public LaunchResult startAction(String action) {
            calls.add("action " + action);
            return LaunchResult.launched();
        }

        @Override
        public LaunchResult launchComponent(String packageName, String className) {
            calls.add("component " + packageName + "/" + className);
            return LaunchResult.launched();
        }

        @Override
        public LaunchResult launchPackage(String packageName) {
            calls.add("package " + packageName);
            return LaunchResult.launched();
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

        // Existing XYAuto installs keep their current built-in apps.
        ConfigurationStore legacyStore = store();
        legacyStore.migrateLegacy(Collections.<String, Object>emptyMap(), true);
        LauncherConfiguration legacy = legacyStore.current();
        assertTrue(legacy.legacyXyAutoUser);
        ResolvedRole equalizer = CarIntentRouter.resolveRole(legacy, Role.EQUALIZER);
        assertTrue(equalizer.isAvailable());
        assertEquals(CarIntentRouter.PKG_EQUALIZER, equalizer.selection.packageName);
        FakeTransport legacyTransport = new FakeTransport();
        CarIntentRouter.launchRole(CarIntentRouter.resolveRole(legacy, Role.DASHCAM),
                legacyTransport);
        assertEquals(Collections.singletonList("component " + CarIntentRouter.PKG_DASHCAM + "/"
                + CarIntentRouter.CLS_DASHCAM_MAIN), legacyTransport.calls);

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
        assertFalse(SetupFlow.shouldAutoOpen(false, true, false));
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

    private static ConfigurationStore newInstallStore() throws IOException {
        ConfigurationStore store = store();
        store.migrateLegacy(Collections.<String, Object>emptyMap(), false);
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
}
