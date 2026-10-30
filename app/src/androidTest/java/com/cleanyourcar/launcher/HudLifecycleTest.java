package com.cleanyourcar.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.cleanyourcar.launcher.compat.CapabilityRegistry;
import com.cleanyourcar.launcher.compat.EnvironmentFacts;
import com.cleanyourcar.launcher.integration.HeadUnitAdapter;
import com.cleanyourcar.launcher.integration.MediaState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * HUD session lifecycle on a real window manager. No shipped adapter has verified reverse
 * support, so the shipped service never grants a session; these tests open one through a
 * test-only policy (aftermarket platform, verified reverse, verified HUD evidence) and check
 * that every closing event removes the overlay windows and overlay dialogs and stops the
 * service.
 */
@RunWith(AndroidJUnit4.class)
public class HudLifecycleTest {

    private String overlayModeBefore;
    private TestHudService service;

    @Before
    public void setUp() throws IOException {
        overlayModeBefore = QualificationSupport.overlayMode();
        QualificationSupport.setOverlayMode("allow");
        assumeTrue("overlay permission could not be granted for the test",
                OverlayHudService.canDrawOverlays(QualificationSupport.targetContext()));
    }

    @After
    public void tearDown() throws IOException {
        if (service != null) {
            QualificationSupport.onMain(new Runnable() {
                @Override
                public void run() {
                    PlusCodeDialog.dismissActive();
                    service.onDestroy();
                }
            });
        }
        QualificationSupport.setOverlayMode(overlayModeBefore);
    }

    @Test
    public void shippedServiceNeverGrantsHudOnUnverifiedAdapters() {
        Context context = QualificationSupport.targetContext();
        assertFalse(OverlayHudService.currentHudStatus(context).isReady());
        assertFalse(OverlayHudService.isMapLayoutVerified(context));
        HudSessionPolicy.Decision decision = OverlayHudService.newPolicy(context).requestShow(
                OverlayHudService.currentHudStatus(context), MediaState.ReverseState.INACTIVE,
                true);
        assertSame(HudSessionPolicy.Action.DENY, decision.action);
    }

