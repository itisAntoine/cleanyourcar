package com.cleanyourcar.launcher.config;

import android.content.Context;
import android.content.pm.PackageInfo;

import com.cleanyourcar.launcher.I18n;
import com.cleanyourcar.launcher.ThemeConfig;
import com.cleanyourcar.launcher.compat.CapabilityRegistry;
import com.cleanyourcar.launcher.compat.DeviceProfile;
import com.cleanyourcar.launcher.compat.ProfileCodec;
import com.cleanyourcar.launcher.compat.ProfileFormatException;
import com.cleanyourcar.launcher.config.RoleSelection.Role;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single configuration layer. Persists accepted profile, explicit role choices, app visibility
 * mode, and local validation keys as JSON replaced atomically. UI/app-library preferences and the
 * saved city reference stay live in the legacy {@code carplay_launcher_prefs} file, which is read
 * but never rewritten or deleted, and are merged into every loaded configuration.
 */
public final class ConfigurationStore {

    public static final String FILE_NAME = "launcher_configuration.json";

    private static final int FORMAT_VERSION = 1;
    static final String CORRUPT_SUFFIX = ".corrupt-";
    private static final String VISIBILITY_CURATED = "curated";
    private static final String VISIBILITY_DISCOVERED = "discovered";
    private static final String LEGACY_XYAUTO_USER = "legacyXyAutoUser";

    /** Legacy package override keys and the roles they migrate to. */
    private static final Map<String, Role> LEGACY_ROLE_KEYS = legacyRoleKeys();

    private static ConfigurationStore instance;

    private final Storage storage;
    private final int appVersionCode;
    private LauncherConfiguration current;
    /** Set when the stored file comes from a newer app version; it must never be overwritten. */
    private boolean readOnly;

    /** Byte persistence; {@link #writeAtomically} must leave previous bytes on failure. */
    public interface Storage {
        /** Returns the stored bytes, or null when nothing was stored yet. */
        byte[] read() throws IOException;

        void writeAtomically(byte[] data) throws IOException;

        /** Renames the stored data to a sibling named with {@code suffix}, keeping its bytes. */
        void moveAside(String suffix) throws IOException;
    }

    /** Thrown by mutations while the stored file belongs to a newer, unsupported format. */
    public static final class NewerFormatException extends IOException {
        NewerFormatException() {
            super("configuration was saved by a newer app version; changes are disabled");
        }
    }

    /** Writes a sibling temp file, syncs it, then renames it over the target. */
    public static final class FileStorage implements Storage {
        private final File file;

        public FileStorage(File file) {
            this.file = file;
        }

        @Override
        public byte[] read() throws IOException {
            if (!file.exists()) {
                return null;
            }
            try (InputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        }

        @Override
        public void writeAtomically(byte[] data) throws IOException {
            File temp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(data);
                out.getFD().sync();
            } catch (IOException e) {
                temp.delete();
                throw e;
            }
            if (!temp.renameTo(file)) {
                temp.delete();
                throw new IOException("could not replace " + file.getName());
            }
        }

        @Override
        public void moveAside(String suffix) throws IOException {
            File aside = new File(file.getPath() + suffix);
            if (file.exists() && !file.renameTo(aside)) {
                throw new IOException("could not move aside " + file.getName());
            }
        }
    }

    /** Previewed configuration change; nothing is written until it is applied. */
    public static final class ConfigurationChange {
        public final LauncherConfiguration oldConfiguration;
        public final LauncherConfiguration newConfiguration;
        /** Capability IDs whose local verification the change invalidates. */
        public final Set<String> affectedCapabilityKeys;

        ConfigurationChange(LauncherConfiguration oldConfiguration,
                LauncherConfiguration newConfiguration, Set<String> affectedCapabilityKeys) {
            this.oldConfiguration = oldConfiguration;
            this.newConfiguration = newConfiguration;
            this.affectedCapabilityKeys = affectedCapabilityKeys;
        }
    }

