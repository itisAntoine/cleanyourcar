package com.cleanyourcar.launcher;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class AppRepositoryOrderTest {
    @Test
    public void orderAndFilterPackages_respectsSavedOrderExcludesHiddenAndSelf() {
        List<String> installed = Arrays.asList(
                "com.cleanyourcar.launcher",
                "com.android.settings",
                "com.google.android.apps.maps",
                "com.fvsm.camera",
                "com.acloud.stub.localmusic");
        List<String> savedOrder = Arrays.asList(
                "com.google.android.apps.maps",
                "com.acloud.stub.localmusic");
        HashSet<String> hidden = new HashSet<>(Collections.singletonList("com.android.settings"));

        List<String> result = AppRepository.orderAndFilterPackages(
                installed, savedOrder, hidden, "com.cleanyourcar.launcher");

        assertEquals(Arrays.asList(
                "com.google.android.apps.maps",
                "com.acloud.stub.localmusic",
                "com.fvsm.camera"), result);
    }

    @Test
    public void isVisibleByDefault_onlyShowsEssentialCarAppsUntilUserExplicitlyUnhides() {
        Set<String> unhidden = new HashSet<>(Collections.singletonList("com.spotify.music"));
        Set<String> hidden = new HashSet<>(Collections.singletonList("com.acloud.stub.localradio"));

        // Core car apps are visible by default unless explicitly hidden
        assertTrue(AppRepository.isPackageVisible(
                "com.google.android.apps.maps", hidden, unhidden));
        assertTrue(AppRepository.isPackageVisible(
                "com.autochips.bluetooth", hidden, unhidden));
        assertFalse(AppRepository.isPackageVisible(
                "com.acloud.stub.localradio", hidden, unhidden));

        // Non-essential Google/phone bloatware is hidden by default unless explicitly unhidden
        assertFalse(AppRepository.isPackageVisible(
                "com.google.android.calendar", hidden, unhidden));
        assertFalse(AppRepository.isPackageVisible(
                "com.google.android.apps.bard", hidden, unhidden));
        assertFalse(AppRepository.isPackageVisible(
                "com.google.android.youtube", hidden, unhidden));
        assertTrue(AppRepository.isPackageVisible(
                "com.spotify.music", hidden, unhidden));
    }

    @Test
    public void isPackageVisible_genericInstallShowsDiscoveredAppsUnlessHidden() {
        Set<String> hidden = new HashSet<>(Collections.singletonList("com.acloud.stub.localradio"));
        Set<String> unhidden = Collections.emptySet();

        assertTrue(AppRepository.isPackageVisible(
                "com.google.android.youtube", hidden, unhidden, null));
        assertTrue(AppRepository.isPackageVisible(
                "com.google.android.apps.maps", hidden, unhidden, null));
        assertFalse(AppRepository.isPackageVisible(
                "com.acloud.stub.localradio", hidden, unhidden, null));

        Set<String> curated = AppRepository.defaultVisiblePackages(true);
        assertFalse(AppRepository.isPackageVisible(
                "com.google.android.youtube", hidden, unhidden, curated));
        assertTrue(AppRepository.isPackageVisible(
                "com.fvsm.camera", hidden, unhidden, curated));
        assertNull(AppRepository.defaultVisiblePackages(false));
    }
}