    @Test
    public void reverseRemovesWindowsDismissesOverlayDialogAndStops() throws Exception {
        service = showingService();
        final Dialog keypad = QualificationSupport.onMain(new Callable<Dialog>() {
            @Override
            public Dialog call() throws Exception {
                PlusCodeDialog.show(service, true);
                return activePlusCodeDialog();
            }
        });
        assertNotNull("the HUD keypad was not opened", keypad);
        assertTrue(QualificationSupport.onMain(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return keypad.isShowing();
            }
        }));

        QualificationSupport.onMain(new Runnable() {
            @Override
            public void run() {
                service.onReverseStateChanged(MediaState.ReverseState.ACTIVE);
            }
        });

        assertClosed(service);
        assertNull("the overlay keypad outlived reverse", activePlusCodeDialog());
        assertFalse(QualificationSupport.onMain(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return keypad.isShowing();
            }
        }));
    }

    @Test
    public void reverseWithoutSessionStillDismissesOverlayDialog() throws Exception {
        service = createdService();
        final Dialog keypad = QualificationSupport.onMain(new Callable<Dialog>() {
            @Override
            public Dialog call() throws Exception {
                PlusCodeDialog.show(service, true);
                return activePlusCodeDialog();
            }
        });
        assertNotNull(keypad);
        QualificationSupport.onMain(new Runnable() {
            @Override
            public void run() {
                service.onReverseStateChanged(MediaState.ReverseState.ACTIVE);
            }
        });
        assertNull(activePlusCodeDialog());
        assertFalse(service.hasOverlayWindows());
    }

    @Test
    public void integrationHealthFailureHidesHud() throws Exception {
        service = showingService();
        QualificationSupport.onMain(new Runnable() {
            @Override
            public void run() {
                service.onHealthChanged(HeadUnitAdapter.Health.FAILED, "qualification test");
            }
        });
        assertClosed(service);
    }

    @Test
    public void firmwareChangeInvalidatesOpenSession() throws Exception {
        service = showingService();
        setField(service, "sessionKey", "firmware-before-update");
        sendCommand(service, OverlayHudService.ACTION_SHOW_COLLAPSED);
        assertClosed(service);
    }

    @Test
    public void overlayPermissionRevocationInvalidatesOpenSession() throws Exception {
        service = showingService();
        QualificationSupport.setOverlayMode("ignore");
        assumeTrue("the device kept the overlay permission granted",
                !OverlayHudService.canDrawOverlays(QualificationSupport.targetContext()));
        // No listener observes revocation while idle; the next interaction closes the session.
        sendCommand(service, OverlayHudService.ACTION_SHOW_COLLAPSED);
        assertClosed(service);
    }

    @Test
    public void routerDenyWhileShowingRemovesHud() throws Exception {
        service = showingService();
        final HudSessionPolicy aaos = new HudSessionPolicy(EnvironmentFacts.PLATFORM_AAOS,
                HeadUnitAdapter.ReverseSupport.VERIFIED);
        final int[] started = new int[1];
        final HudSessionPolicy.Reason[] explained = new HudSessionPolicy.Reason[1];
        QualificationSupport.onMain(new Runnable() {
            @Override
            public void run() {
                CarIntentRouter.gatedOverlay(new CarIntentRouter.HudGate() {
                    @Override
                    public HudSessionPolicy.Decision requestShow() {
                        return aaos.requestShow(readyOverlay(), MediaState.ReverseState.INACTIVE,
                                true);
                    }

                    @Override
                    public void explainDenial(HudSessionPolicy.Reason reason) {
                        explained[0] = reason;
                    }
                }, new CarIntentRouter.Overlay() {
                    @Override
                    public void show() {
                        started[0]++;
                    }
                }, new CarIntentRouter.Overlay() {
                    @Override
                    public void show() {
                        // What stopOverlayService delivers to a running service.
                        service.onStartCommand(new Intent(OverlayHudService.ACTION_HIDE), 0, 2);
                    }
                }).show();
            }
        });
        assertEquals(0, started[0]);
        assertSame(HudSessionPolicy.Reason.AAOS_PLATFORM, explained[0]);
        assertClosed(service);
    }

    private static void assertClosed(TestHudService service) {
        assertFalse("HUD windows remain", QualificationSupport.onMain(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return service.hasOverlayWindows();
            }
        }));
        assertTrue("the service was not stopped", service.ended > 0);
        assertNull(activePlusCodeDialog());
    }

    /** A service created like the system would, with no session opened yet. */
    private static TestHudService createdService() {
        return QualificationSupport.onMain(new Callable<TestHudService>() {
            @Override
            public TestHudService call() {
                TestHudService created = new TestHudService();
                created.attach(QualificationSupport.targetContext());
                created.onCreate();
                return created;
            }
        });
    }

    /** A service with a granted, expanded HUD session and attached overlay windows. */
    private static TestHudService showingService() throws Exception {
        final TestHudService showing = createdService();
        final HudSessionPolicy policy = new HudSessionPolicy(
                EnvironmentFacts.PLATFORM_AFTERMARKET, HeadUnitAdapter.ReverseSupport.VERIFIED);
        setField(showing, "policy", policy);
        final HudSessionPolicy.Decision granted = policy.requestShow(readyOverlay(),
                MediaState.ReverseState.INACTIVE, true);
        assertSame(HudSessionPolicy.Action.SHOW_EXPANDED, granted.action);
        QualificationSupport.onMain(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                Method apply = OverlayHudService.class.getDeclaredMethod("apply",
                        HudSessionPolicy.Decision.class);
                apply.setAccessible(true);
                apply.invoke(showing, granted);
                return null;
            }
        });
        assertTrue("HUD windows were not attached", showing.hasOverlayWindows());
        assertEquals(0, showing.ended);
        return showing;
    }

    private static void sendCommand(final TestHudService service, final String action) {
        QualificationSupport.onMain(new Runnable() {
            @Override
            public void run() {
                service.onStartCommand(new Intent(action), 0, 1);
            }
        });
    }

    static CapabilityRegistry.CapabilityStatus readyOverlay() {
        Map<String, String> deps = new HashMap<>();
        deps.put(CapabilityRegistry.KEY_ENVIRONMENT, "qualification");
        deps.put(CapabilityRegistry.KEY_SAFETY_PROFILE, "qualification");
        deps.put(CapabilityRegistry.KEY_PERMISSION_OVERLAY, "granted");
        return CapabilityRegistry.evaluate(CapabilityRegistry.Capability.OVERLAY,
                CapabilityRegistry.Evidence.verified(deps),
                new CapabilityRegistry.RuntimeConditions(CapabilityRegistry.Availability.AVAILABLE,
                        deps, Collections.<String>emptyList(), false, false));
    }

    private static Dialog activePlusCodeDialog() {
        try {
            Field field = PlusCodeDialog.class.getDeclaredField("activeDialog");
            field.setAccessible(true);
            return (Dialog) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = OverlayHudService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** Records stop requests; Service#stopSelf is a no-op for an instance not started by AMS. */
    static final class TestHudService extends OverlayHudService {
        int ended;

        void attach(Context base) {
            attachBaseContext(base);
        }

        @Override
        void endService() {
            ended++;
        }
    }
}
