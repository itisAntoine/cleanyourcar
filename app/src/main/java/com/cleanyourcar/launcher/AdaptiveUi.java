package com.cleanyourcar.launcher;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import com.cleanyourcar.launcher.layout.LayoutPolicy;

/** Android glue for {@link LayoutPolicy}: dp conversion, window metrics, bounded dialogs. */
final class AdaptiveUi {

    /** Delay that coalesces bursts of resize/inset callbacks into a single refit. */
    static final long REFIT_DEBOUNCE_MS = 100L;

    private AdaptiveUi() {
    }

    /** Receives the dialog bounds whenever they are (re)computed for the current window. */
    interface OnBoundsChanged {
        void onBounds(LayoutPolicy.LayoutSpec spec);
    }

    static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    static float dpf(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    /** Policy for the context's current window configuration (before a view is measured). */
    static LayoutPolicy.LayoutSpec specFor(Context context) {
        return specFor(context, context.getResources().getConfiguration());
    }

    /** Policy for an explicit configuration, e.g. one delivered by a configuration change. */
    static LayoutPolicy.LayoutSpec specFor(Context context, Configuration config) {
        int widthDp = config.screenWidthDp;
        int heightDp = config.screenHeightDp;
        if (widthDp <= 0 || heightDp <= 0) {
            float density = context.getResources().getDisplayMetrics().density;
            widthDp = Math.round(context.getResources().getDisplayMetrics().widthPixels / density);
            heightDp = Math.round(context.getResources().getDisplayMetrics().heightPixels / density);
        }
        return LayoutPolicy.calculate(widthDp, heightDp, config.fontScale,
                ThemeConfig.getGridColumns(context));
    }

    /** Policy for a measured view, excluding its padding (which carries the applied insets). */
    static LayoutPolicy.LayoutSpec specFor(Context context, View host) {
        float density = context.getResources().getDisplayMetrics().density;
        int widthPx = host.getWidth() - host.getPaddingLeft() - host.getPaddingRight();
        int heightPx = host.getHeight() - host.getPaddingTop() - host.getPaddingBottom();
        return LayoutPolicy.calculate(Math.round(widthPx / density),
                Math.round(heightPx / density),
                context.getResources().getConfiguration().fontScale,
                ThemeConfig.getGridColumns(context));
    }

    /** Pads {@code host} by the system-bar and cutout insets it actually receives. */
    static void applySystemInsetsAsPadding(View host, final Runnable onChanged) {
        host.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left;
                int top;
                int right;
                int bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                    bottom = bars.bottom;
                } else {
                    left = legacyInset(insets, 0);
                    top = legacyInset(insets, 1);
                    right = legacyInset(insets, 2);
                    bottom = legacyInset(insets, 3);
                }
                if (v.getPaddingLeft() != left || v.getPaddingTop() != top
                        || v.getPaddingRight() != right || v.getPaddingBottom() != bottom) {
                    v.setPadding(left, top, right, bottom);
                    if (onChanged != null) {
                        v.removeCallbacks(onChanged);
                        v.postDelayed(onChanged, REFIT_DEBOUNCE_MS);
                    }
                }
                return insets;
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static int legacyInset(WindowInsets insets, int side) {
        switch (side) {
            case 0: return insets.getSystemWindowInsetLeft();
            case 1: return insets.getSystemWindowInsetTop();
            case 2: return insets.getSystemWindowInsetRight();
            default: return insets.getSystemWindowInsetBottom();
        }
    }

    /** A vertical scroller that stretches short content to the viewport (weights still apply). */
    static ScrollView fillingScroll(Context context, View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(child, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /**
     * Shows {@code root} in a transparent dialog whose width is the preferred width bounded by
     * the window and whose height never exceeds the window. With {@code scrollWhole} the entire
     * content scrolls; otherwise {@code root} must contain its own scroller.
     */
    static void showBounded(Dialog dialog, View root, int preferredWidthDp, boolean scrollWhole) {
        showBounded(dialog, root, preferredWidthDp, scrollWhole, null);
    }

    /**
     * Like {@link #showBounded(Dialog, View, int, boolean)}, and re-applies the width and height
     * bounds whenever the window is rotated or resized while the dialog is open. {@code onBounds}
     * is called once before showing and again after every change of the computed bounds.
     */
    static void showBounded(final Dialog dialog, View root, final int preferredWidthDp,
            boolean scrollWhole, final OnBoundsChanged onBounds) {
        final Context context = dialog.getContext();
        final MaxHeightFrame frame = new MaxHeightFrame(context);
        final LayoutPolicy.LayoutSpec[] applied = new LayoutPolicy.LayoutSpec[1];
        final ConfigListener rebound = new ConfigListener() {
            @Override
            public void onConfiguration(Configuration config) {
                LayoutPolicy.LayoutSpec spec = specFor(context, config);
                if (spec.equals(applied[0])) return;
                applied[0] = spec;
                frame.setMaxHeightPx(dp(context, spec.dialogMaxHeightDp));
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setLayout(dp(context, spec.dialogWidthDp(preferredWidthDp)),
                            WindowManager.LayoutParams.WRAP_CONTENT);
                }
                if (onBounds != null) onBounds.onBounds(spec);
            }
        };
        frame.setConfigListener(rebound);
        View content = root;
        if (scrollWhole) {
            ScrollView scroll = new ScrollView(context);
            scroll.addView(root);
            content = scroll;
        }
        frame.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(frame);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
        }
        rebound.onConfiguration(context.getResources().getConfiguration());
        dialog.show();
        if (dialog.getWindow() != null) {
            // Fallback for resizes that reach the dialog without a configuration callback
            // (e.g. multi-window bounds changes within the same configuration bucket).
            dialog.getWindow().getDecorView().addOnLayoutChangeListener(
                    new View.OnLayoutChangeListener() {
                        @Override
                        public void onLayoutChange(View v, int l, int t, int r, int b,
                                int ol, int ot, int or, int ob) {
                            v.removeCallbacks(recheck);
                            v.post(recheck);
                        }

                        private final Runnable recheck = new Runnable() {
                            @Override
                            public void run() {
                                rebound.onConfiguration(
                                        context.getResources().getConfiguration());
                            }
                        };
                    });
        }
    }

    interface ConfigListener {
        void onConfiguration(Configuration config);
    }

    /** FrameLayout that caps its measured height so inner scrollers take over. */
    static final class MaxHeightFrame extends FrameLayout {
        private int maxHeightPx = Integer.MAX_VALUE;

        public MaxHeightFrame(Context context) {
            super(context);
        }

        public MaxHeightFrame(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        private ConfigListener configListener;

        MaxHeightFrame(Context context, int maxHeightPx) {
            super(context);
            this.maxHeightPx = maxHeightPx;
        }

        void setMaxHeightPx(int maxHeightPx) {
            if (this.maxHeightPx == maxHeightPx) return;
            this.maxHeightPx = maxHeightPx;
            requestLayout();
        }

        void setConfigListener(ConfigListener listener) {
            configListener = listener;
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            if (configListener != null) configListener.onConfiguration(newConfig);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int mode = MeasureSpec.getMode(heightMeasureSpec);
            int size = MeasureSpec.getSize(heightMeasureSpec);
            int capped = mode == MeasureSpec.UNSPECIFIED ? maxHeightPx : Math.min(size, maxHeightPx);
            int newMode = mode == MeasureSpec.EXACTLY ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST;
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(capped, newMode));
        }
    }
}
