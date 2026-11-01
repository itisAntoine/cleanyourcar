package com.cleanyourcar.launcher.config;

import com.cleanyourcar.launcher.NavPayloadParser;
import com.cleanyourcar.launcher.PlusCodeCodec;
import com.cleanyourcar.launcher.ThemeConfig;
import com.cleanyourcar.launcher.config.NavigationReference.LocationSample;
import com.cleanyourcar.launcher.config.NavigationReference.ReferenceResult;
import com.cleanyourcar.launcher.config.NavigationReference.SavedReference;

import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class NavigationReferenceTest {

    private static final double HCM_LAT = 10.7769;
    private static final double HCM_LON = 106.7009;
    private static final int HANOI_INDEX = 1;
    private static final long ONE_MINUTE_MS = 60_000L;

    private static LocationSample freshHcmFix() {
        return new LocationSample(true, HCM_LAT, HCM_LON, ONE_MINUTE_MS, 25f);
    }

    @Test
    public void shortCodeRequiresExplicitOrValidLocationReference() {
        assertTrue(NavPayloadParser.requiresReference("QMVX+2P"));
        assertTrue(NavPayloadParser.requiresReference("QMVX2P"));

        ReferenceResult missingReference =
                NavigationReference.resolve(LocationSample.NONE, SavedReference.NONE);
        assertFalse(missingReference.available);
        assertEquals(NavigationReference.Reason.NO_REFERENCE, missingReference.reason);
        assertNull(NavPayloadParser.parse("QMVX+2P", missingReference));

        // Permission denied with otherwise plausible coordinates is not a reference.
        ReferenceResult denied = NavigationReference.resolve(
                new LocationSample(false, HCM_LAT, HCM_LON, ONE_MINUTE_MS, 25f),
                SavedReference.NONE);
        assertFalse(denied.available);

        // Out-of-range, non-finite, or inaccurate fixes are not references either.
        assertFalse(NavigationReference.resolve(
                new LocationSample(true, 95.0, HCM_LON, ONE_MINUTE_MS, 25f),
                SavedReference.NONE).available);
        assertFalse(NavigationReference.resolve(
                new LocationSample(true, Double.NaN, HCM_LON, ONE_MINUTE_MS, 25f),
                SavedReference.NONE).available);
        assertFalse(NavigationReference.resolve(
                new LocationSample(true, HCM_LAT, HCM_LON, ONE_MINUTE_MS, 1500f),
                SavedReference.NONE).available);

        ReferenceResult fromLocation =
                NavigationReference.resolve(freshHcmFix(), SavedReference.NONE);
        assertTrue(fromLocation.available);
        assertEquals(NavigationReference.Source.CURRENT_LOCATION, fromLocation.source);
        PlusCodeCodec.LatLon viaLocation = NavPayloadParser.parse("QMVX+2P", fromLocation);
        assertNotNull(viaLocation);
        assertEquals(10.7925625, viaLocation.lat, 0.001);

        ReferenceResult fromSaved = NavigationReference.resolve(LocationSample.NONE,
                SavedReference.fromCityIndex(0));
        assertTrue(fromSaved.available);
        assertEquals(NavigationReference.Source.SAVED, fromSaved.source);
        PlusCodeCodec.LatLon viaSaved = NavPayloadParser.parse("QMVX+2P", fromSaved);
        assertNotNull(viaSaved);
        assertEquals(106.6993125, viaSaved.lon, 0.001);
    }

    @Test
    public void fullCodeDoesNotNeedLocalReference() {
        assertFalse(NavPayloadParser.requiresReference("7P28QMVX+2P"));
        assertFalse(NavPayloadParser.requiresReference("7P28QMVX2P"));
        assertFalse(NavPayloadParser.requiresReference("10.7721, 106.6983"));

        ReferenceResult missingReference =
                NavigationReference.resolve(LocationSample.NONE, SavedReference.NONE);
        assertFalse(missingReference.available);

        PlusCodeCodec.LatLon full = NavPayloadParser.parse("7P28QMVX+2P", missingReference);
        assertNotNull(full);
        assertEquals(10.7925625, full.lat, 0.001);
        assertEquals(106.6993125, full.lon, 0.001);

        PlusCodeCodec.LatLon coords = NavPayloadParser.parse("geo:10.7721,106.6983",
                missingReference);
        assertNotNull(coords);
        assertEquals(10.7721, coords.lat, 0.0001);
    }

    @Test
    public void noneChoiceRejectsShortCodesEvenWithGrantedPermission() {
        SavedReference hanoi = SavedReference.fromCityIndex(HANOI_INDEX);
        // "None" disables both GPS and any leftover saved city, even with consent and a fix.
        ReferenceResult none = NavigationReference.resolve(NavigationReference.Mode.NONE, true,
                freshHcmFix(), hanoi);
        assertFalse(none.available);
        assertEquals(NavigationReference.Reason.NO_REFERENCE, none.reason);
        assertNull(NavPayloadParser.parse("QMVX+2P", none));
        // Full codes keep working without a reference.
        assertNotNull(NavPayloadParser.parse("7P28QMVX+2P", none));
    }

    @Test
    public void grantedPermissionWithoutConsentDoesNotUseGps() {
        SavedReference hanoi = SavedReference.fromCityIndex(HANOI_INDEX);
        for (NavigationReference.Mode mode : NavigationReference.Mode.values()) {
            ReferenceResult noConsent = NavigationReference.resolve(mode, false, freshHcmFix(),
                    SavedReference.NONE);
            assertFalse(mode.name(), noConsent.available);
        }
        // Without consent the explicit saved city is used instead of the fix.
        ReferenceResult cityFallback = NavigationReference.resolve(
                NavigationReference.Mode.CURRENT_LOCATION, false, freshHcmFix(), hanoi);
        assertEquals(NavigationReference.Source.SAVED, cityFallback.source);
        // City mode never uses GPS, even with consent.
        assertEquals(NavigationReference.Source.SAVED, NavigationReference.resolve(
                NavigationReference.Mode.CITY, true, freshHcmFix(), hanoi).source);
        // Consent plus current-location mode is the only way GPS becomes the reference.
        assertEquals(NavigationReference.Source.CURRENT_LOCATION, NavigationReference.resolve(
                NavigationReference.Mode.CURRENT_LOCATION, true, freshHcmFix(), hanoi).source);

        // Stored mode keys round-trip; unknown or missing values default to city-only.
        assertEquals(NavigationReference.Mode.NONE,
                NavigationReference.Mode.fromKey(NavigationReference.Mode.NONE.key()));
        assertEquals(NavigationReference.Mode.CURRENT_LOCATION, NavigationReference.Mode.fromKey(
                NavigationReference.Mode.CURRENT_LOCATION.key()));
        assertEquals(NavigationReference.Mode.CITY, NavigationReference.Mode.fromKey(null));
        assertEquals(NavigationReference.Mode.CITY, NavigationReference.Mode.fromKey("bogus"));
    }

    @Test
    public void staleLocationDoesNotOverrideSavedCity() {
        SavedReference hanoi = SavedReference.fromCityIndex(HANOI_INDEX);
        assertTrue(hanoi.present);

        LocationSample stale = new LocationSample(true, HCM_LAT, HCM_LON,
                NavigationReference.MAX_LOCATION_AGE_MS + 1, 25f);
        ReferenceResult result = NavigationReference.resolve(stale, hanoi);
        assertTrue(result.available);
        assertEquals(NavigationReference.Source.SAVED, result.source);
        assertEquals(PlusCodeCodec.VIETNAM_PRESETS[HANOI_INDEX].lat, result.lat, 1e-9);
        assertEquals(PlusCodeCodec.VIETNAM_PRESETS[HANOI_INDEX].lon, result.lon, 1e-9);

        // Exactly ten minutes old and 1,000m accurate is still acceptable.
        LocationSample boundary = new LocationSample(true, HCM_LAT, HCM_LON,
                NavigationReference.MAX_LOCATION_AGE_MS,
                NavigationReference.MAX_LOCATION_ACCURACY_M);
        assertEquals(NavigationReference.Source.CURRENT_LOCATION,
                NavigationReference.resolve(boundary, hanoi).source);

        // A stale fix with no saved city leaves the reference explicitly unavailable.
        assertFalse(NavigationReference.resolve(stale, SavedReference.NONE).available);
    }

    @Test
    public void savedCityIndexMigratesToExplicitReferenceWithoutImplicitFallback()
            throws IOException {
        Map<String, Object> legacy = new HashMap<>();
        legacy.put(ThemeConfig.KEY_CITY_INDEX, HANOI_INDEX);
        LauncherConfiguration migrated = newStore().migrateLegacy(legacy).configuration;
        SavedReference saved = migrated.savedNavigationReference;
        assertTrue(saved.present);
        assertEquals(Integer.valueOf(HANOI_INDEX), saved.cityIndex);
        assertEquals(PlusCodeCodec.VIETNAM_PRESETS[HANOI_INDEX].name, saved.label);

        LauncherConfiguration fresh =
                newStore().migrateLegacy(Collections.<String, Object>emptyMap()).configuration;
        assertSame(SavedReference.NONE, fresh.savedNavigationReference);
        assertFalse(fresh.savedNavigationReference.present);

        assertSame(SavedReference.NONE, SavedReference.fromCityIndex(null));
        assertSame(SavedReference.NONE, SavedReference.fromCityIndex(-1));
        assertSame(SavedReference.NONE,
                SavedReference.fromCityIndex(PlusCodeCodec.VIETNAM_PRESETS.length));
    }

    private static ConfigurationStore newStore() {
        return new ConfigurationStore(new ConfigurationStore.Storage() {
            byte[] bytes;

            @Override
            public byte[] read() {
                return bytes;
            }

            @Override
            public void writeAtomically(byte[] data) {
                bytes = data.clone();
            }

            @Override
            public void moveAside(String suffix) {
                bytes = null;
            }
        }, 1);
    }
}
