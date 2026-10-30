package com.cleanyourcar.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class ThemeConfigTest {
    @Test
    public void glassmorphismTokens_matchDesignSpec() {
        assertEquals(0xFF1856FF, ThemeConfig.COLOR_PRIMARY);
        assertEquals(0xFF3A344E, ThemeConfig.COLOR_SECONDARY);
        assertEquals(0xFF07CA6B, ThemeConfig.COLOR_SUCCESS);
        assertEquals(0xFFE89558, ThemeConfig.COLOR_WARNING);
        assertEquals(0xFFEA2143, ThemeConfig.COLOR_DANGER);
        assertEquals(0xFFFFFFFF, ThemeConfig.COLOR_TEXT_PRIMARY);
        assertEquals(0xFFB8C4E0, ThemeConfig.COLOR_TEXT_SECONDARY);
    }

    @Test
    public void calculateInSampleSize_downsamples4kWallpaperFor1024x600() {
        assertEquals(1, ThemeConfig.calculateInSampleSize(1024, 600, 1024, 600));
        assertEquals(2, ThemeConfig.calculateInSampleSize(2560, 1440, 1024, 600));
        assertEquals(4, ThemeConfig.calculateInSampleSize(4096, 2400, 1024, 600));
    }

    @Test
    public void themePresets_supportGlassmorphismOledAndCarbonPalettes() {
        ThemeConfig.Palette glass = ThemeConfig.getPalette(ThemeConfig.THEME_GLASSMORPHISM);
        ThemeConfig.Palette oled = ThemeConfig.getPalette(ThemeConfig.THEME_OLED_BLACK);
        ThemeConfig.Palette carbon = ThemeConfig.getPalette(ThemeConfig.THEME_WARM_CARBON);

        assertEquals(0xFF1856FF, glass.accentPrimary);
        assertEquals(0xFFE07A2E, carbon.accentPrimary);
        assertEquals(0xFF7C3AED, oled.accentPrimary);
        assertTrue(glass.name.contains("Liquid Glass"));
    }

    @Test
    public void vectorIconSpecs_provideCleanNonEmojiPathsForAllControls() {
        assertNotNull(VectorIcons.getSpec(VectorIcons.ICON_MAP));
        assertNotNull(VectorIcons.getSpec(VectorIcons.ICON_MUSIC));
        assertNotNull(VectorIcons.getSpec(VectorIcons.ICON_CAMERA));
        assertNotNull(VectorIcons.getSpec(VectorIcons.ICON_EQUALIZER));
        assertNotNull(VectorIcons.getSpec(VectorIcons.ICON_GRID));
        assertNotNull(VectorIcons.getSpec(VectorIcons.ICON_PLAY));
        assertNotNull(VectorIcons.getSpec(VectorIcons.ICON_PAUSE));
        assertNotNull(VectorIcons.getSpec(VectorIcons.ICON_PREV));
        assertNotNull(VectorIcons.getSpec(VectorIcons.ICON_NEXT));
        assertNotNull(VectorIcons.getSpec(VectorIcons.ICON_HASH));
        assertNotNull(VectorIcons.getSpec(VectorIcons.ICON_SUN));
        assertNotNull(VectorIcons.getSpec(VectorIcons.ICON_MOON));
    }

    @Test
    public void glassOpacityPresets_scaleAlphaChannelsPredictablyAcross4Levels() {
        ThemeConfig.Palette clear = ThemeConfig.getPalette(
                ThemeConfig.THEME_GLASSMORPHISM, ThemeConfig.OPACITY_CRYSTAL);
        ThemeConfig.Palette balanced = ThemeConfig.getPalette(
                ThemeConfig.THEME_GLASSMORPHISM, ThemeConfig.OPACITY_BALANCED);
        ThemeConfig.Palette frosted = ThemeConfig.getPalette(
                ThemeConfig.THEME_GLASSMORPHISM, ThemeConfig.OPACITY_FROSTED);
        ThemeConfig.Palette solid = ThemeConfig.getPalette(
                ThemeConfig.THEME_GLASSMORPHISM, ThemeConfig.OPACITY_SOLID);

        int alphaClear = (clear.cardTop >>> 24) & 0xFF;
        int alphaBalanced = (balanced.cardTop >>> 24) & 0xFF;
        int alphaFrosted = (frosted.cardTop >>> 24) & 0xFF;
        int alphaSolid = (solid.cardTop >>> 24) & 0xFF;

        assertTrue(alphaClear < alphaBalanced);
        assertTrue(alphaBalanced < alphaFrosted);
        assertTrue(alphaFrosted < alphaSolid);
        assertEquals("Trong suốt cao (Crystal)", ThemeConfig.getOpacityLabel(ThemeConfig.OPACITY_CRYSTAL));
        assertEquals("Đặc chống chói (Solid)", ThemeConfig.getOpacityLabel(ThemeConfig.OPACITY_SOLID));
    }

    @Test
    public void i18n_supportsVietnameseEnglishAndPopularGlobalLanguages() {
        assertEquals(6, I18n.LANGUAGE_NAMES.length);
        assertEquals("Tiếng Việt", I18n.LANGUAGE_NAMES[I18n.LANG_VI]);
        assertEquals("English", I18n.LANGUAGE_NAMES[I18n.LANG_EN]);

        assertEquals("Google Maps", I18n.t(I18n.LANG_VI, I18n.KEY_GOOGLE_MAPS));
        assertEquals("Google Maps", I18n.t(I18n.LANG_EN, I18n.KEY_GOOGLE_MAPS));
        assertEquals("Nhạc Bluetooth", I18n.t(I18n.LANG_VI, I18n.KEY_BT_MUSIC));
        assertEquals("Bluetooth Music", I18n.t(I18n.LANG_EN, I18n.KEY_BT_MUSIC));
        assertEquals("Nhập điểm đến", I18n.t(I18n.LANG_VI, I18n.KEY_PLUS_CODE));
        assertEquals("Enter Destination", I18n.t(I18n.LANG_EN, I18n.KEY_PLUS_CODE));
        assertNotNull(VectorIcons.getSpec(VectorIcons.ICON_GOOGLE_MAPSPIN));
    }
}
