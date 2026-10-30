package com.cleanyourcar.launcher.layout;

/**
 * Density-independent window-fit policy for the launcher's home screen and dialogs.
 *
 * <p>Inputs are the window's available size in dp (after system-bar and cutout insets), the
 * current font scale, and the user's grid-column preference. The result is a pure value the UI
 * converts to pixels with the display density. Heights are conservative estimates; any surface
 * whose estimate does not fit is marked scrollable so essential actions stay reachable.
 */
public final class LayoutPolicy {

    public enum Mode {
        /** Dashboard cards stacked vertically (narrow or portrait windows). */
        STACKED,
        /** Existing two-column dashboard with the user's column ordering. */
        SPLIT
    }

    public enum DockPlacement {
        /** Existing side dock on the user's chosen side. */
        SIDE,
        /** Narrower side dock with 48dp targets for short or narrow windows. */
        SIDE_COMPACT
    }

    public static final int MIN_TOUCH_TARGET_DP = 48;
    public static final int MAX_CONTENT_WIDTH_DP = 1200;
    public static final int MIN_SPLIT_COLUMN_DP = 240;
    public static final int MIN_TILE_WIDTH_DP = 112;
    public static final int MAX_TILE_HEIGHT_DP = 240;
    public static final int TILE_GAP_DP = 14;
    public static final int TILE_ICON_DP = 78;
    public static final int CARD_GAP_DP = 14;
    public static final int DIALOG_MARGIN_DP = 16;
    public static final int GRID_PADDING_DP = 7;
    /** Inner padding of the dashboard cards (horizontal). */
    public static final int CARD_PADDING_DP = 20;
    public static final int TRANSPORT_GAP_DP = 12;
    public static final int TRANSPORT_BUTTONS = 3;
    public static final int KEYPAD_PADDING_DP = 22;
    public static final int KEYPAD_KEY_MARGIN_DP = 5;
    public static final int KEYPAD_MAX_COLUMNS = 7;

    private static final int REGULAR_MARGIN_DP = 14;
    private static final int COMPACT_MARGIN_DP = 8;
    private static final int REGULAR_DOCK_MIN_WIDTH_DP = 84;
    private static final int COMPACT_DOCK_MIN_WIDTH_DP = 64;
    private static final int REGULAR_DOCK_BUTTON_DP = 58;
    private static final int COMPACT_DOCK_BUTTON_DP = MIN_TOUCH_TARGET_DP;
    private static final int REGULAR_DOCK_TOGGLE_DP = 56;
    private static final int COMPACT_DOCK_TOGGLE_DP = MIN_TOUCH_TARGET_DP;
    private static final int REGULAR_NAV_BUTTON_DP = 92;
    private static final int COMPACT_NAV_BUTTON_DP = 56;
    private static final int REGULAR_TRANSPORT_DP = 64;
    private static final int COMPACT_TRANSPORT_DP = MIN_TOUCH_TARGET_DP;
    private static final int DOCK_BUTTON_COUNT = 4;
    private static final int ALBUM_ART_DP = 76;
    private static final float LINE_HEIGHT_FACTOR = 1.2f;
    private static final float DIGIT_WIDTH_FACTOR = 0.58f;
    private static final int CLOCK_CHARS = 5;

    private LayoutPolicy() {
    }

    /** Computed layout for one window size; compare with {@link #equals} to detect changes. */
    public static final class LayoutSpec {
        public final Mode mode;
        public final DockPlacement dock;
        public final int minimumTouchTargetDp;

        public final int marginDp;
        public final int dockWidthDp;
        public final int dockButtonDp;
        public final int dockButtonMarginDp;
        public final int dockToggleDp;
        public final int dockPaddingDp;
        public final int dockSidePaddingDp;
        public final float dockClockSp;
        public final int dockHeightDp;
        public final boolean dockScrolls;

        /** Width left for dashboard/grid content after the dock and margins. */
        public final int availableContentWidthDp;
        /** Width actually used by content; capped on ultrawide windows. */
        public final int contentWidthDp;

        public final int navButtonHeightDp;
        public final int transportHeightDp;
        /** Width of each media transport button in the music card. */
        public final int transportButtonWidthDp;
        public final int actionRowMinHeightDp;
        public final int dashboardHeightDp;
        public final boolean dashboardScrolls;

        public final int gridColumns;
        public final int gridRows;
        public final int appSlotsPerPage;
        public final int gridTileHeightDp;
        public final int minimumTileHeightDp;
        public final boolean gridScrolls;

        public final int dialogMaxWidthDp;
        public final int dialogMaxHeightDp;

