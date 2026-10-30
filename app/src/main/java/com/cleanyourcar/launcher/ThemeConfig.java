package com.cleanyourcar.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;

import java.io.File;

/**
 * Glassmorphism design tokens, multi-theme palettes, layout customization preferences,
 * and zero-bloat RGB_565 optional wallpaper loader.
 */
public final class ThemeConfig {
    // Glassmorphism Design System Tokens (.github/skills/glassmorphism/DESIGN.md)
    public static final int COLOR_PRIMARY = 0xFF1856FF;
    public static final int COLOR_SECONDARY = 0xFF3A344E;
    public static final int COLOR_SUCCESS = 0xFF07CA6B;
    public static final int COLOR_WARNING = 0xFFE89558;
    public static final int COLOR_DANGER = 0xFFEA2143;
    public static final int COLOR_TEXT_PRIMARY = 0xFFFFFFFF;
    public static final int COLOR_TEXT_SECONDARY = 0xFFB8C4E0;

    // Built-in Visual Themes for multi-user customization
    public static final int THEME_GLASSMORPHISM = 0;
    public static final int THEME_OLED_BLACK = 1;
    public static final int THEME_WARM_CARBON = 2;

    // 4-step Liquid Glass Opacity / Translucency presets
    public static final int OPACITY_CRYSTAL = 0;   // Ultra-clear liquid glass
    public static final int OPACITY_BALANCED = 1;  // Default iOS 26 liquid glass
    public static final int OPACITY_FROSTED = 2;   // Deep frosted glass
    public static final int OPACITY_SOLID = 3;     // Near-opaque anti-glare

    /**
     * Legacy preference file; kept by name and never deleted. UI, app-library, and city keys
     * remain stored here and are merged into
     * {@link com.cleanyourcar.launcher.config.LauncherConfiguration} on load.
     */
    public static final String PREFS_NAME = "carplay_launcher_prefs";
    public static final String KEY_THEME_ID = "theme_id";
    public static final String KEY_GLASS_OPACITY = "glass_opacity";
    public static final String KEY_LANGUAGE_ID = "language_id";
    public static final String KEY_DOCK_LEFT = "dock_left";
    public static final String KEY_SWAP_COLUMNS = "swap_dashboard_columns";
    public static final String KEY_GRID_COLUMNS = "grid_columns";
    public static final String KEY_CITY_INDEX = "plus_code_city_index";
    public static final String KEY_APP_SAVED_ORDER = "app_saved_order_csv";
    public static final String KEY_APP_HIDDEN_PACKAGES = "app_hidden_pkgs_set";
    public static final String KEY_APP_UNHIDDEN_PACKAGES = "app_unhidden_pkgs_set";
    /**
     * Legacy role package overrides. Read once by the configuration migration; role choices
     * are then read and written only through
     * {@link com.cleanyourcar.launcher.config.ConfigurationStore}.
     */
    public static final String KEY_PKG_MAPS = "pkg_maps";
    public static final String KEY_PKG_BT_MUSIC = "pkg_bt_music";
    public static final String KEY_PKG_USB_MUSIC = "pkg_usb_music";
    public static final String KEY_PKG_DASHCAM = "pkg_dashcam";
    public static final String KEY_PKG_EQUALIZER = "pkg_equalizer";

    private static final String[] WALLPAPER_PATHS = new String[] {
            "/sdcard/carplay_wallpaper.jpg",
            "/sdcard/carplay_wallpaper.webp",
            "/sdcard/carplay_wallpaper.png"
    };

    private ThemeConfig() {
    }

    public static final class Palette {
        public final int id;
        public final String name;
        public final int canvasStart;
        public final int canvasMid;
        public final int canvasEnd;
        public final int dockTop;
        public final int dockBottom;
        public final int cardTop;
        public final int cardBottom;
        public final int borderLuminous;
        public final int borderSubtle;
        public final int wellBackground;
        public final int tileDefault;
        public final int tilePressed;
        public final int accentPrimary;
        public final int accentSuccess;
        public final int accentWarning;
        public final int accentDanger;
        public final int textPrimary;
        public final int textSecondary;

