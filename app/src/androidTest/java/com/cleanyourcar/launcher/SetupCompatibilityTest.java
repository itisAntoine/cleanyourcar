package com.cleanyourcar.launcher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.cleanyourcar.launcher.CarIntentRouter.LaunchResult;
import com.cleanyourcar.launcher.compat.CapabilityRegistry;
import com.cleanyourcar.launcher.compat.EnvironmentFacts;
import com.cleanyourcar.launcher.compat.ProfileCodec;
import com.cleanyourcar.launcher.compat.ProfileFormatException;
import com.cleanyourcar.launcher.config.ConfigurationStore;
import com.cleanyourcar.launcher.config.RoleSelection.Role;
import com.cleanyourcar.launcher.integration.HeadUnitAdapter;
import com.cleanyourcar.launcher.integration.MediaState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Setup and compatibility qualification on a real device: imported configuration is durable,
 * permission denials only remove the dependent feature, and system restrictions (e.g. AAOS
 * parked-use rules) are reported instead of being routed around.
 */
@RunWith(AndroidJUnit4.class)
public class SetupCompatibilityTest {

    private static final int APP_VERSION = 1;

    private File dir;
    private String overlayModeBefore;

    @Before
    public void setUp() throws IOException {
        dir = new File(QualificationSupport.targetContext().getCacheDir(),
                "setup-compat-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        overlayModeBefore = QualificationSupport.overlayMode();
    }

    @After
    public void tearDown() throws IOException {
        QualificationSupport.setOverlayMode(overlayModeBefore);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        dir.delete();
    }

    @Test
    public void importedConfigurationSurvivesCancellationAndFailure() throws Exception {
        File file = new File(dir, ConfigurationStore.FILE_NAME);
        FlakyStorage storage = new FlakyStorage(new ConfigurationStore.FileStorage(file));
        ConfigurationStore store = new ConfigurationStore(storage, APP_VERSION);
        store.migrateLegacy(Collections.<String, Object>emptyMap());

        store.applyProfile(store.importProfile(
                QualificationSupport.readTestAsset("valid-xyauto.json")));
        assertEquals("xyauto-yt3518c", store.current().acceptedProfileId);
        byte[] accepted = readFile(file);

        // Cancelled import: a preview that is never applied changes nothing, even on reload.
        ConfigurationStore.ConfigurationChange cancelled = store.importProfile(
                QualificationSupport.readTestAsset("valid-generic.json"));
        assertEquals("generic-android", cancelled.newConfiguration.acceptedProfileId);
        assertAcceptedProfileKept(store, file, accepted);

        // Rejected imports: malformed, unsupported, and oversized profiles never apply.
        List<byte[]> rejected = new ArrayList<>();
        rejected.add(QualificationSupport.readTestAsset("invalid-adapter.json"));
        rejected.add(QualificationSupport.readTestAsset("invalid-command.json"));
        rejected.add(QualificationSupport.readTestAsset("invalid-type.json"));
        rejected.add("{\"profileId\":".getBytes("UTF-8"));
        rejected.add(new byte[0]);
        rejected.add(new byte[ProfileCodec.MAX_PROFILE_BYTES + 1]);
        for (byte[] bytes : rejected) {
            try {
                store.importProfile(bytes);
                fail("an invalid profile of " + bytes.length + " bytes was accepted");
            } catch (ProfileFormatException expected) {
                // The import is refused before anything is written.
            }
            assertAcceptedProfileKept(store, file, accepted);
        }

        // Failed write: the previous file and in-memory configuration remain.
        ConfigurationStore.ConfigurationChange change = store.importProfile(
                QualificationSupport.readTestAsset("valid-generic.json"));
        storage.failWrites = true;
        try {
            store.applyProfile(change);
            fail("a failing write was reported as success");
        } catch (IOException expected) {
            // Reported to the user as a failed import.
        }
        storage.failWrites = false;
        assertAcceptedProfileKept(store, file, accepted);
    }

    @Test
    public void locationDenialKeepsSetupFinishableAsForegroundApp() {
        assertFalse(SetupFlow.requiresLocationPermission(SetupFlow.ReferenceChoice.CITY));
        assertFalse(SetupFlow.requiresLocationPermission(SetupFlow.ReferenceChoice.NONE));

        SetupFlow flow = new SetupFlow(Collections.<SetupFlow.Feature>emptySet());
        flow.onHomeResult(SetupFlow.HomeResult.DECLINED);
        flow.onLocationPermissionResult(false);

        assertTrue(flow.canFinish());
        assertSame(SetupFlow.Entry.FOREGROUND_APP, flow.entry());
        assertFalse(flow.isConsented(SetupFlow.Feature.CURRENT_LOCATION));
        assertFalse(flow.isConsented(SetupFlow.Feature.HOME));
    }

    @Test
    public void overlayDenialRemovesOnlyTheHudAndKeepsForegroundBasicsUsable() throws Exception {
        QualificationSupport.setOverlayMode("ignore");
        Context context = QualificationSupport.targetContext();
        assumeTrue("the device kept the overlay permission granted",
                !OverlayHudService.canDrawOverlays(context));

        CapabilityRegistry.CapabilityStatus hud = OverlayHudService.currentHudStatus(context);
        assertTrue(hud.blockers.contains(CapabilityRegistry.KEY_PERMISSION_OVERLAY));
        assertFalse(hud.isReady());
        HudSessionPolicy.Decision decision = new HudSessionPolicy(
                EnvironmentFacts.PLATFORM_AFTERMARKET, HeadUnitAdapter.ReverseSupport.VERIFIED)
                .requestShow(hud, MediaState.ReverseState.INACTIVE, true);
        assertSame(HudSessionPolicy.Action.DENY, decision.action);
        assertSame(HudSessionPolicy.Reason.OVERLAY_PERMISSION_MISSING, decision.reason);

        boolean setupSeen = QualificationSupport.markSetupSeen(true);
        MainActivity activity = null;
        try {
            activity = QualificationSupport.launchMain();
            final MainActivity main = activity;
            int reachable = QualificationSupport.onMain(new Callable<Integer>() {
                @Override
                public Integer call() {
                    View root = main.getWindow().getDecorView();
                    List<View> actions = QualificationSupport.actionViews(root);
                    int longClickable = 0;
                    for (View action : actions) {
                        QualificationSupport.assertReachable("overlay denied", root, action);
                        if (action.isLongClickable()) {
                            longClickable++;
                        }
                    }
                    assertTrue("no settings entry (long-press action) is shown",
                            longClickable > 0);
                    return actions.size();
                }
            });
            assertTrue("the launcher shows no actions without the overlay permission",
                    reachable > 0);
            assertFalse(activity.isFinishing());
        } finally {
            QualificationSupport.finish(activity);
            QualificationSupport.markSetupSeen(setupSeen);
        }
    }

    @Test
    public void restrictedRoleLaunchIsReportedWithoutBypassRouteOnAaos() throws Exception {
        ConfigurationStore legacy = new ConfigurationStore(
                new ConfigurationStore.FileStorage(new File(dir, "legacy.json")), APP_VERSION);
        legacy.migrateLegacy(Collections.<String, Object>emptyMap(), true);

        for (Role role : new Role[] {Role.DASHCAM, Role.EQUALIZER}) {
            RestrictedContext restricted = new RestrictedContext(
                    QualificationSupport.targetContext());
            LaunchResult result = CarIntentRouter.launchRole(
                    CarIntentRouter.resolveRole(legacy.current(), role),
                    new CarIntentRouter.AndroidTransport(restricted), true);
            assertSame(role + " was not reported as restricted",
                    LaunchResult.Reason.NOT_PERMITTED, result.reason);
            assertEquals(role + " tried another route after a restriction: " + restricted.started,
                    1, restricted.started.size());
        }
    }

    @Test
    public void restrictedSettingsScreenIsExplainedNotReplacedOnAaos() {
        final RestrictedContext restricted = new RestrictedContext(
                QualificationSupport.targetContext());
        QualificationSupport.onMain(new Runnable() {
            @Override
            public void run() {
                CarIntentRouter.launchSystemSettings(restricted,
                        Settings.ACTION_BLUETOOTH_SETTINGS, true);
            }
        });
        assertEquals(Collections.singletonList(Settings.ACTION_BLUETOOTH_SETTINGS),
                restricted.started);
    }

    @Test
    public void refusedSettingsScreenFallsBackOffAaos() {
        // On aftermarket firmware a SecurityException means a protected panel, not a block.
        final RestrictedContext refused = new RestrictedContext(
                QualificationSupport.targetContext());
        QualificationSupport.onMain(new Runnable() {
            @Override
            public void run() {
                CarIntentRouter.launchSystemSettings(refused,
                        Settings.ACTION_BLUETOOTH_SETTINGS, false);
            }
        });
        assertEquals(Arrays.asList(Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SETTINGS),
                refused.started);
    }

    private static void assertAcceptedProfileKept(ConfigurationStore store, File file,
            byte[] accepted) throws IOException {
        assertEquals("xyauto-yt3518c", store.current().acceptedProfileId);
        assertArrayEquals(accepted, readFile(file));
        ConfigurationStore reloaded = new ConfigurationStore(
                new ConfigurationStore.FileStorage(file), APP_VERSION);
        assertNotNull(reloaded.migrateLegacy(Collections.<String, Object>emptyMap()));
        assertEquals("xyauto-yt3518c", reloaded.current().acceptedProfileId);
    }

    private static byte[] readFile(File file) throws IOException {
        return new ConfigurationStore.FileStorage(file).read();
    }

    /** Storage whose writes can be made to fail, as on a full or read-only data partition. */
    private static final class FlakyStorage implements ConfigurationStore.Storage {
        private final ConfigurationStore.Storage delegate;
        boolean failWrites;

        FlakyStorage(ConfigurationStore.Storage delegate) {
            this.delegate = delegate;
        }

        @Override
        public byte[] read() throws IOException {
            return delegate.read();
        }

        @Override
        public void writeAtomically(byte[] data) throws IOException {
            if (failWrites) {
                throw new IOException("simulated write failure");
            }
            delegate.writeAtomically(data);
        }

        @Override
        public void moveAside(String suffix) throws IOException {
            delegate.moveAside(suffix);
        }
    }

    /**
     * A context on which every activity start throws SecurityException. On AAOS this stands for
     * a refused start; real AAOS UX restrictions more often launch and then swap in the
     * system's ActivityBlockingActivity, which needs a physical AAOS run to observe.
     */
    private static final class RestrictedContext extends ContextWrapper {
        final List<String> started = new ArrayList<>();

        RestrictedContext(Context base) {
            super(base);
        }

        @Override
        public void startActivity(Intent intent) {
            started.add(intent.getComponent() != null
                    ? intent.getComponent().flattenToShortString() : intent.getAction());
            throw new SecurityException("blocked while driving");
        }
    }
}
