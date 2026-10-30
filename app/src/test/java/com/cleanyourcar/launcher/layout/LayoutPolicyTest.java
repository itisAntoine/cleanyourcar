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

    /** Spec fixture matrix plus inset-constrained and split-screen variants. */
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
        list.add(new Fixture("split-screen 320dp portrait", 640, 1200, XHDPI, 0, 0));
        list.add(new Fixture("split-screen 320dp landscape", 320, 400, MDPI, 0, 0));
        return list;
    }

    private static final float[] FONT_SCALES = {0.85f, 1f, 1.3f, 2f};

    // Geometry of the existing views, restated here so the checks do not reuse policy formulas.
    private static final int CARD_PADDING_DP = 20;
    private static final int TRANSPORT_GAP_DP = 12;
    private static final int NAV_BUTTON_GAP_DP = 10;
    private static final int KEYPAD_PREFERRED_WIDTH_DP = 620;
    private static final int KEYPAD_PADDING_DP = 22;
    private static final int KEY_MARGIN_DP = 5;

    /** Width of the card holding the music controls: one column in SPLIT, full width stacked. */
    private static int musicCardWidthDp(LayoutPolicy.LayoutSpec spec) {
        return spec.mode == LayoutPolicy.Mode.SPLIT
                ? (spec.contentWidthDp - spec.marginDp) / 2 : spec.contentWidthDp;
    }

    private static void assertCommonGuarantees(String label, LayoutPolicy.LayoutSpec spec,
            int widthDp, int heightDp, int preferredColumns) {
        // Horizontal fit: dock, its outer margin, content padding and content fit the window.
        assertTrue(label + " horizontal fit " + spec,
                spec.dockWidthDp + 3 * spec.marginDp + spec.contentWidthDp <= widthDp);
        assertTrue(label, spec.contentWidthDp > 0);

        // Dock buttons and toggle fit inside the dock and are full touch targets.
        assertTrue(label + " dock button", spec.dockButtonDp >= 48);
        assertTrue(label + " dock toggle", spec.dockToggleDp >= 48);
        assertTrue(label + " dock button fits dock",
                spec.dockButtonDp + 2 * spec.dockSidePaddingDp <= spec.dockWidthDp);
        assertTrue(label + " dock toggle fits dock",
                spec.dockToggleDp + 2 * spec.dockSidePaddingDp <= spec.dockWidthDp);

        // Transport row: three buttons with two gaps inside the padded music card.
        int transportWidth = (musicCardWidthDp(spec) - 2 * CARD_PADDING_DP
                - 2 * TRANSPORT_GAP_DP) / 3;
        assertTrue(label + " transport width " + transportWidth, transportWidth >= 48);
        assertEquals(label, transportWidth, spec.transportButtonWidthDp);
        assertTrue(label + " transport height", spec.transportHeightDp >= 48);

        // Navigation row: two buttons in the padded card of the other column.
        int navWidth = (musicCardWidthDp(spec) - 2 * CARD_PADDING_DP - NAV_BUTTON_GAP_DP) / 2;
        assertTrue(label + " nav width " + navWidth, navWidth >= 48);
        assertTrue(label + " nav height", spec.navButtonHeightDp >= 48);
        assertTrue(label + " action row", spec.actionRowMinHeightDp >= 48);

        // Grid tiles: the grid view spans content plus both margins, minus its 7dp padding,
        // and each tile has 7dp margins on both sides.
        int gridInner = spec.contentWidthDp + 2 * spec.marginDp - 2 * 7;
        int tileWidth = gridInner / spec.gridColumns - 2 * 7;
        assertTrue(label + " tile width " + tileWidth, tileWidth >= 48);
        assertTrue(label + " tile height", spec.gridTileHeightDp >= 48);
        assertTrue(label + " grid cols pref", spec.gridColumns <= preferredColumns);
        assertTrue(label + " grid cols", spec.gridColumns >= 1);

        // Plus Code keypad: keys share the padded dialog width, each with 5dp margins.
        int dialogWidth = spec.dialogWidthDp(KEYPAD_PREFERRED_WIDTH_DP);
        assertTrue(label + " dialog in window", dialogWidth + 2 * 16 <= widthDp);
        assertTrue(label + " dialog height in window", spec.dialogMaxHeightDp + 2 * 16 <= heightDp);
        int keyColumns = spec.keypadColumns(KEYPAD_PREFERRED_WIDTH_DP);
        int keyWidth = (dialogWidth - 2 * KEYPAD_PADDING_DP) / keyColumns - 2 * KEY_MARGIN_DP;
        assertTrue(label + " key width " + keyWidth, keyWidth >= 48);
        assertTrue(label + " key columns", keyColumns >= 1 && keyColumns <= 7);
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
    public void scrollingMatchesKnownWindows() {
        // The existing head unit fits everything at the default font scale.
        LayoutPolicy.LayoutSpec reference = LayoutPolicy.calculate(1024, 600, 1f, 4);
        assertFalse(reference.dashboardScrolls);
        assertFalse(reference.dockScrolls);
        assertFalse(reference.gridScrolls);
        // The small 800x480 unit still fits at the default font scale.
        assertFalse(LayoutPolicy.calculate(800, 480, 1f, 4).dashboardScrolls);
        // At font scale 2.0 the right column alone needs two 16.5sp action rows (2 x 59dp),
        // a 30sp navigation title (~70dp), a 48dp navigation button, card padding (36 + 32),
        // one card gap (14) and margins (16): more than 300dp, so it must scroll.
        assertTrue(LayoutPolicy.calculate(800, 300, 2f, 4).dashboardScrolls);
        // A 288dp-tall window cannot fit the 4 dock buttons (4 x 48dp) plus clock and toggle.
        assertTrue(LayoutPolicy.calculate(640, 288, 1f, 4).dockScrolls);
        // Stacked cards hold, top to bottom, 76dp album art, four 48dp control rows (transport,
        // navigation, two actions), card padding (36 + 36 + 32), two gaps (28) and margins (16):
        // at least 416dp, so a 400dp-tall portrait window must scroll.
        assertTrue(LayoutPolicy.calculate(360, 400, 1f, 4).dashboardScrolls);
    }

    @Test
    public void slotCountsMatchVisibleTiles() {
        // Original 2-row grid: columns x 2 minus the library and settings tiles.
        assertEquals(4, LayoutPolicy.calculate(1024, 600, 1f, 3).appSlotsPerPage);
        assertEquals(6, LayoutPolicy.calculate(1024, 600, 1f, 4).appSlotsPerPage);
        assertEquals(8, LayoutPolicy.calculate(1024, 600, 1f, 5).appSlotsPerPage);
        // A single-column window still shows at least one app next to the two fixed tiles.
        LayoutPolicy.LayoutSpec split = LayoutPolicy.calculate(320, 600, 1f, 5);
        assertEquals(1, split.gridColumns);
        assertTrue(split.appSlotsPerPage >= 1);
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
        assertTrue(shortSpec.dockScrolls);

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
                    int gridInner = spec.contentWidthDp + 2 * spec.marginDp - 2 * 7;
                    int tileWidth = gridInner / spec.gridColumns - 2 * 7;
                    assertTrue(label + " tile " + tileWidth, spec.gridColumns == 1
                            || tileWidth >= LayoutPolicy.MIN_TILE_WIDTH_DP);
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
        // Keypad keeps 7 columns at its designed width and drops columns when narrow.
        assertEquals(7, wide.keypadColumns(620));
        assertTrue(narrow.keypadColumns(620) < 7);
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
        assertEquals(1, shortWindow.gridRows);
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
