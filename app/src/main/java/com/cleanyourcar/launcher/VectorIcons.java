package com.cleanyourcar.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Hallmark Floor Gate 30a compliant: Pure geometric Canvas vector icons on a 24x24 grid.
 * Zero emojis, zero bitmap allocations, zero KB asset bloat, crisp at any density.
 */
public final class VectorIcons {
    public static final int ICON_MAP = 1;
    public static final int ICON_MUSIC = 2;
    public static final int ICON_CAMERA = 3;
    public static final int ICON_EQUALIZER = 4;
    public static final int ICON_GRID = 5;
    public static final int ICON_PLAY = 6;
    public static final int ICON_PAUSE = 7;
    public static final int ICON_PREV = 8;
    public static final int ICON_NEXT = 9;
    public static final int ICON_HASH = 10;
    public static final int ICON_SUN = 11;
    public static final int ICON_MOON = 12;
    public static final int ICON_PLUS = 13;
    public static final int ICON_SLIDERS = 14;
    public static final int ICON_HOME = 15;
    public static final int ICON_GOOGLE_MAPSPIN = 16;

    private VectorIcons() {
    }

    public static String getSpec(int iconId) {
        switch (iconId) {
            case ICON_MAP: return "nav-arrow-24";
            case ICON_MUSIC: return "music-note-24";
            case ICON_CAMERA: return "camera-lens-24";
            case ICON_EQUALIZER: return "eq-sliders-24";
            case ICON_GRID: return "bento-grid-24";
            case ICON_PLAY: return "play-triangle-24";
            case ICON_PAUSE: return "pause-bars-24";
            case ICON_PREV: return "skip-back-24";
            case ICON_NEXT: return "skip-fwd-24";
            case ICON_HASH: return "plus-code-hash-24";
            case ICON_SUN: return "brightness-sun-24";
            case ICON_MOON: return "night-crescent-24";
            case ICON_PLUS: return "library-plus-24";
            case ICON_SLIDERS: return "tune-sliders-24";
            case ICON_HOME: return "home-grid-24";
            case ICON_GOOGLE_MAPSPIN: return "google-maps-pin-24";
            default: return null;
        }
    }

    public static View createIconView(Context context, int iconId, int strokeColor, int sizePx) {
        View v = new View(context);
        v.setBackground(new IconDrawable(iconId, strokeColor, sizePx));
        return v;
    }

    public static Drawable createDrawable(int iconId, int strokeColor, int sizePx) {
        return new IconDrawable(iconId, strokeColor, sizePx);
    }

    public static final class IconDrawable extends Drawable {
        private final int iconId;
        private final Paint strokePaint;
        private final Paint fillPaint;
        private final int intrinsicSize;

        public IconDrawable(int iconId, int color, int intrinsicSize) {
            this.iconId = iconId;
            this.intrinsicSize = intrinsicSize;

            strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setColor(color);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(2.0f);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);

            fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            fillPaint.setColor(color);
            fillPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public int getIntrinsicWidth() {
            return intrinsicSize;
        }

