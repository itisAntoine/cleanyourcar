package com.cleanyourcar.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.annotation.TargetApi;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.cleanyourcar.launcher.layout.LayoutPolicy;

import org.junit.After;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Adaptive layout qualification: in every window fixture, dialogs stay inside the window and
 * every action in them (and in the launcher itself) can be brought into view and tapped.
 *
 * <p>Fixtures are applied as configuration overrides on top of the device's real window, so
 * they exercise the layout policy and the dialog bounding code with the fixture's size,
 * density and font scale. Physical-panel effects (cutouts, OEM bars) still need a device run.
 *
 * <p>A dialog window cannot be larger than the host window, so a window-backed fixture is only
 * checked when the host activity window is at least the fixture's pixel size. Fixtures that do
 * not fit are checked as far as possible and then the test is reported as skipped (assumption
 * failure) naming them. Covering every fixture needs a host window of at least 1920x1280 px
 * (for example a 2560x1600 tablet AVD); a 1024x600 AVD covers only the fixtures up to that size.
 */
@RunWith(AndroidJUnit4.class)
public class AdaptiveLayoutTest {

    private static final int SETTINGS_DIALOG_WIDTH_DP = 650;
    private static final int PLUS_CODE_DIALOG_WIDTH_DP = 620;

    /** A window size, in pixels at a density, as seen by the app. */
    static final class Fixture {
        final String name;
        final int widthPx;
        final int heightPx;
        final int densityDpi;
        final float fontScale;

        Fixture(String name, int widthPx, int heightPx, int densityDpi, float fontScale) {
            this.name = name;
            this.widthPx = widthPx;
            this.heightPx = heightPx;
            this.densityDpi = densityDpi;
            this.fontScale = fontScale;
        }

        int widthDp() {
            return Math.round(widthPx * 160f / densityDpi);
        }

        int heightDp() {
            return Math.round(heightPx * 160f / densityDpi);
        }

        Configuration apply(Configuration base) {
            Configuration config = new Configuration(base);
            config.screenWidthDp = widthDp();
            config.screenHeightDp = heightDp();
            config.smallestScreenWidthDp = Math.min(widthDp(), heightDp());
            config.densityDpi = densityDpi;
            config.fontScale = fontScale;
            config.orientation = widthDp() >= heightDp()
                    ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
            return config;
        }

        Fixture rotated() {
            return new Fixture(name + " rotated", heightPx, widthPx, densityDpi, fontScale);
        }

        @Override
        public String toString() {
            return name + " (" + widthDp() + "x" + heightDp() + "dp, font " + fontScale + ")";
        }
    }

    static final List<Fixture> FIXTURES = Arrays.asList(
            new Fixture("800x480 mdpi", 800, 480, 160, 1f),
            new Fixture("1024x600 mdpi", 1024, 600, 160, 1f),
            new Fixture("1280x720 xhdpi", 1280, 720, 320, 1f),
            new Fixture("1920x720 ultrawide", 1920, 720, 160, 1f),
            new Fixture("720x1280 portrait xhdpi", 720, 1280, 320, 1f),
            new Fixture("320dp split screen", 320, 600, 160, 1f),
            // 1024x600 minus a 24dp status bar, a 48dp navigation bar and a 40dp side cutout.
            new Fixture("1024x600 inset-constrained", 984, 528, 160, 1f),
            new Fixture("800x480 font 2.0", 800, 480, 160, 2f),
            new Fixture("1024x600 font 2.0", 1024, 600, 160, 2f));

    private boolean setupSeenBefore;
    private MainActivity activity;

    @Before
    public void setUp() {
        setupSeenBefore = QualificationSupport.markSetupSeen(true);
        activity = QualificationSupport.launchMain();
    }