        LayoutSpec(Mode mode, DockPlacement dock, int marginDp, int dockWidthDp,
                int dockButtonDp, int dockButtonMarginDp, int dockToggleDp, int dockPaddingDp,
                int dockSidePaddingDp, float dockClockSp,
                int dockHeightDp, boolean dockScrolls, int availableContentWidthDp,
                int contentWidthDp, int navButtonHeightDp, int transportHeightDp,
                int transportButtonWidthDp,
                int actionRowMinHeightDp, int dashboardHeightDp, boolean dashboardScrolls,
                int gridColumns, int gridRows, int appSlotsPerPage, int gridTileHeightDp,
                int minimumTileHeightDp, boolean gridScrolls, int dialogMaxWidthDp,
                int dialogMaxHeightDp) {
            this.mode = mode;
            this.dock = dock;
            this.minimumTouchTargetDp = MIN_TOUCH_TARGET_DP;
            this.marginDp = marginDp;
            this.dockWidthDp = dockWidthDp;
            this.dockButtonDp = dockButtonDp;
            this.dockButtonMarginDp = dockButtonMarginDp;
            this.dockToggleDp = dockToggleDp;
            this.dockPaddingDp = dockPaddingDp;
            this.dockSidePaddingDp = dockSidePaddingDp;
            this.dockClockSp = dockClockSp;
            this.dockHeightDp = dockHeightDp;
            this.dockScrolls = dockScrolls;
            this.availableContentWidthDp = availableContentWidthDp;
            this.contentWidthDp = contentWidthDp;
            this.navButtonHeightDp = navButtonHeightDp;
            this.transportHeightDp = transportHeightDp;
            this.transportButtonWidthDp = transportButtonWidthDp;
            this.actionRowMinHeightDp = actionRowMinHeightDp;
            this.dashboardHeightDp = dashboardHeightDp;
            this.dashboardScrolls = dashboardScrolls;
            this.gridColumns = gridColumns;
            this.gridRows = gridRows;
            this.appSlotsPerPage = appSlotsPerPage;
            this.gridTileHeightDp = gridTileHeightDp;
            this.minimumTileHeightDp = minimumTileHeightDp;
            this.gridScrolls = gridScrolls;
            this.dialogMaxWidthDp = dialogMaxWidthDp;
            this.dialogMaxHeightDp = dialogMaxHeightDp;
        }

        /** A dialog's preferred width bounded by the window. */
        public int dialogWidthDp(int preferredWidthDp) {
            return Math.max(1, Math.min(preferredWidthDp, dialogMaxWidthDp));
        }

        /**
         * Plus Code keypad columns for a dialog of the preferred width: as many as keep every
         * key at least {@link #MIN_TOUCH_TARGET_DP} wide, up to {@link #KEYPAD_MAX_COLUMNS}.
         */
        public int keypadColumns(int preferredDialogWidthDp) {
            int inner = dialogWidthDp(preferredDialogWidthDp) - 2 * KEYPAD_PADDING_DP;
            return fitColumns(inner, MIN_TOUCH_TARGET_DP + 2 * KEYPAD_KEY_MARGIN_DP, 0,
                    KEYPAD_MAX_COLUMNS);
        }