    public static final class MigrationResult {
        public final LauncherConfiguration configuration;
        /** True only for the run that performed the one-time migration. */
        public final boolean migrated;

        MigrationResult(LauncherConfiguration configuration, boolean migrated) {
            this.configuration = configuration;
            this.migrated = migrated;
        }
    }

    /** A validated, non-device-specific settings transfer shown for confirmation before apply. */
    public static final class SanitizedImport {
        public final NavigationReference.Mode referenceMode;
        public final Map<Role, RoleSelection> roleOverrides;
        public final boolean curatedAppVisibility;
        public final LauncherConfiguration.UiPreferences ui;
        private final LauncherConfiguration baseConfiguration;

        SanitizedImport(NavigationReference.Mode referenceMode,
                Map<Role, RoleSelection> roleOverrides, boolean curatedAppVisibility,
                LauncherConfiguration.UiPreferences ui,
                LauncherConfiguration baseConfiguration) {
            this.referenceMode = referenceMode;
            this.roleOverrides = Collections.unmodifiableMap(new EnumMap<>(roleOverrides));
            this.curatedAppVisibility = curatedAppVisibility;
            this.ui = ui;
            this.baseConfiguration = baseConfiguration;
        }
    }

    public ConfigurationStore(Storage storage, int appVersionCode) {
        this.storage = storage;
        this.appVersionCode = appVersionCode;
    }