        @Override
        public int getIntrinsicHeight() {
            return intrinsicSize;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            float size = Math.min(b.width(), b.height());
            float scale = size / 24f;
            float ox = b.left + (b.width() - size) / 2f;
            float oy = b.top + (b.height() - size) / 2f;

            strokePaint.setStrokeWidth(Math.max(1.6f, 1.85f * scale));

            int save = canvas.save();
            canvas.translate(ox, oy);
            canvas.scale(scale, scale);

            Path p = new Path();
            switch (iconId) {
                case ICON_MAP:
                    p.moveTo(3f, 11f);
                    p.lineTo(21f, 3f);
                    p.lineTo(13f, 21f);
                    p.lineTo(11f, 13f);
                    p.close();
                    canvas.drawPath(p, strokePaint);
                    break;

                case ICON_MUSIC:
                    p.moveTo(9f, 18f);
                    p.lineTo(9f, 5f);
                    p.lineTo(20f, 3f);
                    p.lineTo(20f, 16f);
                    canvas.drawPath(p, strokePaint);
                    canvas.drawCircle(6.5f, 18f, 2.5f, strokePaint);
                    canvas.drawCircle(17.5f, 16f, 2.5f, strokePaint);
                    break;

                case ICON_CAMERA:
                    p.moveTo(14.5f, 4f);
                    p.lineTo(9.5f, 4f);
                    p.lineTo(7f, 7f);
                    p.lineTo(4f, 7f);
                    p.quadTo(2f, 7f, 2f, 9f);
                    p.lineTo(2f, 18f);
                    p.quadTo(2f, 20f, 4f, 20f);
                    p.lineTo(20f, 20f);
                    p.quadTo(22f, 20f, 22f, 18f);
                    p.lineTo(22f, 9f);
                    p.quadTo(22f, 7f, 20f, 7f);
                    p.lineTo(17f, 7f);
                    p.close();
                    canvas.drawPath(p, strokePaint);
                    canvas.drawCircle(12f, 13.5f, 3.2f, strokePaint);
                    break;

                case ICON_EQUALIZER:
                case ICON_SLIDERS:
                    canvas.drawLine(5f, 20f, 5f, 14f, strokePaint);
                    canvas.drawLine(5f, 10f, 5f, 4f, strokePaint);
                    canvas.drawLine(12f, 20f, 12f, 12f, strokePaint);
                    canvas.drawLine(12f, 8f, 12f, 4f, strokePaint);
                    canvas.drawLine(19f, 20f, 19f, 16f, strokePaint);
                    canvas.drawLine(19f, 12f, 19f, 4f, strokePaint);
                    canvas.drawLine(2.5f, 14f, 7.5f, 14f, strokePaint);
                    canvas.drawLine(9.5f, 8f, 14.5f, 8f, strokePaint);
                    canvas.drawLine(16.5f, 16f, 21.5f, 16f, strokePaint);
                    break;

                case ICON_GRID:
                case ICON_HOME:
                    canvas.drawRoundRect(new RectF(3f, 3f, 10f, 10f), 1.8f, 1.8f, strokePaint);
                    canvas.drawRoundRect(new RectF(14f, 3f, 21f, 10f), 1.8f, 1.8f, strokePaint);
                    canvas.drawRoundRect(new RectF(14f, 14f, 21f, 21f), 1.8f, 1.8f, strokePaint);
                    canvas.drawRoundRect(new RectF(3f, 14f, 10f, 21f), 1.8f, 1.8f, strokePaint);
                    break;

                case ICON_PLAY:
                    p.moveTo(6f, 4f);
                    p.lineTo(19f, 12f);
                    p.lineTo(6f, 20f);
                    p.close();
                    canvas.drawPath(p, fillPaint);
                    break;

                case ICON_PAUSE:
                    canvas.drawRoundRect(new RectF(6f, 4f, 10f, 20f), 1.2f, 1.2f, fillPaint);
                    canvas.drawRoundRect(new RectF(14f, 4f, 18f, 20f), 1.2f, 1.2f, fillPaint);
                    break;

                case ICON_PREV:
                    p.moveTo(19f, 19f);
                    p.lineTo(9f, 12f);
                    p.lineTo(19f, 5f);
                    p.close();
                    canvas.drawPath(p, strokePaint);
                    canvas.drawLine(5f, 18.5f, 5f, 5.5f, strokePaint);
                    break;

                case ICON_NEXT:
                    p.moveTo(5f, 5f);
                    p.lineTo(15f, 12f);
                    p.lineTo(5f, 19f);
                    p.close();
                    canvas.drawPath(p, strokePaint);
                    canvas.drawLine(19f, 5.5f, 19f, 18.5f, strokePaint);
                    break;

                case ICON_HASH:
                    canvas.drawLine(4f, 9f, 20f, 9f, strokePaint);
                    canvas.drawLine(4f, 15f, 20f, 15f, strokePaint);
                    canvas.drawLine(10f, 3.5f, 8f, 20.5f, strokePaint);
                    canvas.drawLine(16f, 3.5f, 14f, 20.5f, strokePaint);
                    break;

                case ICON_SUN:
                    canvas.drawCircle(12f, 12f, 4.5f, strokePaint);
                    canvas.drawLine(12f, 2f, 12f, 4.2f, strokePaint);
                    canvas.drawLine(12f, 19.8f, 12f, 22f, strokePaint);
                    canvas.drawLine(2f, 12f, 4.2f, 12f, strokePaint);
                    canvas.drawLine(19.8f, 12f, 22f, 12f, strokePaint);
                    canvas.drawLine(4.9f, 4.9f, 6.5f, 6.5f, strokePaint);
                    canvas.drawLine(17.5f, 17.5f, 19.1f, 19.1f, strokePaint);
                    canvas.drawLine(19.1f, 4.9f, 17.5f, 6.5f, strokePaint);
                    canvas.drawLine(6.5f, 17.5f, 4.9f, 19.1f, strokePaint);
                    break;

                case ICON_MOON:
                    p.moveTo(20.5f, 13.2f);
                    p.cubicTo(19.2f, 17.8f, 14.5f, 20.8f, 9.8f, 19.8f);
                    p.cubicTo(5.1f, 18.8f, 2.2f, 14.1f, 3.5f, 9.4f);
                    p.cubicTo(4.3f, 6.3f, 6.8f, 3.9f, 10.0f, 3.2f);
                    p.cubicTo(8.5f, 6.8f, 10.2f, 11.2f, 14.0f, 12.8f);
                    p.cubicTo(16.1f, 13.7f, 18.4f, 13.8f, 20.5f, 13.2f);
                    p.close();
                    canvas.drawPath(p, strokePaint);
                    break;

                case ICON_PLUS:
                    canvas.drawLine(12f, 5f, 12f, 19f, strokePaint);
                    canvas.drawLine(5f, 12f, 19f, 12f, strokePaint);
                    break;

                case ICON_GOOGLE_MAPSPIN:
                    drawGoogleMapsPin(canvas);
                    break;
            }
            canvas.restoreToCount(save);
        }