    @After
    public void tearDown() {
        QualificationSupport.onMain(new Runnable() {
            @Override
            public void run() {
                PlusCodeDialog.dismissActive();
                if (activity != null) {
                    activity.setRequestedOrientation(
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                }
            }
        });
        QualificationSupport.finish(activity);
        QualificationSupport.markSetupSeen(setupSeenBefore);
    }

    @Test
    public void layoutPolicyKeepsTouchTargetsAndBoundsInEveryFixture() {
        for (Fixture fixture : FIXTURES) {
            LayoutPolicy.LayoutSpec spec = AdaptiveUi.specFor(fixtureContext(fixture),
                    fixture.apply(activity.getResources().getConfiguration()));
            String label = fixture + ": " + spec;
            int min = LayoutPolicy.MIN_TOUCH_TARGET_DP;
            assertTrue(label, spec.dockButtonDp >= min);
            assertTrue(label, spec.dockToggleDp >= min);
            assertTrue(label, spec.transportHeightDp >= min);
            assertTrue(label, spec.transportButtonWidthDp >= min);
            assertTrue(label, spec.actionRowMinHeightDp >= min);
            assertTrue(label, spec.navButtonHeightDp >= min);
            assertTrue(label, spec.dialogWidthDp(SETTINGS_DIALOG_WIDTH_DP)
                    <= fixture.widthDp() - 2 * LayoutPolicy.DIALOG_MARGIN_DP);
            assertTrue(label, spec.dialogMaxHeightDp
                    <= fixture.heightDp() - 2 * LayoutPolicy.DIALOG_MARGIN_DP);
            int columns = spec.keypadColumns(PLUS_CODE_DIALOG_WIDTH_DP);
            int keyWidth = (spec.dialogWidthDp(PLUS_CODE_DIALOG_WIDTH_DP)
                    - 2 * LayoutPolicy.KEYPAD_PADDING_DP) / columns
                    - 2 * LayoutPolicy.KEYPAD_KEY_MARGIN_DP;
            assertTrue(label + " keypad key " + keyWidth + "dp", keyWidth >= min);
            if (spec.dockHeightDp > fixture.heightDp() - 2 * spec.marginDp) {
                assertTrue(label + " dock does not fit but does not scroll", spec.dockScrolls);
            }
            if (spec.dashboardHeightDp > fixture.heightDp()) {
                assertTrue(label + " dashboard does not fit but does not scroll",
                        spec.dashboardScrolls);
            }
        }
    }

    @Test
    public void boundedDialogKeepsBottomActionReachableInEveryFixture() {
        List<Fixture> skipped = new ArrayList<>();
        for (final Fixture fixture : FIXTURES) {
            if (!hostFits(fixture)) {
                skipped.add(fixture);
                continue;
            }
            final Dialog dialog = showTallDialog(fixture, true);
            try {
                assertDialogWithinFixture(fixture, dialog, SetupUi.DIALOG_WIDTH_DP);
                assertActionsReachable(fixture.toString(), dialog.getWindow().getDecorView());
            } finally {
                dismiss(dialog);
            }
            final Dialog ownScroller = showTallDialog(fixture, false);
            try {
                assertActionsReachable(fixture + " (own scroller)",
                        ownScroller.getWindow().getDecorView());
            } finally {
                dismiss(ownScroller);
            }
        }
        assumeAllFitted(skipped);
    }

    @Test
    public void plusCodeKeypadStaysReachableInEveryFixture() {
        List<Fixture> skipped = new ArrayList<>();
        for (final Fixture fixture : FIXTURES) {
            if (!hostFits(fixture)) {
                skipped.add(fixture);
                continue;
            }
            Dialog keypad = QualificationSupport.onMain(new Callable<Dialog>() {
                @Override
                public Dialog call() throws Exception {
                    PlusCodeDialog.show(fixtureContext(fixture), false);
                    Field field = PlusCodeDialog.class.getDeclaredField("activeDialog");
                    field.setAccessible(true);
                    return (Dialog) field.get(null);
                }
            });
            assertNotNull(fixture + ": keypad not shown", keypad);
            try {
                assertDialogWithinFixture(fixture, keypad, PLUS_CODE_DIALOG_WIDTH_DP);
                assertActionsReachable(fixture + " keypad", keypad.getWindow().getDecorView());
            } finally {
                QualificationSupport.onMain(new Runnable() {
                    @Override
                    public void run() {
                        PlusCodeDialog.dismissActive();
                    }
                });
            }
        }
        assumeAllFitted(skipped);
    }

    @Test
    @TargetApi(29)
    public void settingsDialogActionsStayReachableInEveryFixture() {
        assumeTrue("window inspection needs API 29", Build.VERSION.SDK_INT >= 29);
        List<Fixture> skipped = new ArrayList<>();
        for (final Fixture fixture : FIXTURES) {
            if (!hostFits(fixture)) {
                skipped.add(fixture);
                continue;
            }
            final List<View> before = rootViews();
            QualificationSupport.onMain(new Runnable() {
                @Override
                public void run() {
                    LauncherSettingsDialog.show(fixtureContext(fixture),
                            new LauncherSettingsDialog.OnSettingsChangedListener() {
                                @Override
                                public void onSettingsChanged() {
                                }
                            });
                }
            });
            List<View> added = dialogDecors(rootViews());
            added.removeAll(before);
            assertEquals(fixture + ": settings dialog windows", 1, added.size());
            View decor = added.get(0);
            try {
                assertRootWithinFixture(fixture, decor);
                assertActionsReachable(fixture + " settings", decor);
            } finally {
                closeWindow(decor);
            }
        }
        assumeAllFitted(skipped);
    }

    @Test
    public void launcherActionsStayReachableInCurrentWindow() {
        QualificationSupport.onMain(new Runnable() {
            @Override
            public void run() {
                View root = activity.getWindow().getDecorView();
                List<View> actions = QualificationSupport.actionViews(root);
                assertFalse("the launcher shows no actions", actions.isEmpty());
                boolean settingsEntry = false;
                for (View action : actions) {
                    QualificationSupport.assertReachable("launcher", root, action);
                    settingsEntry |= action.isLongClickable();
                }
                assertTrue("no settings entry (long-press action) is reachable",
                        settingsEntry);
            }
        });
    }

    @Test
    public void openDialogIsReboundedOnRotation() {
        final Fixture landscape = FIXTURES.get(1);
        final Fixture portrait = landscape.rotated();
        assumeTrue("host window smaller than " + portrait,
                hostFits(landscape) && hostFits(portrait));
        final List<LayoutPolicy.LayoutSpec> bounds = new ArrayList<>();
        final RotatableContext[] host = new RotatableContext[1];
        final Dialog dialog = QualificationSupport.onMain(new Callable<Dialog>() {
            @Override
            public Dialog call() {
                host[0] = new RotatableContext(activity, landscape);
                Dialog shown = new Dialog(host[0]);
                AdaptiveUi.showBounded(shown, tallContent(shown.getContext()),
                        SETTINGS_DIALOG_WIDTH_DP, true, new AdaptiveUi.OnBoundsChanged() {
                            @Override
                            public void onBounds(LayoutPolicy.LayoutSpec spec) {
                                bounds.add(spec);
                            }
                        });
                return shown;
            }
        });
        try {
            QualificationSupport.instrumentation().waitForIdleSync();
            assertFalse(bounds.isEmpty());
            assertEquals(landscape.heightDp() - 2 * LayoutPolicy.DIALOG_MARGIN_DP,
                    bounds.get(bounds.size() - 1).dialogMaxHeightDp);
            final int beforeRotation = bounds.size();
            // The dialog's resources now report the rotated configuration, as after a real
            // rotation, so the layout-change recheck agrees with the configuration callback.
            QualificationSupport.onMain(new Runnable() {
                @Override
                public void run() {
                    Configuration rotated = host[0].rotateTo(portrait);
                    dialog.getWindow().getDecorView().dispatchConfigurationChanged(rotated);
                }
            });
            QualificationSupport.instrumentation().waitForIdleSync();
            assertTrue("rotation did not re-bound the open dialog",
                    bounds.size() > beforeRotation);
            LayoutPolicy.LayoutSpec spec = bounds.get(bounds.size() - 1);
            assertEquals(portrait.widthDp() - 2 * LayoutPolicy.DIALOG_MARGIN_DP,
                    spec.dialogWidthDp(SETTINGS_DIALOG_WIDTH_DP));
            assertEquals(portrait.heightDp() - 2 * LayoutPolicy.DIALOG_MARGIN_DP,
                    spec.dialogMaxHeightDp);
            WindowManager.LayoutParams attrs = dialog.getWindow().getAttributes();
            assertEquals(AdaptiveUi.dp(dialog.getContext(),
                    spec.dialogWidthDp(SETTINGS_DIALOG_WIDTH_DP)), attrs.width);
            assertActionsReachable("rotated", dialog.getWindow().getDecorView());
        } finally {
            dismiss(dialog);
        }
    }

    @Test
    public void openDialogStaysReachableAcrossRealRotation() {
        PackageManager pm = activity.getPackageManager();
        assumeTrue("device cannot rotate",
                pm.hasSystemFeature(PackageManager.FEATURE_SCREEN_LANDSCAPE)
                        && pm.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT));
        final Dialog dialog = showTallDialog(null, true);
        try {
            for (final int orientation : new int[] {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
                QualificationSupport.onMain(new Runnable() {
                    @Override
                    public void run() {
                        activity.setRequestedOrientation(orientation);
                    }
                });
                QualificationSupport.sleep(1500L);
                QualificationSupport.instrumentation().waitForIdleSync();
                assertActionsReachable("orientation " + orientation,
                        dialog.getWindow().getDecorView());
                assertDecorInsideVisibleArea(dialog);
            }
        } finally {
            dismiss(dialog);
        }
    }

    @Test
    public void dialogStaysInsideSystemBarsUnderEdgeToEdge() {
        assumeTrue("edge-to-edge is enforced from API 35", Build.VERSION.SDK_INT >= 35);
        final Dialog dialog = showTallDialog(null, true);
        try {
            assertDecorInsideVisibleArea(dialog);
            assertActionsReachable("edge-to-edge", dialog.getWindow().getDecorView());
            // The policy itself must leave room for the bars, not only the window manager.
            QualificationSupport.onMain(new Runnable() {
                @Override
                public void run() {
                    LayoutPolicy.LayoutSpec spec = AdaptiveUi.specFor(dialog.getContext());
                    float density = dialog.getContext().getResources()
                            .getDisplayMetrics().density;
                    int safeHeightDp = (int) (insetSafeArea().height() / density);
                    assertTrue("dialog max height " + spec.dialogMaxHeightDp
                                    + "dp exceeds the inset-safe height " + safeHeightDp + "dp",
                            spec.dialogMaxHeightDp <= safeHeightDp);
                }
            });
        } finally {
            dismiss(dialog);
        }
    }

    /** Dialog content never overlaps the status or navigation bars of the activity window. */
    private void assertDecorInsideVisibleArea(final Dialog dialog) {
        QualificationSupport.onMain(new Runnable() {
            @Override
            public void run() {
                Rect safe = insetSafeArea();
                View content = dialog.getWindow().findViewById(Window.ID_ANDROID_CONTENT);
                int[] location = new int[2];
                content.getLocationOnScreen(location);
                Rect dialogRect = new Rect(location[0], location[1],
                        location[0] + content.getWidth(), location[1] + content.getHeight());
                assertTrue("dialog " + dialogRect + " overlaps system bars, safe " + safe,
                        safe.contains(dialogRect));
            }
        });
    }

    /** Whether the host activity window can hold a dialog of the fixture's pixel size. */
    private boolean hostFits(final Fixture fixture) {
        return QualificationSupport.onMain(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                View decor = activity.getWindow().getDecorView();
                return decor.getWidth() >= fixture.widthPx && decor.getHeight() >= fixture.heightPx;
            }
        });
    }

    private static void assumeAllFitted(List<Fixture> skipped) {
        if (!skipped.isEmpty()) {
            throw new AssumptionViolatedException("host window smaller than fixtures " + skipped
                    + "; run on a host window of at least 1920x1280 px to cover them");
        }
    }

    /** The activity window minus system bars and display cutout (main thread only). */
    private Rect insetSafeArea() {
        View activityDecor = activity.getWindow().getDecorView();
        Rect safe = new Rect();
        activityDecor.getGlobalVisibleRect(safe);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets insets = activityDecor.getRootWindowInsets();
            if (insets != null) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                safe.left += bars.left;
                safe.top += bars.top;
                safe.right -= bars.right;
                safe.bottom -= bars.bottom;
            }
        }
        return safe;
    }

    /**
     * A themed context with its own Resources instance whose configuration can be switched in
     * place. Dialog wraps its context and caches that Resources object, so updating it is what
     * makes the dialog's later configuration reads see a rotation.
     */
    private static final class RotatableContext extends ContextThemeWrapper {
        private final Resources resources;

        @SuppressWarnings("deprecation")
        RotatableContext(Context base, Fixture fixture) {
            super(base, android.R.style.Theme_Material_NoActionBar);
            Resources hostResources = base.getResources();
            resources = new Resources(hostResources.getAssets(), metricsFor(hostResources, fixture),
                    fixture.apply(hostResources.getConfiguration()));
        }

        @SuppressWarnings("deprecation")
        Configuration rotateTo(Fixture fixture) {
            Configuration config = fixture.apply(resources.getConfiguration());
            resources.updateConfiguration(config, metricsFor(resources, fixture));
            return config;
        }

        @Override
        public Resources getResources() {
            return resources;
        }

        private static DisplayMetrics metricsFor(Resources from, Fixture fixture) {
            DisplayMetrics metrics = new DisplayMetrics();
            metrics.setTo(from.getDisplayMetrics());
            metrics.densityDpi = fixture.densityDpi;
            metrics.density = fixture.densityDpi / 160f;
            metrics.scaledDensity = metrics.density * fixture.fontScale;
            metrics.widthPixels = fixture.widthPx;
            metrics.heightPixels = fixture.heightPx;
            return metrics;
        }
    }

    private ContextThemeWrapper fixtureContext(Fixture fixture) {
        ContextThemeWrapper context = new ContextThemeWrapper(activity,
                android.R.style.Theme_Material_NoActionBar);
        if (fixture != null) {
            context.applyOverrideConfiguration(
                    fixture.apply(activity.getResources().getConfiguration()));
        }
        return context;
    }

    /** A dialog taller than any fixture, ending in a confirm button, like the setup dialogs. */
    private Dialog showTallDialog(final Fixture fixture, final boolean scrollWhole) {
        return QualificationSupport.onMain(new Callable<Dialog>() {
            @Override
            public Dialog call() {
                Dialog dialog = SetupUi.newDialog(fixtureContext(fixture));
                View content = tallContent(dialog.getContext());
                if (!scrollWhole) {
                    LinearLayout withHeader = new LinearLayout(dialog.getContext());
                    withHeader.setOrientation(LinearLayout.VERTICAL);
                    Button close = new Button(dialog.getContext());
                    close.setText("Close");
                    withHeader.addView(close);
                    withHeader.addView(AdaptiveUi.fillingScroll(dialog.getContext(), content),
                            new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
                    content = withHeader;
                }
                SetupUi.showDialog(dialog, content, scrollWhole);
                return dialog;
            }
        });
    }

    private static View tallContent(android.content.Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 40; i++) {
            TextView line = new TextView(context);
            line.setText("Line " + i);
            column.addView(line);
        }
        Button confirm = new Button(context);
        confirm.setText("Confirm");
        column.addView(confirm);
        return column;
    }

    private static void assertDialogWithinFixture(final Fixture fixture, final Dialog dialog,
            final int preferredWidthDp) {
        QualificationSupport.onMain(new Runnable() {
            @Override
            public void run() {
                LayoutPolicy.LayoutSpec spec = AdaptiveUi.specFor(dialog.getContext());
                assertEquals(fixture + " dialog width",
                        AdaptiveUi.dp(dialog.getContext(), spec.dialogWidthDp(preferredWidthDp)),
                        dialog.getWindow().getAttributes().width);
                assertRootWithinFixture(fixture, dialog.getWindow().getDecorView());
            }
        });
    }

    private static void assertRootWithinFixture(final Fixture fixture, final View decor) {
        QualificationSupport.onMain(new Runnable() {
            @Override
            public void run() {
                View content = decor.findViewById(Window.ID_ANDROID_CONTENT);
                View measured = content != null ? content : decor;
                float density = fixture.densityDpi / 160f;
                int maxHeightPx = Math.round((fixture.heightDp()
                        - 2 * LayoutPolicy.DIALOG_MARGIN_DP) * density);
                int maxWidthPx = Math.round((fixture.widthDp()
                        - 2 * LayoutPolicy.DIALOG_MARGIN_DP) * density);
                assertTrue(fixture + ": dialog height " + measured.getHeight() + " > "
                        + maxHeightPx, measured.getHeight() <= maxHeightPx + 1);
                assertTrue(fixture + ": dialog width " + measured.getWidth() + " > "
                        + maxWidthPx, measured.getWidth() <= maxWidthPx + 1);
            }
        });
    }

    private static void assertActionsReachable(final String label, final View root) {
        QualificationSupport.onMain(new Runnable() {
            @Override
            public void run() {
                List<View> actions = QualificationSupport.actionViews(root);
                assertFalse(label + ": no actions found", actions.isEmpty());
                for (View action : actions) {
                    QualificationSupport.assertReachable(label, root, action);
                }
            }
        });
    }

    private static void dismiss(final Dialog dialog) {
        QualificationSupport.onMain(new Runnable() {
            @Override
            public void run() {
                if (dialog.isShowing()) {
                    dialog.dismiss();
                }
            }
        });
    }

    @TargetApi(29)
    private static List<View> rootViews() {
        return QualificationSupport.onMain(new Callable<List<View>>() {
            @Override
            public List<View> call() {
                return new ArrayList<>(WindowInspector.getGlobalWindowViews());
            }
        });
    }

    /** Only dialog decor windows: TYPE_APPLICATION windows that host a content frame. */
    private static List<View> dialogDecors(List<View> roots) {
        List<View> dialogs = new ArrayList<>();
        for (View root : roots) {
            if (root.getLayoutParams() instanceof WindowManager.LayoutParams
                    && ((WindowManager.LayoutParams) root.getLayoutParams()).type
                            == WindowManager.LayoutParams.TYPE_APPLICATION
                    && root.findViewById(Window.ID_ANDROID_CONTENT) != null) {
                dialogs.add(root);
            }
        }
        return dialogs;
    }

    /** Closes a dialog window found by inspection (its Dialog object is not exposed). */
    private static void closeWindow(final View decor) {
        QualificationSupport.onMain(new Runnable() {
            @Override
            public void run() {
                if (decor.isAttachedToWindow()) {
                    decor.dispatchKeyEvent(new android.view.KeyEvent(
                            android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));
                    decor.dispatchKeyEvent(new android.view.KeyEvent(
                            android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
                }
            }
        });
    }
}