        public Palette(
                int id,
                String name,
                int canvasStart,
                int canvasMid,
                int canvasEnd,
                int dockTop,
                int dockBottom,
                int cardTop,
                int cardBottom,
                int borderLuminous,
                int borderSubtle,
                int wellBackground,
                int tileDefault,
                int tilePressed,
                int accentPrimary,
                int accentSuccess,
                int accentWarning,
                int accentDanger,
                int textPrimary,
                int textSecondary) {
            this.id = id;
            this.name = name;
            this.canvasStart = canvasStart;
            this.canvasMid = canvasMid;
            this.canvasEnd = canvasEnd;
            this.dockTop = dockTop;
            this.dockBottom = dockBottom;
            this.cardTop = cardTop;
            this.cardBottom = cardBottom;
            this.borderLuminous = borderLuminous;
            this.borderSubtle = borderSubtle;
            this.wellBackground = wellBackground;
            this.tileDefault = tileDefault;
            this.tilePressed = tilePressed;
            this.accentPrimary = accentPrimary;
            this.accentSuccess = accentSuccess;
            this.accentWarning = accentWarning;
            this.accentDanger = accentDanger;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
        }
    }

    public static Palette getPalette(int themeId) {
        return getPalette(themeId, OPACITY_BALANCED);
    }

    public static Palette getPalette(int themeId, int opacityLevel) {
        Palette base;
        if (themeId == THEME_OLED_BLACK) {
            base = new Palette(
                    THEME_OLED_BLACK,
                    "Liquid Glass — Ultraviolet Prism",
                    0xFF1A1230,
                    0xFF100B1E,
                    0xFF07050E,
                    0xD9241B3B,
                    0xB8140F24,
                    0x36FFFFFF,
                    0x12FFFFFF,
                    0x59FFFFFF,
                    0x26FFFFFF,
                    0x5904030A,
                    0x24FFFFFF,
                    0x667C3AED,
                    0xFF7C3AED,
                    COLOR_SUCCESS,
                    COLOR_WARNING,
                    COLOR_DANGER,
                    COLOR_TEXT_PRIMARY,
                    0xFFD0C4EC
            );
        } else if (themeId == THEME_WARM_CARBON) {
            base = new Palette(
                    THEME_WARM_CARBON,
                    "Liquid Glass — Titanium & Ember",
                    0xFF211A15,
                    0xFF14110E,
                    0xFF090807,
                    0xD928211C,
                    0xB8171310,
                    0x33FFFFFF,
                    0x10FFFFFF,
                    0x52FFF5EB,
                    0x24FFF5EB,
                    0x59080605,
                    0x22FFFFFF,
                    0x66E07A2E,
                    0xFFE07A2E,
                    COLOR_SUCCESS,
                    0xFFF5B851,
                    COLOR_DANGER,
                    0xFFF5F1EB,
                    0xFFD2C7BA
            );
        } else {
            base = new Palette(
                    THEME_GLASSMORPHISM,
                    "Liquid Glass — Apple Sapphire",
                    0xFF102048,
                    0xFF0A132C,
                    0xFF050812,
                    0xD9162340,
                    0xB80E162B,
                    0x38FFFFFF,
                    0x12FFFFFF,
                    0x60FFFFFF,
                    0x28FFFFFF,
                    0x5903060E,
                    0x24FFFFFF,
                    0x661856FF,
                    COLOR_PRIMARY,
                    COLOR_SUCCESS,
                    COLOR_WARNING,
                    COLOR_DANGER,
                    COLOR_TEXT_PRIMARY,
                    COLOR_TEXT_SECONDARY
            );
        }
        return applyOpacityLevel(base, opacityLevel);
    }

