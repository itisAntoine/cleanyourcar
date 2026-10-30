package com.cleanyourcar.launcher.config;

import com.cleanyourcar.launcher.ThemeConfig;
import com.cleanyourcar.launcher.compat.DeviceProfile;
import com.cleanyourcar.launcher.compat.ProfileCodec;
import com.cleanyourcar.launcher.compat.ProfileFormatException;
import com.cleanyourcar.launcher.config.RoleSelection.Role;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ConfigurationStoreTest {

    private static final int APP_VERSION = 1;

    /** In-memory storage; when failing, writes throw before touching the stored bytes. */
    private static final class FakeStorage implements ConfigurationStore.Storage {
        byte[] bytes;
        boolean failWrites;
        int writes;

        @Override
        public byte[] read() {
            return bytes == null ? null : bytes.clone();
        }

        @Override
        public void writeAtomically(byte[] data) throws IOException {
            if (failWrites) {
                throw new IOException("disk full");
            }
            writes++;
            bytes = data.clone();
        }
    }

    private static String resource(String name) throws IOException {
        InputStream in = ConfigurationStoreTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull("missing test resource " + name, in);
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

    /** Loads a legacy SharedPreferences fixture; JSON arrays become string sets. */
    private static Map<String, Object> legacyPrefs(String name) throws Exception {
        JSONObject json = new JSONObject(resource("config/" + name));
        Map<String, Object> prefs = new HashMap<>();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = json.get(key);
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                Set<String> set = new LinkedHashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    set.add(array.getString(i));
                }
                prefs.put(key, set);
            } else {
                prefs.put(key, value);
            }
        }
        return prefs;
    }

    private static DeviceProfile profile(String fixture) throws Exception {
        return ProfileCodec.parse(resource(fixture), APP_VERSION);
    }

    private static ConfigurationStore migratedStore(FakeStorage storage, String legacyFixture)
            throws Exception {
        ConfigurationStore store = new ConfigurationStore(storage, APP_VERSION);
        store.migrateLegacy(legacyPrefs(legacyFixture));
        return store;
    }

    @Test
    public void role_mapsOneToOneToProfileRoleKeys() {
        Set<String> keys = new HashSet<>();
        for (Role role : Role.values()) {
            assertTrue("unknown role key " + role.key(), DeviceProfile.ROLE_KEYS.contains(role.key()));
            assertEquals(role, Role.fromKey(role.key()));
            keys.add(role.key());
        }
        assertEquals(DeviceProfile.ROLE_KEYS, keys);
        assertNull(Role.fromKey("phone"));
    }

    @Test
    public void merge_userOverridesBeatProfile() throws Exception {
        FakeStorage storage = new FakeStorage();
        ConfigurationStore store = migratedStore(storage, "legacy-prefs-ui-only.json");
        String userNavigationPackage = "com.waze";
        store.setRole(Role.NAVIGATION,
                new RoleSelection(userNavigationPackage, null, "geo", "versionCode:1042"));

        store.applyProfile(store.previewProfile(profile("valid-xyauto.json")));
        LauncherConfiguration effective = store.current();

        assertEquals(userNavigationPackage, effective.roles.get(Role.NAVIGATION).packageName);
        assertEquals("versionCode:1042", effective.roles.get(Role.NAVIGATION).versionIdentity);
        RoleSelection bluetooth = effective.roles.get(Role.BLUETOOTH_MUSIC);
        assertEquals("com.autochips.bluetooth", bluetooth.packageName);
        assertEquals("com.autochips.bluetooth/.MainBluetoothActivity", bluetooth.componentName);
        assertEquals("launch-component", bluetooth.strategyId);
        assertEquals("xyauto-yt3518c", effective.acceptedProfileId);
        assertEquals(1, effective.acceptedProfileRevision);
        assertEquals("xyauto-yt3518c", effective.acceptedAdapterId);

        // A generic profile lacks most roles: those fall back to built-in defaults.
        store.applyProfile(store.previewProfile(profile("valid-generic.json")));
        effective = store.current();
        assertEquals(userNavigationPackage, effective.roles.get(Role.NAVIGATION).packageName);
        assertEquals(LauncherConfiguration.builtInRole(Role.BLUETOOTH_MUSIC).packageName,
                effective.roles.get(Role.BLUETOOTH_MUSIC).packageName);
        assertEquals(Role.values().length, effective.roles.size());

        // Clearing the override exposes the accepted profile default.
        store.setRole(Role.NAVIGATION, null);
        RoleSelection navigation = store.current().roles.get(Role.NAVIGATION);
        assertEquals("com.google.android.apps.maps", navigation.packageName);
        assertEquals("geo", navigation.strategyId);

        // Persisted state survives a new store instance.
        ConfigurationStore reopened = new ConfigurationStore(storage, APP_VERSION);
        LauncherConfiguration reloaded =
                reopened.migrateLegacy(legacyPrefs("legacy-prefs-ui-only.json")).configuration;
        assertEquals("generic-android", reloaded.acceptedProfileId);
        assertEquals("geo", reloaded.roles.get(Role.NAVIGATION).strategyId);
    }

    @Test
    public void migrate_preservesAllLegacyKeys() throws Exception {
        FakeStorage storage = new FakeStorage();
        ConfigurationStore store = new ConfigurationStore(storage, APP_VERSION);
        Map<String, Object> legacy = legacyPrefs("legacy-prefs-existing-user.json");

        ConfigurationStore.MigrationResult result = store.migrateLegacy(legacy);

        assertTrue(result.migrated);
        LauncherConfiguration config = result.configuration;
        assertEquals(2, config.ui.themeId);
        assertEquals(3, config.ui.glassOpacity);
        assertEquals(1, config.ui.languageId);
        assertFalse(config.ui.dockLeft);
        assertTrue(config.ui.swapDashboardColumns);
        assertEquals(5, config.ui.gridColumns);
        assertEquals(Arrays.asList("com.waze", "com.android.settings", "com.fvsm.camera"),
                config.ui.appOrder);
        assertEquals(new HashSet<>(Arrays.asList("com.acloud.stub.localradio",
                "com.android.settings")), config.ui.hiddenPackages);
        assertEquals(Collections.singleton("com.spotify.music"), config.ui.unhiddenPackages);
        assertEquals(Integer.valueOf(3), config.navigationCityIndex);
        assertTrue(config.curatedAppVisibility);

        assertEquals("com.waze", config.roleOverrides.get(Role.NAVIGATION).packageName);
        assertEquals("com.example.btmusic",
                config.roleOverrides.get(Role.BLUETOOTH_MUSIC).packageName);
        assertEquals("com.example.usbmusic", config.roleOverrides.get(Role.LOCAL_MUSIC).packageName);
        assertEquals("com.example.dashcam", config.roleOverrides.get(Role.DASHCAM).packageName);
        assertEquals("com.example.equalizer",
                config.roleOverrides.get(Role.EQUALIZER).packageName);
        assertEquals(5, config.roleOverrides.size());
        assertEquals("com.waze", config.roles.get(Role.NAVIGATION).packageName);
        assertNull(config.acceptedProfileId);
        assertEquals("legacy preferences are read, never rewritten",
                legacyPrefs("legacy-prefs-existing-user.json"), legacy);

        // Idempotent: a second run does not re-import legacy overrides over later choices.
        store.setRole(Role.NAVIGATION,
                new RoleSelection("com.google.android.apps.maps", null, "google-navigation", null));
        ConfigurationStore.MigrationResult again =
                new ConfigurationStore(storage, APP_VERSION).migrateLegacy(legacy);
        assertFalse(again.migrated);
        assertEquals("com.google.android.apps.maps",
                again.configuration.roles.get(Role.NAVIGATION).packageName);
        assertEquals("com.example.btmusic",
                again.configuration.roles.get(Role.BLUETOOTH_MUSIC).packageName);
        assertEquals(5, again.configuration.ui.gridColumns);
    }

    @Test
    public void migrate_genericNewInstallShowsDiscoveredAppsButExistingUsersKeepCuratedList()
            throws Exception {
        LauncherConfiguration fresh = new ConfigurationStore(new FakeStorage(), APP_VERSION)
                .migrateLegacy(new HashMap<String, Object>(), false).configuration;
        assertFalse(fresh.curatedAppVisibility);
        assertNull(fresh.navigationCityIndex);
        assertTrue(fresh.roleOverrides.isEmpty());
        assertEquals(LauncherConfiguration.builtInRole(Role.NAVIGATION).packageName,
                fresh.roles.get(Role.NAVIGATION).packageName);
        assertEquals(ThemeConfig.THEME_GLASSMORPHISM, fresh.ui.themeId);

        LauncherConfiguration upgradedWithoutPrefs =
                new ConfigurationStore(new FakeStorage(), APP_VERSION)
                        .migrateLegacy(new HashMap<String, Object>(), true).configuration;
        assertTrue(upgradedWithoutPrefs.curatedAppVisibility);

        LauncherConfiguration existing = migratedStore(new FakeStorage(),
                "legacy-prefs-ui-only.json").current();
        assertTrue(existing.curatedAppVisibility);
        assertEquals(0, existing.ui.glassOpacity);
    }

    @Test
    public void import_failedWriteKeepsPreviousConfig() throws Exception {
        FakeStorage storage = new FakeStorage();
        ConfigurationStore store = migratedStore(storage, "legacy-prefs-existing-user.json");
        store.recordValidationKey("navigation", "env-1|com.waze@1042");
        byte[] originalBytes = storage.read();
        LauncherConfiguration before = store.current();

        ConfigurationStore.ConfigurationChange change = store.importProfile(
                resource("valid-xyauto.json").getBytes(StandardCharsets.UTF_8));
        storage.failWrites = true;

        assertThrows(IOException.class, () -> store.applyProfile(change));
        assertArrayEquals(originalBytes, storage.read());
        assertNull(store.current().acceptedProfileId);
        assertEquals(before.validationKeys, store.current().validationKeys);
        assertThrows(IOException.class, () -> store.setRole(Role.NAVIGATION, null));
        assertEquals("com.waze", store.current().roles.get(Role.NAVIGATION).packageName);
        assertArrayEquals(originalBytes, storage.read());

        // The same preview applies once storage works again.
        storage.failWrites = false;
        store.applyProfile(change);
        assertEquals("xyauto-yt3518c", store.current().acceptedProfileId);
    }

    @Test
    public void import_invalidProfileDoesNotInvalidateWorkingState() throws Exception {
        FakeStorage storage = new FakeStorage();
        ConfigurationStore store = migratedStore(storage, "legacy-prefs-ui-only.json");
        store.applyProfile(store.previewProfile(profile("valid-xyauto.json")));
        store.recordValidationKey("media-transport", "env-1|adapter-1");
        byte[] workingBytes = storage.read();
        int writes = storage.writes;

        ProfileFormatException invalid = assertThrows(ProfileFormatException.class,
                () -> store.importProfile(new ByteArrayInputStream(
                        resource("invalid-command.json").getBytes(StandardCharsets.UTF_8))));
        assertEquals("unknown_field", invalid.getReason().code());

        byte[] oversized = new byte[ProfileCodec.MAX_PROFILE_BYTES + 1];
        Arrays.fill(oversized, (byte) ' ');
        ProfileFormatException tooLarge = assertThrows(ProfileFormatException.class,
                () -> store.importProfile(new ByteArrayInputStream(oversized)));
        assertEquals("too_large", tooLarge.getReason().code());

        ProfileFormatException malformed = assertThrows(ProfileFormatException.class,
                () -> store.importProfile("{".getBytes(StandardCharsets.UTF_8)));
        assertEquals("malformed", malformed.getReason().code());

        assertEquals(writes, storage.writes);
        assertArrayEquals(workingBytes, storage.read());
        LauncherConfiguration current = store.current();
        assertEquals("xyauto-yt3518c", current.acceptedProfileId);
        assertEquals("env-1|adapter-1", current.validationKeys.get("media-transport"));
    }

    @Test
    public void preview_reportsOnlyDependentCapabilitiesAndApplyDropsTheirValidation()
            throws Exception {
        FakeStorage storage = new FakeStorage();
        ConfigurationStore store = migratedStore(storage, "legacy-prefs-ui-only.json");
        store.applyProfile(store.previewProfile(profile("valid-xyauto.json")));
        store.recordValidationKey("navigation", "nav-ok");
        store.recordValidationKey("media-transport", "media-ok");
        store.recordValidationKey("brightness", "brightness-ok");

        // Same profile again: nothing changes, nothing is invalidated.
        assertTrue(store.previewProfile(profile("valid-xyauto.json"))
                .affectedCapabilityKeys.isEmpty());

        ConfigurationStore.ConfigurationChange toGeneric =
                store.previewProfile(profile("valid-generic.json"));
        assertEquals("xyauto-yt3518c", toGeneric.oldConfiguration.acceptedProfileId);
        assertEquals("generic-android", toGeneric.newConfiguration.acceptedProfileId);
        assertTrue(toGeneric.affectedCapabilityKeys.contains("navigation"));
        assertTrue(toGeneric.affectedCapabilityKeys.contains("media-transport"));
        assertTrue(toGeneric.affectedCapabilityKeys.contains("reverse-detection"));
        assertFalse(toGeneric.affectedCapabilityKeys.contains("brightness"));
        assertFalse(toGeneric.affectedCapabilityKeys.contains("app-launch"));
        assertEquals("preview never writes", "xyauto-yt3518c", store.current().acceptedProfileId);

        store.applyProfile(toGeneric);
        Map<String, String> remaining = store.current().validationKeys;
        assertEquals(Collections.singletonMap("brightness", "brightness-ok"), remaining);

        // Replacing only the navigation app invalidates only navigation verification.
        store.recordValidationKey("media-transport", "media-ok");
        store.recordValidationKey("navigation", "nav-ok");
        store.setRole(Role.NAVIGATION, new RoleSelection("com.waze", null, "geo", null));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("brightness", "brightness-ok");
        expected.put("media-transport", "media-ok");
        assertEquals(expected, store.current().validationKeys);

        assertThrows(IllegalArgumentException.class,
                () -> store.recordValidationKey("not-a-capability", "x"));
    }

    @Test
    public void apply_stalePreviewIsRejected() throws Exception {
        FakeStorage storage = new FakeStorage();
        ConfigurationStore store = migratedStore(storage, "legacy-prefs-ui-only.json");
        ConfigurationStore.ConfigurationChange stale =
                store.previewProfile(profile("valid-xyauto.json"));
        store.setRole(Role.DASHCAM, new RoleSelection("com.example.cam", null,
                "launch-package", null));
        byte[] bytes = storage.read();

        ProfileFormatException e = assertThrows(ProfileFormatException.class,
                () -> store.applyProfile(stale));
        assertEquals("invalid_value", e.getReason().code());
        assertArrayEquals(bytes, storage.read());
    }

    @Test
    public void export_excludesEvidenceSecretsAndLocation() throws Exception {
        FakeStorage storage = new FakeStorage();
        ConfigurationStore store = new ConfigurationStore(storage, APP_VERSION);
        Map<String, Object> legacy = legacyPrefs("legacy-prefs-existing-user.json");
        legacy.put("bridge_auth_token", "s3cr3t-token-value");
        store.migrateLegacy(legacy);
        store.applyProfile(store.importProfile(
                resource("valid-xyauto.json").getBytes(StandardCharsets.UTF_8)));
        store.recordValidationKey("overlay", "local-evidence-digest");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.exportSanitized(out);
        String exported = new String(out.toByteArray(), StandardCharsets.UTF_8);
        JSONObject json = new JSONObject(exported);

        assertFalse(exported.contains("local-evidence-digest"));
        assertFalse(exported.contains("validation"));
        assertFalse(exported.contains("evidence"));
        assertFalse(exported.contains("Reported from sanitized"));
        assertFalse(exported.contains("YT3518C_00008_V008"));
        assertFalse(exported.contains("s3cr3t-token-value"));
        assertFalse(exported.contains("token"));
        assertFalse(exported.toLowerCase(java.util.Locale.ROOT)
                .replace("opacity", "").contains("city"));
        assertFalse(exported.contains("plus_code"));
        assertFalse(exported.contains("navigationCity"));
        assertFalse(exported.contains("versionCode"));
        assertFalse(exported.contains("10.0452"));

        assertEquals("xyauto-yt3518c", json.getJSONObject("profile").getString("id"));
        assertEquals("com.waze", json.getJSONObject("roleOverrides")
                .getJSONObject("navigation").getString("packageName"));
        JSONObject ui = json.getJSONObject("ui");
        assertEquals(3, ui.getInt("glassOpacity"));
        assertEquals("com.waze", ui.getJSONArray("appOrder").getString(0));
    }

    @Test
    public void fileStorage_replacesContentAndReportsMissingFile() throws Exception {
        File dir = new File("build/test-config-storage");
        File file = new File(dir, "launcher_configuration.json");
        file.delete();
        new File(dir, "launcher_configuration.json.tmp").delete();
        dir.mkdirs();
        ConfigurationStore.FileStorage storage = new ConfigurationStore.FileStorage(file);

        assertNull(storage.read());
        storage.writeAtomically("first".getBytes(StandardCharsets.UTF_8));
        storage.writeAtomically("second".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), storage.read());
        assertFalse(new File(dir, "launcher_configuration.json.tmp").exists());
    }
}