    /** Process-wide store backed by a file in the app's private files directory. */
    public static synchronized ConfigurationStore get(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext();
            instance = new ConfigurationStore(
                    new FileStorage(new File(app.getFilesDir(), FILE_NAME)), versionCode(app));
        }
        return instance;
    }

    /** Effective configuration; runs the one-time migration first. Never throws on I/O errors. */
    public LauncherConfiguration load(Context context) {
        Map<String, ?> legacy = ThemeConfig.prefs(context).getAll();
        synchronized (this) {
            if (current != null) {
                current = withLegacyPreferences(current, legacy);
                return current;
            }
        }
        try {
            return migrateLegacy(legacy, isUpgradedInstall(context)).configuration;
        } catch (IOException e) {
            synchronized (this) {
                return current != null ? current
                        : fromLegacy(legacy, isUpgradedInstall(context)).build();
            }
        }
    }

    /** True when the stored file is from a newer format and all mutations are rejected. */
    public synchronized boolean isReadOnly() {
        return readOnly;
    }

    /** Last loaded configuration. */
    public synchronized LauncherConfiguration current() {
        return requireCurrent();
    }

    public MigrationResult migrateLegacy(Context context) throws IOException {
        return migrateLegacy(ThemeConfig.prefs(context).getAll(), isUpgradedInstall(context));
    }

    /** Migrates legacy preference values; an empty map is treated as a new install. */
    public MigrationResult migrateLegacy(Map<String, ?> legacy) throws IOException {
        return migrateLegacy(legacy, false);
    }

    /**
     * One-time, idempotent migration. Once the migration marker is stored, legacy role
     * overrides are never re-imported, so later explicit choices are kept. A file from a newer
     * format is used read-only (or ignored if unreadable) and never overwritten; a corrupt file
     * is moved aside before re-migrating.
     */
    public synchronized MigrationResult migrateLegacy(Map<String, ?> legacy,
            boolean upgradedInstall) throws IOException {
        Stored stored = parse(storage.read());
        readOnly = stored.newerFormat;
        if (stored.newerFormat) {
            current = stored.configuration != null
                    ? withLegacyPreferences(stored.configuration, legacy)
                    : fromLegacy(legacy, upgradedInstall).build();
            return new MigrationResult(current, false);
        }
        if (stored.configuration != null && stored.configuration.migrationVersion
                >= LauncherConfiguration.MIGRATION_VERSION) {
            current = withLegacyPreferences(stored.configuration, legacy);
            return new MigrationResult(current, false);
        }
        if (stored.corrupt) {
            storage.moveAside(CORRUPT_SUFFIX + System.currentTimeMillis());
        }
        LauncherConfiguration migrated = fromLegacy(legacy, upgradedInstall).build();
        storage.writeAtomically(serialize(migrated));
        current = migrated;
        return new MigrationResult(migrated, true);
    }

    /** Computes what accepting {@code profile} would change, without writing anything. */
    public synchronized ConfigurationChange previewProfile(DeviceProfile profile) {
        LauncherConfiguration old = requireCurrent();
        LauncherConfiguration.Builder builder = old.toBuilder();
        builder.acceptedProfileId = profile.profileId;
        builder.acceptedProfileRevision = profile.revision;
        builder.acceptedAdapterId = profile.adapterId;
        builder.profileRoleDefaults.clear();
        for (Map.Entry<String, DeviceProfile.RoleDefault> entry : profile.roleDefaults.entrySet()) {
            Role role = Role.fromKey(entry.getKey());
            if (role != null) {
                builder.profileRoleDefaults.put(role,
                        RoleSelection.fromProfileDefault(entry.getValue()));
            }
        }
        return change(old, builder);
    }

    /** Parses an imported profile (e.g. from a document picker) and previews it. */
    public ConfigurationChange importProfile(InputStream in)
            throws IOException, ProfileFormatException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > ProfileCodec.MAX_PROFILE_BYTES) {
                throw tooLarge();
            }
        }
        return importProfile(out.toByteArray());
    }

    public ConfigurationChange importProfile(byte[] bytes) throws ProfileFormatException {
        if (bytes == null || bytes.length == 0) {
            throw new ProfileFormatException(ProfileFormatException.Reason.MALFORMED,
                    "profile is empty");
        }
        if (bytes.length > ProfileCodec.MAX_PROFILE_BYTES) {
            throw tooLarge();
        }
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new ProfileFormatException(ProfileFormatException.Reason.MALFORMED,
                    "profile is not UTF-8");
        }
        return previewProfile(ProfileCodec.parse(json, appVersionCode));
    }

    /** Identifies exported settings documents while leaving profile imports backward compatible. */
    public static boolean isSanitizedExport(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > ProfileCodec.MAX_PROFILE_BYTES) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return root.optInt("formatVersion", -1) == FORMAT_VERSION
                    && root.optJSONObject("ui") != null;
        } catch (JSONException e) {
            return false;
        }
    }

    /** Parses a settings export; older exports without a mode safely default to CITY. */
    public synchronized SanitizedImport importSanitized(byte[] bytes)
            throws ProfileFormatException {
        if (bytes == null || bytes.length == 0) {
            throw malformedConfiguration("configuration is empty");
        }
        if (bytes.length > ProfileCodec.MAX_PROFILE_BYTES) {
            throw tooLarge();
        }
        final String jsonText;
        try {
            jsonText = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw malformedConfiguration("configuration is not UTF-8");
        }
        try {
            JSONObject root = new JSONObject(jsonText);
            if (root.getInt("formatVersion") != FORMAT_VERSION) {
                throw malformedConfiguration("unsupported configuration version");
            }
            String visibility = root.getString("appVisibility");
            if (!VISIBILITY_CURATED.equals(visibility)
                    && !VISIBILITY_DISCOVERED.equals(visibility)) {
                throw malformedConfiguration("invalid app visibility");
            }
            NavigationReference.Mode mode = NavigationReference.Mode.CITY;
            if (root.has("referenceMode")) {
                mode = NavigationReference.Mode.fromKey(root.getString("referenceMode"));
                if (!mode.key().equals(root.getString("referenceMode"))) {
                    throw malformedConfiguration("invalid reference mode");
                }
            }
            Map<Role, RoleSelection> roles = importedRoles(root.getJSONObject("roleOverrides"));
            LauncherConfiguration.UiPreferences ui =
                    importedUi(root.getJSONObject("ui"));
            return new SanitizedImport(mode, roles,
                    VISIBILITY_CURATED.equals(visibility), ui, requireCurrent());
        } catch (JSONException | IllegalArgumentException e) {
            throw malformedConfiguration("configuration fields are invalid");
        }
    }

    /** Applies only user settings; imported profile IDs and local validation are never accepted. */
    public synchronized void applySanitizedImport(SanitizedImport imported)
            throws IOException, ProfileFormatException {
        LauncherConfiguration old = requireCurrent();
        if (!Arrays.equals(serialize(imported.baseConfiguration), serialize(old))) {
            throw new ProfileFormatException(ProfileFormatException.Reason.INVALID_VALUE,
                    "configuration preview is stale");
        }
        LauncherConfiguration.Builder builder = old.toBuilder();
        builder.roleOverrides.clear();
        builder.roleOverrides.putAll(imported.roleOverrides);
        builder.curatedAppVisibility = imported.curatedAppVisibility;
        persist(change(old, builder).newConfiguration.toBuilder());
    }

    /**
     * Accepts a previewed profile. On a write failure the previous file and in-memory
     * configuration remain. A preview made before another change is rejected as stale.
     */
    public synchronized void applyProfile(ConfigurationChange change)
            throws IOException, ProfileFormatException {
        LauncherConfiguration old = requireCurrent();
        if (!Arrays.equals(serialize(change.oldConfiguration), serialize(old))) {
            throw new ProfileFormatException(ProfileFormatException.Reason.INVALID_VALUE,
                    "profile preview is stale");
        }
        persist(change.newConfiguration.toBuilder());
    }

    /** Sets (or clears with null) the explicit user choice for a role. */
    public synchronized void setRole(Role role, RoleSelection selection) throws IOException {
        LauncherConfiguration.Builder builder = requireCurrent().toBuilder();
        if (selection == null) {
            builder.roleOverrides.remove(role);
        } else {
            builder.roleOverrides.put(role, selection);
        }
        persist(change(current, builder).newConfiguration.toBuilder());
    }

    /** Records local verification for a capability ID. */
    public synchronized void recordValidationKey(String capabilityId, String value)
            throws IOException {
        if (CapabilityRegistry.Capability.fromId(capabilityId) == null) {
            throw new IllegalArgumentException("unknown capability");
        }
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("value is required");
        }
        LauncherConfiguration.Builder builder = requireCurrent().toBuilder();
        builder.validationKeys.put(capabilityId, value);
        persist(builder);
    }

    /**
     * Writes a shareable copy (e.g. to a document picker). Excludes validation keys, version
     * identities, profile evidence, the saved location reference, and any unknown legacy data.
     */
    public void exportSanitized(OutputStream out) throws IOException {
        exportSanitized(out, NavigationReference.Mode.CITY);
    }

    public void exportSanitized(OutputStream out, NavigationReference.Mode referenceMode)
            throws IOException {
        LauncherConfiguration config = current();
        try {
            JSONObject root = new JSONObject();
            root.put("formatVersion", FORMAT_VERSION);
            if (config.acceptedProfileId != null) {
                JSONObject profile = new JSONObject();
                profile.put("id", config.acceptedProfileId);
                profile.put("revision", config.acceptedProfileRevision);
                profile.put("adapterId", config.acceptedAdapterId);
                root.put("profile", profile);
            }
            root.put("roleOverrides", rolesJson(config.roleOverrides, false));
            root.put("appVisibility", visibility(config));
            root.put("referenceMode", referenceMode == null
                    ? NavigationReference.Mode.CITY.key() : referenceMode.key());
            LauncherConfiguration.UiPreferences ui = config.ui;
            JSONObject uiJson = new JSONObject();
            uiJson.put("themeId", ui.themeId);
            uiJson.put("glassOpacity", ui.glassOpacity);
            uiJson.put("languageId", ui.languageId);
            uiJson.put("dockLeft", ui.dockLeft);
            uiJson.put("swapDashboardColumns", ui.swapDashboardColumns);
            uiJson.put("gridColumns", ui.gridColumns);
            uiJson.put("appOrder", array(ui.appOrder));
            uiJson.put("hiddenPackages", array(ui.hiddenPackages));
            uiJson.put("unhiddenPackages", array(ui.unhiddenPackages));
            root.put("ui", uiJson);
            out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (JSONException e) {
            throw new IOException("could not encode configuration", e);
        }
    }

    private static Map<Role, RoleSelection> importedRoles(JSONObject json)
            throws JSONException, ProfileFormatException {
        Map<Role, RoleSelection> roles = new EnumMap<>(Role.class);
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Role role = Role.fromKey(key);
            if (role == null) {
                throw new ProfileFormatException(ProfileFormatException.Reason.UNKNOWN_FIELD,
                        "roleOverrides contains an unsupported role");
            }
            JSONObject entry = json.getJSONObject(key);
            String packageName = entry.getString("packageName");
            String strategy = entry.getString("strategyId");
            String component = entry.has("componentName")
                    ? entry.getString("componentName") : null;
            if (!DeviceProfile.STRATEGY_IDS.contains(strategy)
                    || DeviceProfile.NAVIGATION_STRATEGY_IDS.contains(strategy)
                            && role != Role.NAVIGATION
                    || DeviceProfile.XYAUTO_STRATEGY_IDS.contains(strategy)
                            && role != Role.EQUALIZER) {
                throw new ProfileFormatException(ProfileFormatException.Reason.INVALID_VALUE,
                        "roleOverrides contains an invalid strategy");
            }
            if (component != null && !component.startsWith(packageName + "/")) {
                throw new ProfileFormatException(ProfileFormatException.Reason.INVALID_VALUE,
                        "roleOverrides contains a mismatched component");
            }
            roles.put(role, new RoleSelection(packageName, component, strategy, null));
        }
        return roles;
    }

    private static LauncherConfiguration.UiPreferences importedUi(JSONObject json)
            throws JSONException, ProfileFormatException {
        int themeId = json.getInt("themeId");
        int opacity = json.getInt("glassOpacity");
        int language = json.getInt("languageId");
        int columns = json.getInt("gridColumns");
        if (themeId < ThemeConfig.THEME_GLASSMORPHISM
                || themeId > ThemeConfig.THEME_WARM_CARBON
                || opacity < ThemeConfig.OPACITY_CRYSTAL || opacity > ThemeConfig.OPACITY_SOLID
                || language < 0 || language >= I18n.LANGUAGE_NAMES.length
                || columns < 3 || columns > 5) {
            throw new ProfileFormatException(ProfileFormatException.Reason.INVALID_VALUE,
                    "ui settings contain an invalid value");
        }
        List<String> order = importedStrings(json.getJSONArray("appOrder"));
        Set<String> hidden = new LinkedHashSet<>(importedStrings(
                json.getJSONArray("hiddenPackages")));
        Set<String> unhidden = new LinkedHashSet<>(importedStrings(
                json.getJSONArray("unhiddenPackages")));
        return new LauncherConfiguration.UiPreferences(themeId, opacity, language,
                json.getBoolean("dockLeft"), json.getBoolean("swapDashboardColumns"),
                columns, order, hidden, unhidden);
    }

    private static List<String> importedStrings(JSONArray array) throws JSONException,
            ProfileFormatException {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (!(value instanceof String)) {
                throw new ProfileFormatException(ProfileFormatException.Reason.BAD_TYPE,
                        "configuration arrays must contain strings");
            }
            values.add((String) value);
        }
        return values;
    }

    private static ProfileFormatException malformedConfiguration(String message) {
        return new ProfileFormatException(ProfileFormatException.Reason.MALFORMED, message);
    }

    private LauncherConfiguration requireCurrent() {
        if (current == null) {
            throw new IllegalStateException("configuration not loaded");
        }
        return current;
    }

    /** Writes first; memory changes only after the bytes are safely replaced. */
    private void persist(LauncherConfiguration.Builder builder) throws IOException {
        if (readOnly) {
            throw new NewerFormatException();
        }
        builder.ui = current.ui;
        builder.navigationCityIndex = current.navigationCityIndex;
        LauncherConfiguration next = builder.build();
        storage.writeAtomically(serialize(next));
        current = next;
    }

    /** Builds the candidate and drops validation keys of affected capabilities. */
    private static ConfigurationChange change(LauncherConfiguration old,
            LauncherConfiguration.Builder builder) {
        Set<String> affected = affectedCapabilities(old, builder.build());
        builder.validationKeys.keySet().removeAll(affected);
        return new ConfigurationChange(old, builder.build(), affected);
    }

    private static Set<String> affectedCapabilities(LauncherConfiguration old,
            LauncherConfiguration next) {
        Set<String> keys = new LinkedHashSet<>();
        if (!old.roles.get(Role.NAVIGATION).sameAs(next.roles.get(Role.NAVIGATION))) {
            keys.add(CapabilityRegistry.KEY_NAVIGATION_APP);
        }
        for (Role media : new Role[] {Role.BLUETOOTH_MUSIC, Role.LOCAL_MUSIC, Role.RADIO}) {
            if (!old.roles.get(media).sameAs(next.roles.get(media))) {
                keys.add(CapabilityRegistry.KEY_MEDIA_APP);
            }
        }
        if (!equal(old.acceptedAdapterId, next.acceptedAdapterId)) {
            keys.add(CapabilityRegistry.KEY_ADAPTER_REVISION);
        }
        if (!equal(old.acceptedProfileId, next.acceptedProfileId)
                || old.acceptedProfileRevision != next.acceptedProfileRevision) {
            keys.add(CapabilityRegistry.KEY_SAFETY_PROFILE);
        }
        if (keys.isEmpty()) {
            return Collections.emptySet();
        }
        return CapabilityRegistry.invalidate(
                CapabilityRegistry.ChangeSet.of(keys.toArray(new String[0])));
    }

    private static LauncherConfiguration.Builder fromLegacy(Map<String, ?> legacy,
            boolean upgradedInstall) {
        LauncherConfiguration.Builder builder = LauncherConfiguration.builder();
        builder.migrationVersion = LauncherConfiguration.MIGRATION_VERSION;
        boolean existingInstall = upgradedInstall || !legacy.isEmpty();
        builder.curatedAppVisibility = existingInstall;
        builder.legacyXyAutoUser = false;
        for (Map.Entry<String, Role> entry : LEGACY_ROLE_KEYS.entrySet()) {
            Object value = legacy.get(entry.getKey());
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                Role role = entry.getValue();
                String strategy = role == Role.NAVIGATION ? "google-navigation" : "launch-package";
                builder.roleOverrides.put(role,
                        new RoleSelection((String) value, null, strategy, null));
            }
        }
        builder.ui = uiFrom(legacy);
        builder.navigationCityIndex = cityFrom(legacy);
        return builder;
    }

    private static LauncherConfiguration withLegacyPreferences(LauncherConfiguration config,
            Map<String, ?> legacy) {
        LauncherConfiguration.Builder builder = config.toBuilder();
        builder.ui = uiFrom(legacy);
        builder.navigationCityIndex = cityFrom(legacy);
        return builder.build();
    }

    private static LauncherConfiguration.UiPreferences uiFrom(Map<String, ?> legacy) {
        LauncherConfiguration.UiPreferences d = LauncherConfiguration.UiPreferences.defaults();
        List<String> order = new ArrayList<>();
        Object csv = legacy.get(ThemeConfig.KEY_APP_SAVED_ORDER);
        if (csv instanceof String) {
            for (String part : ((String) csv).split(",")) {
                if (!part.trim().isEmpty()) {
                    order.add(part.trim());
                }
            }
        }
        return new LauncherConfiguration.UiPreferences(
                intValue(legacy, ThemeConfig.KEY_THEME_ID, d.themeId),
                intValue(legacy, ThemeConfig.KEY_GLASS_OPACITY, d.glassOpacity),
                intValue(legacy, ThemeConfig.KEY_LANGUAGE_ID, d.languageId),
                boolValue(legacy, ThemeConfig.KEY_DOCK_LEFT, d.dockLeft),
                boolValue(legacy, ThemeConfig.KEY_SWAP_COLUMNS, d.swapDashboardColumns),
                intValue(legacy, ThemeConfig.KEY_GRID_COLUMNS, d.gridColumns),
                order,
                stringSet(legacy.get(ThemeConfig.KEY_APP_HIDDEN_PACKAGES)),
                stringSet(legacy.get(ThemeConfig.KEY_APP_UNHIDDEN_PACKAGES)));
    }

    private static Integer cityFrom(Map<String, ?> legacy) {
        Object value = legacy.get(ThemeConfig.KEY_CITY_INDEX);
        return value instanceof Integer ? (Integer) value : null;
    }

    private static int intValue(Map<String, ?> legacy, String key, int fallback) {
        Object value = legacy.get(key);
        return value instanceof Integer ? (Integer) value : fallback;
    }

    private static boolean boolValue(Map<String, ?> legacy, String key, boolean fallback) {
        Object value = legacy.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static Set<String> stringSet(Object value) {
        Set<String> set = new LinkedHashSet<>();
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                if (item instanceof String) {
                    set.add((String) item);
                }
            }
        }
        return set;
    }

    private static byte[] serialize(LauncherConfiguration config) {
        try {
            JSONObject root = new JSONObject();
            root.put("formatVersion", FORMAT_VERSION);
            root.put("migrationVersion", config.migrationVersion);
            root.put("appVisibility", visibility(config));
            root.put(LEGACY_XYAUTO_USER, config.legacyXyAutoUser);
            if (config.acceptedProfileId != null) {
                JSONObject profile = new JSONObject();
                profile.put("id", config.acceptedProfileId);
                profile.put("revision", config.acceptedProfileRevision);
                profile.put("adapterId", config.acceptedAdapterId);
                profile.put("roleDefaults", rolesJson(config.profileRoleDefaults, false));
                root.put("profile", profile);
            }
            root.put("roleOverrides", rolesJson(config.roleOverrides, true));
            JSONObject validation = new JSONObject();
            for (Map.Entry<String, String> entry : config.validationKeys.entrySet()) {
                validation.put(entry.getKey(), entry.getValue());
            }
            root.put("validationKeys", validation);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IllegalStateException("could not encode configuration", e);
        }
    }

    /** Classified stored data: missing, current, newer format, or corrupt. */
    private static final class Stored {
        final LauncherConfiguration configuration;
        final boolean newerFormat;
        final boolean corrupt;

        Stored(LauncherConfiguration configuration, boolean newerFormat, boolean corrupt) {
            this.configuration = configuration;
            this.newerFormat = newerFormat;
            this.corrupt = corrupt;
        }
    }

    private static Stored parse(byte[] bytes) {
        if (bytes == null) {
            return new Stored(null, false, false);
        }
        JSONObject root;
        try {
            root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            return new Stored(null, false, true);
        }
        int format = root.optInt("formatVersion", -1);
        if (format > FORMAT_VERSION) {
            LauncherConfiguration readable;
            try {
                readable = parseRoot(root);
            } catch (JSONException | RuntimeException e) {
                readable = null;
            }
            return new Stored(readable, true, false);
        }
        if (format != FORMAT_VERSION) {
            return new Stored(null, false, true);
        }
        try {
            return new Stored(parseRoot(root), false, false);
        } catch (JSONException e) {
            return new Stored(null, false, true);
        }
    }

    private static LauncherConfiguration parseRoot(JSONObject root) throws JSONException {
        LauncherConfiguration.Builder builder = LauncherConfiguration.builder();
        builder.migrationVersion = root.getInt("migrationVersion");
        builder.curatedAppVisibility =
                !VISIBILITY_DISCOVERED.equals(root.optString("appVisibility"));
        // Files written before this flag existed: appVisibility was then fixed at migration
        // from the same existing-install signal, so it is a faithful one-time fallback.
        // Older versions used this flag to infer a vendor adapter from upgrade status or any
        // legacy preference. Keep reading the file shape, but never trust that inference.
        builder.legacyXyAutoUser = false;
        JSONObject profile = root.optJSONObject("profile");
        if (profile != null) {
            builder.acceptedProfileId = profile.getString("id");
            builder.acceptedProfileRevision = profile.getInt("revision");
            builder.acceptedAdapterId = profile.getString("adapterId");
            readRoles(profile.optJSONObject("roleDefaults"), builder.profileRoleDefaults);
        }
        readRoles(root.optJSONObject("roleOverrides"), builder.roleOverrides);
        JSONObject validation = root.optJSONObject("validationKeys");
        if (validation != null) {
            Iterator<String> keys = validation.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (CapabilityRegistry.Capability.fromId(key) != null) {
                    builder.validationKeys.put(key, validation.getString(key));
                }
            }
        }
        return builder.build();
    }

    private static void readRoles(JSONObject json, Map<Role, RoleSelection> into)
            throws JSONException {
        if (json == null) {
            return;
        }
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Role role = Role.fromKey(key);
            JSONObject entry = json.optJSONObject(key);
            if (role == null || entry == null) {
                continue;
            }
            try {
                into.put(role, new RoleSelection(entry.getString("packageName"),
                        entry.optString("componentName", null), entry.getString("strategyId"),
                        entry.optString("versionIdentity", null)));
            } catch (IllegalArgumentException ignored) {
                // Skip an entry that is no longer valid; the next precedence level applies.
            }
        }
    }

    private static JSONObject rolesJson(Map<Role, RoleSelection> roles, boolean withVersion)
            throws JSONException {
        JSONObject json = new JSONObject();
        for (Map.Entry<Role, RoleSelection> entry : roles.entrySet()) {
            RoleSelection selection = entry.getValue();
            JSONObject item = new JSONObject();
            item.put("packageName", selection.packageName);
            if (selection.componentName != null) {
                item.put("componentName", selection.componentName);
            }
            item.put("strategyId", selection.strategyId);
            if (withVersion && selection.versionIdentity != null) {
                item.put("versionIdentity", selection.versionIdentity);
            }
            json.put(entry.getKey().key(), item);
        }
        return json;
    }

    private static JSONArray array(Collection<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private static String visibility(LauncherConfiguration config) {
        return config.curatedAppVisibility ? VISIBILITY_CURATED : VISIBILITY_DISCOVERED;
    }

    private static ProfileFormatException tooLarge() {
        return new ProfileFormatException(ProfileFormatException.Reason.TOO_LARGE,
                "profile exceeds " + ProfileCodec.MAX_PROFILE_BYTES + " bytes");
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** An upgrade from an older build means an existing user even with no saved preferences. */
    private static boolean isUpgradedInstall(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime > info.firstInstallTime;
        } catch (Exception e) {
            return true;
        }
    }

    @SuppressWarnings("deprecation")
    private static int versionCode(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    private static Map<String, Role> legacyRoleKeys() {
        Map<String, Role> keys = new LinkedHashMap<>();
        keys.put(ThemeConfig.KEY_PKG_MAPS, Role.NAVIGATION);
        keys.put(ThemeConfig.KEY_PKG_BT_MUSIC, Role.BLUETOOTH_MUSIC);
        keys.put(ThemeConfig.KEY_PKG_USB_MUSIC, Role.LOCAL_MUSIC);
        keys.put(ThemeConfig.KEY_PKG_DASHCAM, Role.DASHCAM);
        keys.put(ThemeConfig.KEY_PKG_EQUALIZER, Role.EQUALIZER);
        return Collections.unmodifiableMap(keys);
    }
}
