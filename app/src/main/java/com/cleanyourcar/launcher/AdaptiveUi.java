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

    private AdaptiveUi() {
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
        Configuration config = context.getResources().getConfiguration();
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
                    if (onChanged != null) v.post(onChanged);
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
        Context context = dialog.getContext();
        LayoutPolicy.LayoutSpec spec = specFor(context);
        MaxHeightFrame frame = new MaxHeightFrame(context, dp(context, spec.dialogMaxHeightDp));
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
            dialog.getWindow().setLayout(dp(context, spec.dialogWidthDp(preferredWidthDp)),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
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

        MaxHeightFrame(Context context, int maxHeightPx) {
            super(context);
            this.maxHeightPx = maxHeightPx;
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
