package com.cleanyourcar.launcher.compat;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProfileMatcherTest {

    private static final String BOARD = "evb3561sv_w_65_m0";
    private static final String DISPLAY = "YT3518C_00008_V008";

    private static String fixture(String name) throws IOException {
        InputStream in = ProfileMatcherTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull("missing fixture resource " + name, in);
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static DeviceProfile xyauto() throws Exception {
        return ProfileCodec.parse(fixture("valid-xyauto.json"), 1);
    }

    private static DeviceProfile generic() throws Exception {
        return ProfileCodec.parse(fixture("valid-generic.json"), 1);
    }

    private static DeviceProfile profile(String id, String platform, int sdkMin, int sdkMax,
            Map<String, String> properties) {
        return new DeviceProfile(1, id, 1, 1, platform, "generic",
                new DeviceProfile.Match(sdkMin, sdkMax, properties),
                Collections.<String, DeviceProfile.RoleDefault>emptyMap(),
                Collections.<String, String>emptyMap(), Collections.<String>emptyList(),
                new DeviceProfile.Evidence("reported", null, null,
                        Collections.<String>emptyList()));
    }

    private static Map<String, String> props(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static EnvironmentFacts facts(String platform, int sdk, String... keyValues) {
        return EnvironmentFacts.of(platform, sdk, props(keyValues));
    }

    @Test
    public void match_allDeclaredFactsEqualIsExact() throws Exception {
        EnvironmentFacts device = facts("aftermarket", 23, "board", BOARD,
                "manufacturer", "alps", "display", DISPLAY, "product", "full_evb");

        ProfileMatcher.MatchResult result = ProfileMatcher.match(
                Arrays.asList(generic(), xyauto()), device);

        assertEquals(ProfileMatcher.Status.EXACT, result.status);
        assertEquals("xyauto-yt3518c", result.profile().profileId);
        assertTrue(result.missingFacts.isEmpty());
    }

    @Test
    public void match_missingRequiredFactIsPartial() throws Exception {
        EnvironmentFacts device = facts("aftermarket", 23, "board", BOARD,
                "manufacturer", "alps", "display", "unknown");

        ProfileMatcher.MatchResult missingFactResult = ProfileMatcher.match(
                Arrays.asList(generic(), xyauto()), device);

        assertEquals(ProfileMatcher.Status.PARTIAL, missingFactResult.status);
        assertEquals(1, missingFactResult.candidates.size());
        assertEquals("xyauto-yt3518c", missingFactResult.candidates.get(0).profileId);
        assertEquals(Collections.singleton("display"), missingFactResult.missingFacts);
        assertNull(missingFactResult.profile());
    }

    @Test
    public void match_sameBrandIsNotExact() throws Exception {
        EnvironmentFacts sameBrand = facts("aftermarket", 23, "manufacturer", "alps",
                "board", "other_board", "display", "OTHER_V001");

        ProfileMatcher.MatchResult result = ProfileMatcher.match(
                Collections.singletonList(xyauto()), sameBrand);
        assertEquals(ProfileMatcher.Status.UNKNOWN, result.status);
        assertTrue(result.candidates.isEmpty());

        EnvironmentFacts onlyBrandKnown = facts("aftermarket", 23, "manufacturer", "alps");
        ProfileMatcher.MatchResult brandOnly = ProfileMatcher.match(
                Collections.singletonList(xyauto()), onlyBrandKnown);
        assertEquals(ProfileMatcher.Status.PARTIAL, brandOnly.status);
        assertEquals(new java.util.TreeSet<>(Arrays.asList("board", "display")),
                brandOnly.missingFacts);
    }

    @Test
    public void match_twoExactProfilesRequiresChoice() {
        DeviceProfile b = profile("vendor-b", "aftermarket", 23, 30, props("board", "x1"));
        DeviceProfile a = profile("vendor-a", "aftermarket", 23, 30,
                props("board", "x1", "manufacturer", "acme"));
        EnvironmentFacts device = facts("aftermarket", 26, "board", "x1",
                "manufacturer", "acme");

        ProfileMatcher.MatchResult result = ProfileMatcher.match(Arrays.asList(b, a), device);

        assertEquals(ProfileMatcher.Status.CONFLICT, result.status);
        assertEquals(2, result.candidates.size());
        assertEquals("vendor-a", result.candidates.get(0).profileId);
        assertEquals("vendor-b", result.candidates.get(1).profileId);
        assertNull(result.profile());
    }

    @Test
    public void match_genericFallbackOnlyWhenNoSpecificCandidate() throws Exception {
        EnvironmentFacts otherDevice = facts("aftermarket", 29, "manufacturer", "acme");
        ProfileMatcher.MatchResult result = ProfileMatcher.match(
                Arrays.asList(xyauto(), generic()), otherDevice);
        assertEquals(ProfileMatcher.Status.EXACT, result.status);
        assertEquals("generic-android", result.profile().profileId);
    }

    @Test
    public void match_platformAndSdkMustMatch() throws Exception {
        EnvironmentFacts wrongSdk = facts("aftermarket", 24, "board", BOARD,
                "manufacturer", "alps", "display", DISPLAY);
        assertEquals(ProfileMatcher.Status.UNKNOWN, ProfileMatcher.match(
                Collections.singletonList(xyauto()), wrongSdk).status);

        EnvironmentFacts aaos = facts("aaos", 33, "manufacturer", "acme");
        assertEquals(ProfileMatcher.Status.UNKNOWN, ProfileMatcher.match(
                Arrays.asList(xyauto(), generic()), aaos).status);
        assertEquals(ProfileMatcher.Status.UNKNOWN, ProfileMatcher.match(
                Collections.<DeviceProfile>emptyList(), aaos).status);
    }

    @Test
    public void match_conflictingAaosProfilesRequireChoice() {
        DeviceProfile one = profile("aaos-one", "aaos", 30, 35, props("product", "car"));
        DeviceProfile two = profile("aaos-two", "aaos", 33, 34, props("product", "car"));
        EnvironmentFacts device = facts("aaos", 33, "product", "car");

        List<DeviceProfile> profiles = Arrays.asList(two, one);
        ProfileMatcher.MatchResult result = ProfileMatcher.match(profiles, device);
        assertEquals(ProfileMatcher.Status.CONFLICT, result.status);
        assertEquals("aaos-one", result.candidates.get(0).profileId);
    }

    @Test
    public void facts_sdkComesFromApiLevelNotReleaseText() {
        Map<String, String> raw = new HashMap<>();
        raw.put("release", "14");
        raw.put("manufacturer", "  ");
        raw.put("board", "unknown");
        raw.put("product", "car");
        EnvironmentFacts facts = EnvironmentFacts.of("aaos", 34, raw);

        assertEquals(34, facts.sdk);
        assertEquals(Collections.singletonMap("product", "car"), facts.buildProperties);

        DeviceProfile releaseMatched = profile("aaos-14", "aaos", 14, 14,
                props("product", "car"));
        assertEquals(ProfileMatcher.Status.UNKNOWN, ProfileMatcher.match(
                Collections.singletonList(releaseMatched), facts).status);
    }

    @Test
    public void facts_environmentKeyIsDeterministic() {
        EnvironmentFacts first = EnvironmentFacts.of("aftermarket", 23,
                props("board", BOARD, "manufacturer", "alps"));
        EnvironmentFacts reordered = EnvironmentFacts.of("aftermarket", 23,
                props("manufacturer", "alps", "board", BOARD));
        EnvironmentFacts firmwareChanged = EnvironmentFacts.of("aftermarket", 23,
                props("board", BOARD, "manufacturer", "alps", "display", DISPLAY));

        assertEquals(first.environmentKey(), reordered.environmentKey());
        assertEquals(64, first.environmentKey().length());
        assertNotEquals(first.environmentKey(), firmwareChanged.environmentKey());
        assertNotEquals(first.environmentKey(), EnvironmentFacts.of("aftermarket", 24,
                props("board", BOARD, "manufacturer", "alps")).environmentKey());
        assertFalse(first.environmentKey().equals(EnvironmentFacts.of("aaos", 23,
                props("board", BOARD, "manufacturer", "alps")).environmentKey()));
    }
}
