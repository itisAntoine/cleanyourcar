package com.cleanyourcar.launcher;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared helpers for the device qualification tests. These tests only compile in CI; see
 * docs/compatibility.md for which of them have actually been run on a device.
 */
final class QualificationSupport {

    private static final String OVERLAY_OP = "SYSTEM_ALERT_WINDOW";

    private QualificationSupport() {
    }

    static Instrumentation instrumentation() {
        return InstrumentationRegistry.getInstrumentation();
    }

    static Context targetContext() {
        return instrumentation().getTargetContext();
    }

    /** Runs a shell command as the shell user and returns its output once it finished. */
    static String shell(String command) throws IOException {
        ParcelFileDescriptor pfd = instrumentation().getUiAutomation()
                .executeShellCommand(command);
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            pfd.close();
        }
    }

    /** Current app-op mode of the overlay permission, e.g. "allow", "ignore" or "default". */
    static String overlayMode() throws IOException {
        String output = shell("appops get " + targetContext().getPackageName() + " "
                + OVERLAY_OP);
        int start = output.indexOf(OVERLAY_OP + ": ");
        if (start < 0) {
            return "default";
        }
        start += OVERLAY_OP.length() + 2;
        int end = start;
        while (end < output.length() && Character.isLetter(output.charAt(end))) {
            end++;
        }
        return end > start ? output.substring(start, end) : "default";
    }

    /**
     * Grants or denies "display over other apps" through app-ops. Unlike revoking a runtime
     * permission, this does not kill the instrumented process.
     */
    static void setOverlayMode(String mode) throws IOException {
        shell("appops set " + targetContext().getPackageName() + " " + OVERLAY_OP + " " + mode);
        long deadline = System.currentTimeMillis() + 2000L;
        boolean wanted = "allow".equals(mode);
        while (System.currentTimeMillis() < deadline
                && OverlayHudService.canDrawOverlays(targetContext()) != wanted) {
            sleep(50L);
        }
    }

    static byte[] readTestAsset(String name) throws IOException {
        try (InputStream in = instrumentation().getContext().getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /** Runs {@code task} on the main thread and returns its result or rethrows its failure. */
    static <T> T onMain(final Callable<T> task) {
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    result.set(task.call());
                } catch (Throwable t) {
                    failure.set(t);
                }
            }
        });
        Throwable t = failure.get();
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        if (t != null) {
            throw new AssertionError(t);
        }
        instrumentation().waitForIdleSync();
        return result.get();
    }

    static void onMain(final Runnable task) {
        onMain(new Callable<Void>() {
            @Override
            public Void call() {
                task.run();
                return null;
            }
        });
    }

    /** Marks first-run setup as seen; returns the previous value so it can be restored. */
    static boolean markSetupSeen(boolean seen) {
        SharedPreferences prefs = targetContext().getSharedPreferences(SetupActivity.PREFS,
                Context.MODE_PRIVATE);
        boolean previous = prefs.getBoolean(SetupActivity.KEY_SETUP_SEEN, false);
        prefs.edit().putBoolean(SetupActivity.KEY_SETUP_SEEN, seen).commit();
        return previous;
    }

    static MainActivity launchMain() {
        Intent intent = new Intent(targetContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) instrumentation().startActivitySync(intent);
        instrumentation().waitForIdleSync();
        return activity;
    }

    static void finish(final Activity activity) {
        if (activity != null) {
            onMain(new Runnable() {
                @Override
                public void run() {
                    activity.finish();
                }
            });
        }
    }

    /** Visible views that react to a tap or long press. */
    static List<View> actionViews(View root) {
        List<View> found = new ArrayList<>();
        collect(root, found);
        return found;
    }

    private static void collect(View view, List<View> found) {
        if (!view.isShown()) {
            return;
        }
        if (view.isClickable() || view.isLongClickable()) {
            found.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), found);
            }
        }
    }

    /**
     * Scrolls {@code target} into view through its scrolling ancestors and asserts that at
     * least half of it (or all of it, when it is small) is then visible inside {@code root}.
     * Must be called on the main thread after layout.
     */
    static void assertReachable(String label, View root, View target) {
        target.requestRectangleOnScreen(new Rect(0, 0, target.getWidth(), target.getHeight()),
                true);
        Rect visible = new Rect();
        boolean shown = target.getGlobalVisibleRect(visible);
        Rect rootRect = new Rect();
        root.getGlobalVisibleRect(rootRect);
        assertTrue(label + ": " + describe(target) + " is not visible after scrolling", shown);
        assertTrue(label + ": " + describe(target) + " lies outside its window " + rootRect
                + " at " + visible, rootRect.contains(visible));
        assertTrue(label + ": only " + visible.height() + "px of " + describe(target)
                        + " (" + target.getHeight() + "px) can be brought into view",
                visible.height() * 2 >= target.getHeight());
        assertTrue(label + ": only " + visible.width() + "px of " + describe(target)
                        + " (" + target.getWidth() + "px) can be brought into view",
                visible.width() * 2 >= target.getWidth());
    }

    static String describe(View view) {
        CharSequence description = view.getContentDescription();
        if (description == null && view instanceof android.widget.TextView) {
            description = ((android.widget.TextView) view).getText();
        }
        return view.getClass().getSimpleName()
                + (description != null ? "\"" + description + "\"" : "#" + view.getId());
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
