package com.cleanyourcar.launcher.layout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class LayoutPolicyTest {

    /** A physical resolution at a density, optionally reduced by system-bar insets in dp. */
    private static final class Fixture {
        final String name;
        final int widthPx;
        final int heightPx;
        final float density;
        final int insetWidthDp;
        final int insetHeightDp;

        Fixture(String name, int widthPx, int heightPx, float density, int insetWidthDp,
                int insetHeightDp) {
            this.name = name;
            this.widthPx = widthPx;
            this.heightPx = heightPx;
            this.density = density;
            this.insetWidthDp = insetWidthDp;
            this.insetHeightDp = insetHeightDp;
        }

        int widthDp() {
            return Math.round(widthPx / density) - insetWidthDp;
        }

        int heightDp() {
            return Math.round(heightPx / density) - insetHeightDp;
        }

        @Override
        public String toString() {
            return name + " (" + widthDp() + "x" + heightDp() + "dp)";
        }
    }

    private static final float MDPI = 1f;
    private static final float HDPI = 1.5f;
    private static final float XHDPI = 2f;

    /** Spec fixture matrix plus inset-constrained variants (status 24dp + nav 48dp bars). */
    private static List<Fixture> fixtures() {
        List<Fixture> list = new ArrayList<>();
        list.add(new Fixture("800x480 mdpi", 800, 480, MDPI, 0, 0));
        list.add(new Fixture("1024x600 mdpi", 1024, 600, MDPI, 0, 0));
        list.add(new Fixture("1280x720 xhdpi", 1280, 720, XHDPI, 0, 0));
        list.add(new Fixture("1920x720 ultrawide mdpi", 1920, 720, MDPI, 0, 0));
        list.add(new Fixture("1920x720 ultrawide hdpi", 1920, 720, HDPI, 0, 0));
        list.add(new Fixture("720x1280 portrait xhdpi", 720, 1280, XHDPI, 0, 0));
        list.add(new Fixture("720x1280 portrait mdpi", 720, 1280, MDPI, 0, 0));
        list.add(new Fixture("800x480 mdpi bars", 800, 480, MDPI, 0, 72));
        list.add(new Fixture("1024x600 mdpi side nav", 1024, 600, MDPI, 48, 0));
        list.add(new Fixture("1280x720 xhdpi bars", 1280, 720, XHDPI, 0, 72));
        list.add(new Fixture("720x1280 portrait xhdpi bars", 720, 1280, XHDPI, 0, 72));
        return list;
    }

    private static final float[] FONT_SCALES = {0.85f, 1f, 1.3f, 2f};

    private static void assertCommonGuarantees(String label, LayoutPolicy.LayoutSpec spec,
            int availableWidthDp, int availableHeightDp, int preferredColumns) {
        assertTrue(label, spec.minimumTouchTargetDp >= 48);
        assertTrue(label, spec.contentWidthDp <= availableWidthDp);
        assertTrue(label, spec.contentWidthDp > 0);
        assertTrue(label + " dock target", spec.dockButtonDp >= 48);
        assertTrue(label + " transport target", spec.transportHeightDp >= 48);
        assertTrue(label + " nav target", spec.navButtonHeightDp >= 48);
        assertTrue(label + " action target", spec.actionRowMinHeightDp >= 48);
        assertTrue(label + " tile target", spec.gridTileHeightDp >= 48);
        assertTrue(label + " tile text room", spec.gridTileHeightDp >= spec.minimumTileHeightDp);
        assertTrue(label + " grid cols", spec.gridColumns >= 1);
        assertTrue(label + " grid cols pref", spec.gridColumns <= preferredColumns);
        assertTrue(label + " grid rows", spec.gridRows >= 1);
        assertTrue(label + " app slots", spec.appSlotsPerPage >= 1);
        // Essential actions stay reachable: either the content fits or the surface scrolls.
        assertTrue(label + " dashboard reachable", spec.dashboardScrolls
                || spec.dashboardHeightDp <= availableHeightDp);
        assertTrue(label + " dock reachable", spec.dockScrolls
                || spec.dockHeightDp <= availableHeightDp);
        assertTrue(label + " grid reachable", spec.gridScrolls
                || spec.gridRows * (spec.gridTileHeightDp + LayoutPolicy.TILE_GAP_DP)
                        <= availableHeightDp);
        // Library and settings tiles plus at least one app fit on every page or the grid scrolls.
        assertTrue(label + " grid fixed tiles", spec.gridScrolls
                || spec.gridColumns * spec.gridRows >= spec.appSlotsPerPage + 2);
        // Dialogs are bounded by the window.
        assertTrue(label + " dialog width", spec.dialogMaxWidthDp <= availableWidthDp);
        assertTrue(label + " dialog height", spec.dialogMaxHeightDp <= availableHeightDp);
        assertTrue(label + " dialog width fit", spec.dialogWidthDp(650) <= spec.dialogMaxWidthDp);
    }

    @Test
    public void everyFixtureKeepsTouchTargetsAndBounds() {
        for (Fixture f : fixtures()) {
            for (float fontScale : FONT_SCALES) {
                for (int cols = 3; cols <= 5; cols++) {
                    LayoutPolicy.LayoutSpec spec =
                            LayoutPolicy.calculate(f.widthDp(), f.heightDp(), fontScale, cols);
                    assertCommonGuarantees(f + " font " + fontScale + " cols " + cols, spec,
                            f.widthDp(), f.heightDp(), cols);
                }
            }
        }
    }

    @Test
    public void smallWindowStacksAndScrolls() {
        Fixture portrait = new Fixture("720x1280 portrait xhdpi bars", 720, 1280, XHDPI, 0, 72);
        LayoutPolicy.LayoutSpec spec =
                LayoutPolicy.calculate(portrait.widthDp(), portrait.heightDp(), 1f, 4);
        assertEquals(LayoutPolicy.Mode.STACKED, spec.mode);
        assertEquals(LayoutPolicy.DockPlacement.SIDE_COMPACT, spec.dock);
        assertTrue(spec.minimumTouchTargetDp >= 48);
        assertTrue(spec.contentWidthDp <= portrait.widthDp());
        LayoutPolicy.LayoutSpec portraitLargeText =
                LayoutPolicy.calculate(portrait.widthDp(), portrait.heightDp(), 1.3f, 4);
        assertEquals(LayoutPolicy.Mode.STACKED, portraitLargeText.mode);
        assertTrue(portraitLargeText.dashboardScrolls);

        Fixture shortWindow = new Fixture("1280x720 xhdpi bars", 1280, 720, XHDPI, 0, 72);
        LayoutPolicy.LayoutSpec shortSpec =
                LayoutPolicy.calculate(shortWindow.widthDp(), shortWindow.heightDp(), 1f, 4);
        assertEquals(LayoutPolicy.DockPlacement.SIDE_COMPACT, shortSpec.dock);
        assertTrue(shortSpec.dashboardScrolls);
        assertTrue(shortSpec.dockScrolls || shortSpec.dockHeightDp <= shortWindow.heightDp());

        // The existing 1024x600 head unit keeps the two-column dashboard without scrolling.
        LayoutPolicy.LayoutSpec reference = LayoutPolicy.calculate(1024, 600, 1f, 4);
        assertEquals(LayoutPolicy.Mode.SPLIT, reference.mode);
        assertEquals(LayoutPolicy.DockPlacement.SIDE, reference.dock);
        assertFalse(reference.dashboardScrolls);
        assertFalse(reference.dockScrolls);
        assertEquals(84, reference.dockWidthDp);
        assertEquals(92, reference.navButtonHeightDp);
        assertEquals(240, reference.gridTileHeightDp);
    }

    @Test
    public void gridPreferenceCannotOverflowWindow() {
        for (Fixture f : fixtures()) {
            for (float fontScale : FONT_SCALES) {
                for (int cols = 3; cols <= 5; cols++) {
                    LayoutPolicy.LayoutSpec spec =
                            LayoutPolicy.calculate(f.widthDp(), f.heightDp(), fontScale, cols);
                    String label = f + " font " + fontScale + " cols " + cols;
                    int needed = spec.gridColumns * (LayoutPolicy.MIN_TILE_WIDTH_DP
                            + LayoutPolicy.TILE_GAP_DP);
                    assertTrue(label, spec.gridColumns == 1
                            || needed <= spec.contentWidthDp + LayoutPolicy.TILE_GAP_DP);
                    assertTrue(label, spec.gridColumns <= cols);
                }
            }
        }
        // Preference honoured where it fits.
        LayoutPolicy.LayoutSpec wide = LayoutPolicy.calculate(1024, 600, 1f, 5);
        assertEquals(5, wide.gridColumns);
        assertEquals(2, wide.gridRows);
        assertEquals(8, wide.appSlotsPerPage);
        // Preference bounded where it does not.
        LayoutPolicy.LayoutSpec narrow = LayoutPolicy.calculate(360, 568, 1f, 5);
        assertTrue(narrow.gridColumns < 5);
        // Keypad columns are bounded by the dialog width and the 48dp target.
        int keypadWidth = narrow.dialogWidthDp(620) - 44;
        int keypad = LayoutPolicy.fitColumns(keypadWidth, 48, 10, 7);
        assertTrue(keypad >= 1 && keypad < 7);
        assertTrue(keypad * (48 + 10) <= keypadWidth + 10);
        assertEquals(7, LayoutPolicy.fitColumns(620 - 44, 48, 10, 7));
        assertEquals(1, LayoutPolicy.fitColumns(10, 48, 10, 7));
    }

    @Test
    public void largeFontPreservesControls() {
        for (Fixture f : fixtures()) {
            LayoutPolicy.LayoutSpec normal =
                    LayoutPolicy.calculate(f.widthDp(), f.heightDp(), 1f, 4);
            LayoutPolicy.LayoutSpec large =
                    LayoutPolicy.calculate(f.widthDp(), f.heightDp(), 2f, 4);
            assertCommonGuarantees(f + " large font", large, f.widthDp(), f.heightDp(), 4);
            // Larger text never shrinks targets or text room; it scrolls instead of clipping.
            assertTrue(f.toString(), large.actionRowMinHeightDp >= normal.actionRowMinHeightDp);
            assertTrue(f.toString(), large.dockWidthDp >= normal.dockWidthDp);
            assertTrue(f.toString(), large.dashboardHeightDp >= normal.dashboardHeightDp);
            assertTrue(f.toString(), large.minimumTileHeightDp >= normal.minimumTileHeightDp);
        }
        LayoutPolicy.LayoutSpec shortWindow = LayoutPolicy.calculate(640, 288, 2f, 4);
        assertTrue(shortWindow.dashboardScrolls);
        assertTrue(shortWindow.dockScrolls);
        assertTrue(shortWindow.gridScrolls || shortWindow.gridRows == 1);
    }

    @Test
    public void ultrawideCapsContentWidth() {
        Fixture ultrawide = new Fixture("1920x720 ultrawide mdpi", 1920, 720, MDPI, 0, 0);
        LayoutPolicy.LayoutSpec spec =
                LayoutPolicy.calculate(ultrawide.widthDp(), ultrawide.heightDp(), 1f, 4);
        assertEquals(LayoutPolicy.Mode.SPLIT, spec.mode);
        assertTrue(spec.contentWidthDp <= LayoutPolicy.MAX_CONTENT_WIDTH_DP);
        assertTrue(spec.contentWidthDp < spec.availableContentWidthDp);
        assertTrue(spec.contentWidthDp <= ultrawide.widthDp());
        assertTrue(spec.minimumTouchTargetDp >= 48);
        assertTrue(spec.dialogWidthDp(650) <= 650);

        // Ordinary windows use their full content width.
        LayoutPolicy.LayoutSpec reference = LayoutPolicy.calculate(1024, 600, 1f, 4);
        assertEquals(reference.availableContentWidthDp, reference.contentWidthDp);
    }

    @Test
    public void equalInputsProduceEqualSpecs() {
        assertEquals(LayoutPolicy.calculate(800, 480, 1f, 4),
                LayoutPolicy.calculate(800, 480, 1f, 4));
        assertEquals(LayoutPolicy.calculate(800, 480, 1f, 4).hashCode(),
                LayoutPolicy.calculate(800, 480, 1f, 4).hashCode());
        assertFalse(LayoutPolicy.calculate(800, 480, 1f, 4)
                .equals(LayoutPolicy.calculate(360, 640, 1f, 4)));
    }
}