    private static Palette applyOpacityLevel(Palette base, int opacityLevel) {
        int cardTopAlpha;
        int cardBottomAlpha;
        int dockTopAlpha;
        int dockBottomAlpha;
        int tileAlpha;
        int cardBaseRgb;

        if (opacityLevel == OPACITY_CRYSTAL) {
            cardTopAlpha = 0x20;
            cardBottomAlpha = 0x0A;
            dockTopAlpha = 0x99;
            dockBottomAlpha = 0x7A;
            tileAlpha = 0x16;
            cardBaseRgb = 0xFFFFFF;
        } else if (opacityLevel == OPACITY_FROSTED) {
            cardTopAlpha = 0xBF;
            cardBottomAlpha = 0xA6;
            dockTopAlpha = 0xEE;
            dockBottomAlpha = 0xE0;
            tileAlpha = 0x38;
            cardBaseRgb = base.dockTop & 0x00FFFFFF;
        } else if (opacityLevel == OPACITY_SOLID) {
            cardTopAlpha = 0xF5;
            cardBottomAlpha = 0xEB;
            dockTopAlpha = 0xFC;
            dockBottomAlpha = 0xF5;
            tileAlpha = 0x48;
            cardBaseRgb = base.dockTop & 0x00FFFFFF;
        } else {
            // Default OPACITY_BALANCED
            cardTopAlpha = 0x38;
            cardBottomAlpha = 0x12;
            dockTopAlpha = 0xD9;
            dockBottomAlpha = 0xB8;
            tileAlpha = 0x24;
            cardBaseRgb = 0xFFFFFF;
        }

        return new Palette(
                base.id,
                base.name,
                base.canvasStart,
                base.canvasMid,
                base.canvasEnd,
                (dockTopAlpha << 24) | (base.dockTop & 0x00FFFFFF),
                (dockBottomAlpha << 24) | (base.dockBottom & 0x00FFFFFF),
                (cardTopAlpha << 24) | cardBaseRgb,
                (cardBottomAlpha << 24) | cardBaseRgb,
                base.borderLuminous,
                base.borderSubtle,
                base.wellBackground,
                (tileAlpha << 24) | 0x00FFFFFF,
                base.tilePressed,
                base.accentPrimary,
                base.accentSuccess,
                base.accentWarning,
                base.accentDanger,
                base.textPrimary,
                base.textSecondary
        );
    }

    public static Palette getActivePalette(Context context) {
        return getPalette(getThemeId(context), getGlassOpacity(context));
    }

    public static int getGlassOpacity(Context context) {
        int val = prefs(context).getInt(KEY_GLASS_OPACITY, OPACITY_BALANCED);
        if (val < OPACITY_CRYSTAL || val > OPACITY_SOLID) return OPACITY_BALANCED;
        return val;
    }

    public static void setGlassOpacity(Context context, int opacityLevel) {
        int clamped = Math.max(OPACITY_CRYSTAL, Math.min(OPACITY_SOLID, opacityLevel));
        prefs(context).edit().putInt(KEY_GLASS_OPACITY, clamped).apply();
    }

    public static int cycleGlassOpacity(Context context) {
        int next = (getGlassOpacity(context) + 1) % 4;
        setGlassOpacity(context, next);
        return next;
    }

    public static String getOpacityLabel(int opacityLevel) {
        switch (opacityLevel) {
            case OPACITY_CRYSTAL:
                return "Trong suốt cao (Crystal)";
            case OPACITY_FROSTED:
                return "Kính mờ đậm (Frosted)";
            case OPACITY_SOLID:
                return "Đặc chống chói (Solid)";
            default:
                return "Kính lỏng chuẩn (Liquid)";
        }
    }

    public static int getLanguageId(Context context) {
        int id = prefs(context).getInt(KEY_LANGUAGE_ID, I18n.LANG_VI);
        if (id < 0 || id >= I18n.LANGUAGE_NAMES.length) return I18n.LANG_VI;
        return id;
    }