        private void drawGoogleMapsPin(Canvas canvas) {
            RectF headRect = new RectF(5f, 2f, 19f, 16f);
            Paint segPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            segPaint.setStyle(Paint.Style.STROKE);
            segPaint.setStrokeWidth(3.6f);
            segPaint.setStrokeCap(Paint.Cap.BUTT);

            // Top-left Red (#EA4335)
            segPaint.setColor(0xFFEA4335);
            canvas.drawArc(headRect, 195f, 95f, false, segPaint);

            // Top-right Blue (#4285F4)
            segPaint.setColor(0xFF4285F4);
            canvas.drawArc(headRect, 290f, 95f, false, segPaint);

            // Bottom-left Yellow (#FBBC04)
            segPaint.setColor(0xFFFBBC04);
            canvas.drawArc(headRect, 115f, 80f, false, segPaint);

            // Bottom-right & Pin Tip Green (#34A853)
            segPaint.setColor(0xFF34A853);
            canvas.drawArc(headRect, 25f, 90f, false, segPaint);

            Paint tipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            tipPaint.setStyle(Paint.Style.FILL);
            tipPaint.setColor(0xFF34A853);
            Path tip = new Path();
            tip.moveTo(8.2f, 14.2f);
            tip.lineTo(15.8f, 14.2f);
            tip.lineTo(12.0f, 21.8f);
            tip.close();
            canvas.drawPath(tip, tipPaint);

            // Inner white hole
            tipPaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(12f, 9f, 2.5f, tipPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            strokePaint.setAlpha(alpha);
            fillPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            strokePaint.setColorFilter(colorFilter);
            fillPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