        public boolean isContentCapped() {
            return contentWidthDp < availableContentWidthDp;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LayoutSpec)) return false;
            LayoutSpec s = (LayoutSpec) o;
            return mode == s.mode && dock == s.dock && marginDp == s.marginDp
                    && dockWidthDp == s.dockWidthDp && dockButtonDp == s.dockButtonDp
                    && dockButtonMarginDp == s.dockButtonMarginDp
                    && dockToggleDp == s.dockToggleDp
                    && dockPaddingDp == s.dockPaddingDp
                    && dockSidePaddingDp == s.dockSidePaddingDp
                    && Float.compare(dockClockSp, s.dockClockSp) == 0
                    && dockHeightDp == s.dockHeightDp && dockScrolls == s.dockScrolls
                    && availableContentWidthDp == s.availableContentWidthDp
                    && contentWidthDp == s.contentWidthDp
                    && navButtonHeightDp == s.navButtonHeightDp
                    && transportHeightDp == s.transportHeightDp
                    && transportButtonWidthDp == s.transportButtonWidthDp
                    && actionRowMinHeightDp == s.actionRowMinHeightDp
                    && dashboardHeightDp == s.dashboardHeightDp
                    && dashboardScrolls == s.dashboardScrolls
                    && gridColumns == s.gridColumns && gridRows == s.gridRows
                    && appSlotsPerPage == s.appSlotsPerPage
                    && gridTileHeightDp == s.gridTileHeightDp
                    && minimumTileHeightDp == s.minimumTileHeightDp
                    && gridScrolls == s.gridScrolls
                    && dialogMaxWidthDp == s.dialogMaxWidthDp
                    && dialogMaxHeightDp == s.dialogMaxHeightDp;
        }

        @Override
        public int hashCode() {
            int h = mode.hashCode();
            h = 31 * h + dock.hashCode();
            h = 31 * h + dockWidthDp;
            h = 31 * h + dockPaddingDp;
            h = 31 * h + dockSidePaddingDp;
            h = 31 * h + Float.floatToIntBits(dockClockSp);
            h = 31 * h + transportButtonWidthDp;
            h = 31 * h + dockHeightDp;
            h = 31 * h + contentWidthDp;
            h = 31 * h + dashboardHeightDp;
            h = 31 * h + gridColumns;
            h = 31 * h + gridRows;
            h = 31 * h + gridTileHeightDp;
            h = 31 * h + dialogMaxWidthDp;
            h = 31 * h + dialogMaxHeightDp;
            return h;
        }

        @Override
        public String toString() {
            return "LayoutSpec{" + mode + ", " + dock + ", dock=" + dockWidthDp + "dp, content="
                    + contentWidthDp + "/" + availableContentWidthDp + "dp, grid=" + gridColumns
                    + "x" + gridRows + "@" + gridTileHeightDp + "dp, dashboardScrolls="
                    + dashboardScrolls + ", dockScrolls=" + dockScrolls + ", gridScrolls="
                    + gridScrolls + "}";
        }
    }

    /**
     * @param widthDp available window width in dp, excluding insets
     * @param heightDp available window height in dp, excluding insets
     * @param fontScale system font scale (1.0 = default)
     * @param preferredColumns user's grid-column preference (3..5); bounded by fit
     */
    public static LayoutSpec calculate(int widthDp, int heightDp, float fontScale,
            int preferredColumns) {
        int w = Math.max(1, widthDp);
        int h = Math.max(1, heightDp);
        float fs = Float.isNaN(fontScale) ? 1f : Math.max(0.5f, fontScale);
        int pref = Math.max(1, preferredColumns);
        boolean portrait = h > w;

        // Dock: keep the existing side dock unless it cannot fit the window comfortably.
        float regularClockSp = 23f;
        int regularDockHeight = dockHeight(fs, regularClockSp, REGULAR_DOCK_BUTTON_DP, 6,
                REGULAR_DOCK_TOGGLE_DP, 16);
        boolean compact = portrait || w < 720
                || regularDockHeight > h - 2 * REGULAR_MARGIN_DP;
        DockPlacement dock = compact ? DockPlacement.SIDE_COMPACT : DockPlacement.SIDE;
        int margin = compact ? COMPACT_MARGIN_DP : REGULAR_MARGIN_DP;
        float clockSp = compact ? 18f : regularClockSp;
        int dockButton = compact ? COMPACT_DOCK_BUTTON_DP : REGULAR_DOCK_BUTTON_DP;
        int dockButtonMargin = compact ? 4 : 6;
        int dockToggle = compact ? COMPACT_DOCK_TOGGLE_DP : REGULAR_DOCK_TOGGLE_DP;
        int dockPadding = compact ? 8 : 16;
        int dockSidePadding = compact ? 6 : 8;
        int dockMinWidth = compact ? COMPACT_DOCK_MIN_WIDTH_DP : REGULAR_DOCK_MIN_WIDTH_DP;
        // Large fonts widen the clock, but never so far that the content loses its 48dp
        // transport buttons; past that point the clock text is scaled down instead.
        int minMusicCard = TRANSPORT_BUTTONS * MIN_TOUCH_TARGET_DP
                + (TRANSPORT_BUTTONS - 1) * TRANSPORT_GAP_DP + 2 * CARD_PADDING_DP;
        int dockMaxWidth = Math.max(dockMinWidth, w - 3 * margin - minMusicCard);
        int naturalDockWidth = ceil(clockSp * fs * DIGIT_WIDTH_FACTOR * CLOCK_CHARS)
                + 2 * dockSidePadding;
        int dockWidth = Math.max(dockMinWidth, Math.min(naturalDockWidth, dockMaxWidth));
        if (naturalDockWidth > dockWidth) {
            clockSp = (dockWidth - 2 * dockSidePadding) / (fs * DIGIT_WIDTH_FACTOR * CLOCK_CHARS);
        }
        int dockHeight = compact || clockSp != regularClockSp
                ? dockHeight(fs, clockSp, dockButton, dockButtonMargin, dockToggle, dockPadding)
                : regularDockHeight;
        boolean dockScrolls = dockHeight > h - 2 * margin;

        // Content width: what remains next to the dock, capped on ultrawide windows.
        int availableContent = Math.max(1, w - dockWidth - margin - 2 * margin);
        int contentWidth = Math.min(availableContent, MAX_CONTENT_WIDTH_DP);
        int columnWidth = (contentWidth - margin) / 2;
        Mode mode = portrait || columnWidth < MIN_SPLIT_COLUMN_DP ? Mode.STACKED : Mode.SPLIT;

        // Dashboard: prefer the existing sizes; shrink to 48dp-safe sizes before scrolling.
        int actionRow = Math.max(MIN_TOUCH_TARGET_DP, line(16.5f, fs) + 20);
        int regularDash = dashboardHeight(mode, fs, margin, REGULAR_NAV_BUTTON_DP,
                REGULAR_TRANSPORT_DP, actionRow);
        int navButton;
        int transport;
        int dashHeight;
        if (regularDash <= h) {
            navButton = REGULAR_NAV_BUTTON_DP;
            transport = REGULAR_TRANSPORT_DP;
            dashHeight = regularDash;
        } else {
            navButton = Math.max(COMPACT_NAV_BUTTON_DP, actionRow);
            transport = COMPACT_TRANSPORT_DP;
            dashHeight = dashboardHeight(mode, fs, margin, navButton, transport, actionRow);
        }
        boolean dashScrolls = dashHeight > h;
        int musicCard = mode == Mode.SPLIT ? columnWidth : contentWidth;
        int transportWidth = (musicCard - 2 * CARD_PADDING_DP
                - (TRANSPORT_BUTTONS - 1) * TRANSPORT_GAP_DP) / TRANSPORT_BUTTONS;

        // App grid: columns bounded by fit, rows by height; library + settings tiles always shown.
        // The grid view spans content plus both margins minus its padding; tiles carry margins.
        int gridInner = contentWidth + 2 * margin - 2 * GRID_PADDING_DP;
        int cols = fitColumns(gridInner, MIN_TILE_WIDTH_DP + 2 * GRID_PADDING_DP, 0, pref);
        int minTile = 32 + TILE_ICON_DP + 10 + line(16.5f, fs);
        int pageDots = 2 * line(14f, fs) + 4;
        int gridHeight = Math.max(1, h - GRID_PADDING_DP - 2 * GRID_PADDING_DP - pageDots);
        int fitRows = gridHeight / (minTile + TILE_GAP_DP);
        int targetRows = Math.max(2, ceilDiv(pref * 2, cols));
        int rows = Math.max(1, Math.min(fitRows, targetRows));
        int tileHeight = Math.max(minTile,
                Math.min(MAX_TILE_HEIGHT_DP, gridHeight / rows - TILE_GAP_DP));
        int slots = Math.max(1, cols * rows - 2);
        boolean gridScrolls = rows * (tileHeight + TILE_GAP_DP) > gridHeight
                || cols * rows < slots + 2;

        int dialogMaxWidth = Math.max(1, w - 2 * DIALOG_MARGIN_DP);
        int dialogMaxHeight = Math.max(1, h - 2 * DIALOG_MARGIN_DP);

        return new LayoutSpec(mode, dock, margin, dockWidth, dockButton, dockButtonMargin,
                dockToggle, dockPadding, dockSidePadding, clockSp, dockHeight, dockScrolls,
                availableContent, contentWidth, navButton, transport, transportWidth, actionRow,
                dashHeight, dashScrolls, cols, rows, slots,
                tileHeight, minTile, gridScrolls, dialogMaxWidth, dialogMaxHeight);
    }

    /** Number of cells of at least {@code minCellDp} plus {@code gapDp} that fit, in 1..max. */
    public static int fitColumns(int availableDp, int minCellDp, int gapDp, int maxColumns) {
        int fit = (availableDp + gapDp) / Math.max(1, minCellDp + gapDp);
        return Math.max(1, Math.min(Math.max(1, maxColumns), fit));
    }

    private static int dockHeight(float fs, float clockSp, int button, int buttonMargin,
            int toggle, int padding) {
        return 2 * padding + line(clockSp, fs) + line(12f, fs)
                + DOCK_BUTTON_COUNT * (button + 2 * buttonMargin) + toggle;
    }

    private static int dashboardHeight(Mode mode, float fs, int margin, int navButton,
            int transport, int actionRow) {
        int segmented = line(17f, fs) + 28 + 8;
        int trackInfo = Math.max(ALBUM_ART_DP, 2 * line(26f, fs) + 6 + line(16f, fs));
        int music = 36 + segmented + trackInfo + transport;
        int nav = 36 + line(30f, fs) + navButton;
        int utilities = 32 + 2 * actionRow + 10;
        int right = nav + CARD_GAP_DP + utilities;
        int body = mode == Mode.SPLIT ? Math.max(music, right) : music + CARD_GAP_DP + right;
        return 2 * margin + body;
    }

    private static int line(float sp, float fs) {
        return ceil(sp * fs * LINE_HEIGHT_FACTOR);
    }

    private static int ceil(float value) {
        return (int) Math.ceil(value);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