    public static void setLanguageId(Context context, int langId) {
        int clamped = Math.max(0, Math.min(I18n.LANGUAGE_NAMES.length - 1, langId));
        prefs(context).edit().putInt(KEY_LANGUAGE_ID, clamped).apply();
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getThemeId(Context context) {
        return prefs(context).getInt(KEY_THEME_ID, THEME_GLASSMORPHISM);
    }

    public static void setThemeId(Context context, int themeId) {
        prefs(context).edit().putInt(KEY_THEME_ID, themeId).apply();
    }

    public static int cycleTheme(Context context) {
        int next = (getThemeId(context) + 1) % 3;
        setThemeId(context, next);
        return next;
    }

    public static boolean isDockLeft(Context context) {
        return prefs(context).getBoolean(KEY_DOCK_LEFT, true);
    }

    public static void setDockLeft(Context context, boolean dockLeft) {
        prefs(context).edit().putBoolean(KEY_DOCK_LEFT, dockLeft).apply();
    }

    public static boolean isSwapDashboardColumns(Context context) {
        return prefs(context).getBoolean(KEY_SWAP_COLUMNS, false);
    }

    public static void setSwapDashboardColumns(Context context, boolean swap) {
        prefs(context).edit().putBoolean(KEY_SWAP_COLUMNS, swap).apply();
    }

    public static int getGridColumns(Context context) {
        int cols = prefs(context).getInt(KEY_GRID_COLUMNS, 4);
        if (cols < 3 || cols > 5) return 4;
        return cols;
    }

    public static void setGridColumns(Context context, int cols) {
        int clamped = Math.max(3, Math.min(5, cols));
        prefs(context).edit().putInt(KEY_GRID_COLUMNS, clamped).apply();
    }

    public static int calculateInSampleSize(int rawWidth, int rawHeight, int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        if (rawHeight > reqHeight || rawWidth > reqWidth) {
            final int halfHeight = rawHeight / 2;
            final int halfWidth = rawWidth / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    public static Drawable createGlassCardDrawable(
            float radiusPx, int fillTopColor, int fillBottomColor, int strokeColor, int strokeWidthPx) {
        // Outer refractive rim
        GradientDrawable base = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { fillTopColor, fillBottomColor });
        base.setCornerRadius(radiusPx);
        base.setStroke(strokeWidthPx, strokeColor);

        // Inner top-left specular lens sheen (Apple Liquid Glass highlight)
        GradientDrawable sheen = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x36FFFFFF, 0x00FFFFFF });
        sheen.setCornerRadius(radiusPx);

        LayerDrawable layered = new LayerDrawable(new Drawable[] { base, sheen });
        layered.setLayerInset(1, 1, 1, 1, 1);
        return layered;
    }

    public static Drawable createInteractiveTileDrawable(
            float radiusPx, int normalFill, int pressedFill, int normalStroke, int activeStroke) {
        Drawable pressed = createGlassCardDrawable(radiusPx, 0x55FFFFFF, pressedFill, activeStroke, 2);
        Drawable focused = createGlassCardDrawable(radiusPx, 0x55FFFFFF, pressedFill, activeStroke, 2);
        Drawable normal = createGlassCardDrawable(radiusPx, normalFill, 0x0DFFFFFF, normalStroke, 1);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_pressed }, pressed);
        states.addState(new int[] { android.R.attr.state_focused }, focused);
        states.addState(new int[] {}, normal);
        return states;
    }

    public static Drawable createSquircleIconDrawable(float radiusPx, int startColor, int endColor, int borderColor) {
        return createGlassCardDrawable(radiusPx, startColor, endColor, borderColor, 1);
    }

    public static Drawable loadCanvasBackground(Context context) {
        Palette p = getActivePalette(context);
        try {
            for (String path : WALLPAPER_PATHS) {
                File f = new File(path);
                if (f.exists() && f.isFile() && f.canRead()) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 1024, 600);
                        opts.inPreferredConfig = Bitmap.Config.RGB_565;
                        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                        if (bmp != null) {
                            BitmapDrawable bmpDrawable = new BitmapDrawable(context.getResources(), bmp);
                            ColorDrawable scrim = new ColorDrawable(0x8805070E);
                            return new LayerDrawable(new Drawable[] { bmpDrawable, scrim });
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall back to zero-RAM gradient drawable
        }
        return new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { p.canvasStart, p.canvasMid, p.canvasEnd });
    }
}
