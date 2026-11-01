package com.cleanyourcar.launcher.compat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ProfileCodecTest {

    private static String fixture(String name) throws IOException {
        InputStream in = ProfileCodecTest.class.getClassLoader().getResourceAsStream(name);
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

    private static JSONObject fixtureObject(String name) throws Exception {
        return new JSONObject(fixture(name));
    }

    private static String reasonOf(String json, int appVersionCode) {
        ProfileFormatException e = assertThrows(ProfileFormatException.class,
                () -> ProfileCodec.parse(json, appVersionCode));
        assertNotNull(e.getMessage());
        return e.getReason().code();
    }

    @Test
    public void parse_acceptsVersionOne() throws Exception {
        DeviceProfile parsed = ProfileCodec.parse(fixture("valid-xyauto.json"), 1);
        assertEquals(1, parsed.schemaVersion);
        assertEquals("xyauto-yt3518c", parsed.profileId);
        assertEquals("aftermarket", parsed.platform);
        assertEquals("xyauto-yt3518c", parsed.adapterId);
        assertEquals(23, parsed.match.sdkMin);
        assertEquals(23, parsed.match.sdkMax);
        assertNull(parsed.match.buildProperties.get("fingerprint"));
        assertEquals("reported", parsed.evidence.classification);

        DeviceProfile.RoleDefault navigation = parsed.roleDefaults.get("navigation");
        assertEquals("com.google.android.apps.maps", navigation.packageName);
        assertNull(navigation.componentName);
        assertEquals("google-navigation", navigation.strategyId);
        DeviceProfile.RoleDefault equalizer = parsed.roleDefaults.get("equalizer");
        assertEquals("xyauto-equalizer", equalizer.strategyId);
        assertTrue(parsed.protectedPackages.contains("com.autochips.android.backcar"));

        DeviceProfile generic = ProfileCodec.parse(fixture("valid-generic.json"), 1);
        assertEquals(1, generic.schemaVersion);
        assertEquals("generic", generic.adapterId);
        assertTrue(generic.match.buildProperties.isEmpty());

        assertEquals(new HashSet<>(Arrays.asList("generic", "xyauto-yt3518c")),
                DeviceProfile.ADAPTER_IDS);
        assertEquals(new HashSet<>(Arrays.asList("launch-package", "launch-component",
                "google-navigation", "geo", "xyauto-equalizer")), DeviceProfile.STRATEGY_IDS);
        for (DeviceProfile profile : Arrays.asList(parsed, generic)) {
            for (DeviceProfile.RoleDefault role : profile.roleDefaults.values()) {
                assertTrue(DeviceProfile.STRATEGY_IDS.contains(role.strategyId));
            }
        }

        assertThrows(UnsupportedOperationException.class, () -> parsed.protectedPackages.add("x.y"));
        assertThrows(UnsupportedOperationException.class, () -> parsed.roleDefaults.clear());
        assertThrows(UnsupportedOperationException.class,
                () -> parsed.match.buildProperties.put("board", "x"));
        assertThrows(UnsupportedOperationException.class, () -> parsed.featureClaims.clear());
        assertThrows(UnsupportedOperationException.class,
                () -> parsed.evidence.references.add("x"));
    }

    @Test
    public void parse_rejectsCommandsAndUnknownAdapter() throws Exception {
        assertEquals("unknown_field", reasonOf(fixture("invalid-command.json"), 1));
        assertEquals("unknown_adapter", reasonOf(fixture("invalid-adapter.json"), 1));

        JSONObject unknownStrategy = fixtureObject("valid-generic.json");
        unknownStrategy.getJSONObject("roleDefaults").getJSONObject("navigation")
                .put("strategyId", "shell-exec");
        assertEquals("unknown_strategy", reasonOf(unknownStrategy.toString(), 1));

        JSONObject navStrategyOnOtherRole = fixtureObject("valid-generic.json");
        navStrategyOnOtherRole.getJSONObject("roleDefaults").put("radio",
                new JSONObject().put("packageName", "com.example.radio").put("strategyId", "geo"));
        assertEquals("invalid_value", reasonOf(navStrategyOnOtherRole.toString(), 1));

        JSONObject xyStrategyOnGeneric = fixtureObject("valid-generic.json");
        xyStrategyOnGeneric.getJSONObject("roleDefaults").put("equalizer",
                new JSONObject().put("packageName", "com.xyauto.Settings")
                        .put("strategyId", "xyauto-equalizer"));
        assertEquals("invalid_value", reasonOf(xyStrategyOnGeneric.toString(), 1));

        JSONObject foreignComponent = fixtureObject("valid-xyauto.json");
        foreignComponent.getJSONObject("roleDefaults").getJSONObject("radio")
                .put("componentName", "com.android.shell/.Exec");
        assertEquals("invalid_value", reasonOf(foreignComponent.toString(), 1));

        JSONObject componentStrategyWithoutComponent = fixtureObject("valid-generic.json");
        componentStrategyWithoutComponent.getJSONObject("roleDefaults").put("settings",
                new JSONObject().put("packageName", "com.android.settings")
                        .put("strategyId", "launch-component"));
        assertEquals("invalid_value", reasonOf(componentStrategyWithoutComponent.toString(), 1));

        JSONObject unknownRole = fixtureObject("valid-generic.json");
        unknownRole.getJSONObject("roleDefaults").put("broadcast",
                new JSONObject().put("packageName", "com.example").put("strategyId", "launch-package"));
        assertEquals("unknown_field", reasonOf(unknownRole.toString(), 1));

        JSONObject nestedExtra = fixtureObject("valid-generic.json");
        nestedExtra.getJSONObject("evidence").put("grantPermissions", new JSONArray().put("x"));
        assertEquals("unknown_field", reasonOf(nestedExtra.toString(), 1));
    }

    @Test
    public void parse_rejectsWrongTypeOrFutureSchema() throws Exception {
        assertEquals("bad_type", reasonOf(fixture("invalid-type.json"), 1));

        JSONObject future = fixtureObject("valid-generic.json").put("schemaVersion", 2);
        assertEquals("unsupported_schema", reasonOf(future.toString(), 1));
        JSONObject zero = fixtureObject("valid-generic.json").put("schemaVersion", 0);
        assertEquals("unsupported_schema", reasonOf(zero.toString(), 1));

        JSONObject stringVersion = fixtureObject("valid-generic.json").put("schemaVersion", "1");
        assertEquals("bad_type", reasonOf(stringVersion.toString(), 1));
        String decimalVersion = fixture("valid-generic.json")
                .replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1.0,");
        assertTrue(decimalVersion.contains("1.0"));
        assertEquals("bad_type", reasonOf(decimalVersion, 1));
        JSONObject nullPlatform = fixtureObject("valid-generic.json").put("platform", JSONObject.NULL);
        assertEquals("bad_type", reasonOf(nullPlatform.toString(), 1));

        JSONObject missing = fixtureObject("valid-generic.json");
        missing.remove("evidence");
        assertEquals("missing_field", reasonOf(missing.toString(), 1));

        JSONObject badPlatform = fixtureObject("valid-generic.json").put("platform", "desktop");
        assertEquals("invalid_value", reasonOf(badPlatform.toString(), 1));

        JSONObject emptyProperty = fixtureObject("valid-xyauto.json");
        emptyProperty.getJSONObject("match").getJSONObject("buildProperties").put("board", "");
        assertEquals("invalid_value", reasonOf(emptyProperty.toString(), 1));

        JSONObject unknownProperty = fixtureObject("valid-xyauto.json");
        unknownProperty.getJSONObject("match").getJSONObject("buildProperties")
                .put("ro.secure", "0");
        assertEquals("unknown_field", reasonOf(unknownProperty.toString(), 1));

        JSONObject badClaim = fixtureObject("valid-generic.json");
        badClaim.getJSONObject("featureClaims").put("overlay", "verified");
        assertEquals("invalid_value", reasonOf(badClaim.toString(), 1));

        JSONObject badRevision = fixtureObject("valid-generic.json").put("revision", 0);
        assertEquals("invalid_value", reasonOf(badRevision.toString(), 1));

        assertEquals("malformed", reasonOf("{\"schemaVersion\": 1", 1));
        assertEquals("malformed", reasonOf(fixture("valid-generic.json") + "{}", 1));
        assertEquals("bad_type", reasonOf("[]", 1));
    }

    @Test
    public void parse_rejectsSharedNonStandardJsonSyntax() throws Exception {
        for (String name : Arrays.asList("malformed-duplicate-key.json",
                "malformed-comment.json", "malformed-single-quote.json",
                "malformed-trailing-comma.json", "malformed-number.json")) {
            assertEquals(name, "malformed", reasonOf(fixture(name), 1));
        }

        String valid = fixture("valid-generic.json");
        for (String numeric : Arrays.asList("1.0", "1e0", "-0")) {
            String nonInteger = valid.replace("\"schemaVersion\": 1,",
                    "\"schemaVersion\": " + numeric + ",");
            assertTrue(nonInteger.contains("\"schemaVersion\": " + numeric + ","));
            assertEquals(numeric, "bad_type", reasonOf(nonInteger, 1));
        }
    }

    @Test
    public void parse_rejectsOversizeUtf8AndTooManyPackages() throws Exception {
        String base = fixture("valid-generic.json");
        int baseBytes = base.getBytes(StandardCharsets.UTF_8).length;

        StringBuilder atLimit = new StringBuilder(base);
        for (int i = baseBytes; i < ProfileCodec.MAX_PROFILE_BYTES; i++) {
            atLimit.append(' ');
        }
        assertEquals(65_536, atLimit.toString().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(1, ProfileCodec.parse(atLimit.toString(), 1).schemaVersion);

        JSONObject multibyte = fixtureObject("valid-generic.json");
        multibyte.getJSONObject("evidence").put("notes", "");
        int templateBytes = multibyte.toString().getBytes(StandardCharsets.UTF_8).length;
        int remaining = 65_537 - templateBytes;
        StringBuilder notes = new StringBuilder();
        for (int i = 0; i < remaining / 2; i++) {
            notes.append('\u00e9');
        }
        if (remaining % 2 == 1) {
            notes.append('a');
        }
        multibyte.getJSONObject("evidence").put("notes", notes.toString());
        String oversized = multibyte.toString();
        assertEquals(65_537, oversized.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(oversized.length() < 65_536);
        assertEquals("too_large", reasonOf(oversized, 1));

        JSONObject maxPackages = fixtureObject("valid-generic.json");
        JSONArray packages = new JSONArray();
        for (int i = 0; i < 256; i++) {
            packages.put("com.example.p" + i);
        }
        maxPackages.put("protectedPackages", packages);
        assertEquals(256, ProfileCodec.parse(maxPackages.toString(), 1).protectedPackages.size());

        packages.put("com.example.p256");
        assertEquals("too_many_packages", reasonOf(maxPackages.toString(), 1));

        JSONObject badPackage = fixtureObject("valid-generic.json");
        badPackage.put("protectedPackages", new JSONArray().put("rm -rf /"));
        assertEquals("invalid_value", reasonOf(badPackage.toString(), 1));

        JSONObject duplicate = fixtureObject("valid-generic.json");
        duplicate.put("protectedPackages", new JSONArray().put("com.a.b").put("com.a.b"));
        assertEquals("invalid_value", reasonOf(duplicate.toString(), 1));

        JSONObject longNotes = fixtureObject("valid-generic.json");
        StringBuilder tooLongNotes = new StringBuilder();
        for (int i = 0; i < 2049; i++) {
            tooLongNotes.append('\u00e9');
        }
        longNotes.getJSONObject("evidence").put("notes", tooLongNotes.toString());
        assertEquals("invalid_value", reasonOf(longNotes.toString(), 1));
    }

    @Test
    public void parse_rejectsAppVersionTooOld() throws Exception {
        JSONObject profile = fixtureObject("valid-generic.json").put("minAppVersionCode", 5);
        assertEquals("app_too_old", reasonOf(profile.toString(), 4));
        assertEquals(5, ProfileCodec.parse(profile.toString(), 5).minAppVersionCode);
        assertEquals(5, ProfileCodec.parse(profile.toString(), 6).minAppVersionCode);
    }
}
