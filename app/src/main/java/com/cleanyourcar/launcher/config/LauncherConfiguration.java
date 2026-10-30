package com.cleanyourcar.launcher.config;

import com.cleanyourcar.launcher.CarIntentRouter;
import com.cleanyourcar.launcher.I18n;
import com.cleanyourcar.launcher.ThemeConfig;
import com.cleanyourcar.launcher.config.RoleSelection.Role;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable launcher configuration. Effective {@link #roles} follow the precedence: explicit
 * user override, then accepted profile default, then built-in default. Safety policy is never
 * part of this configuration.
 */
public final class LauncherConfiguration {

    /** Current one-time legacy preference migration version. */
    public static final int MIGRATION_VERSION = 1;

    private static final Map<Role, RoleSelection> BUILT_IN_ROLES = builtInRoles();

    /** 0 until legacy preferences have been migrated. */
    public final int migrationVersion;
    /** Accepted device profile, or null when none was accepted. */
    public final String acceptedProfileId;
    public final int acceptedProfileRevision;
    public final String acceptedAdapterId;
    public final Map<Role, RoleSelection> profileRoleDefaults;
    /** Explicit user choices; always win over profile and built-in defaults. */
    public final Map<Role, RoleSelection> roleOverrides;
    /** Effective selection for every role. */
    public final Map<Role, RoleSelection> roles;
    /** True keeps the curated car-app list; false shows all discovered launchable apps. */
    public final boolean curatedAppVisibility;
    public final UiPreferences ui;
    /** Explicit saved navigation reference city preset index, or null when never chosen. */
    public final Integer navigationCityIndex;
    /** Local verification keyed by capability ID; device-specific and never exported. */
    public final Map<String, String> validationKeys;

    private LauncherConfiguration(Builder builder) {
        this.migrationVersion = builder.migrationVersion;
        this.acceptedProfileId = builder.acceptedProfileId;
        this.acceptedProfileRevision = builder.acceptedProfileRevision;
        this.acceptedAdapterId = builder.acceptedAdapterId;
        this.profileRoleDefaults = immutableRoles(builder.profileRoleDefaults);
        this.roleOverrides = immutableRoles(builder.roleOverrides);
        this.curatedAppVisibility = builder.curatedAppVisibility;
        this.ui = builder.ui;
        this.navigationCityIndex = builder.navigationCityIndex;
        this.validationKeys = Collections.unmodifiableMap(new TreeMap<>(builder.validationKeys));

        Map<Role, RoleSelection> effective = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            RoleSelection selection = roleOverrides.get(role);
            if (selection == null) {
                selection = profileRoleDefaults.get(role);
            }
            if (selection == null) {
                selection = BUILT_IN_ROLES.get(role);
            }
            effective.put(role, selection);
        }
        this.roles = Collections.unmodifiableMap(effective);
    }

    /** Built-in default used when neither the user nor an accepted profile chose an app. */
    public static RoleSelection builtInRole(Role role) {
        return BUILT_IN_ROLES.get(role);
    }

    static Builder builder() {
        return new Builder();
    }

    Builder toBuilder() {
        Builder builder = new Builder();
        builder.migrationVersion = migrationVersion;
        builder.acceptedProfileId = acceptedProfileId;
        builder.acceptedProfileRevision = acceptedProfileRevision;
        builder.acceptedAdapterId = acceptedAdapterId;
        builder.profileRoleDefaults.putAll(profileRoleDefaults);
        builder.roleOverrides.putAll(roleOverrides);
        builder.curatedAppVisibility = curatedAppVisibility;
        builder.ui = ui;
        builder.navigationCityIndex = navigationCityIndex;
        builder.validationKeys.putAll(validationKeys);
        return builder;
    }

    /** UI and app-library preferences carried over from the legacy preference file. */
    public static final class UiPreferences {
        public final int themeId;
        public final int glassOpacity;
        public final int languageId;
        public final boolean dockLeft;
        public final boolean swapDashboardColumns;
        public final int gridColumns;
        /** Saved app order; empty when the user never reordered apps. */
        public final List<String> appOrder;
        public final Set<String> hiddenPackages;
        public final Set<String> unhiddenPackages;

        UiPreferences(int themeId, int glassOpacity, int languageId, boolean dockLeft,
                boolean swapDashboardColumns, int gridColumns, List<String> appOrder,
                Set<String> hiddenPackages, Set<String> unhiddenPackages) {
            this.themeId = themeId;
            this.glassOpacity = glassOpacity;
            this.languageId = languageId;
            this.dockLeft = dockLeft;
            this.swapDashboardColumns = swapDashboardColumns;
            this.gridColumns = gridColumns;
            this.appOrder = Collections.unmodifiableList(new ArrayList<>(appOrder));
            this.hiddenPackages = Collections.unmodifiableSet(new LinkedHashSet<>(hiddenPackages));
            this.unhiddenPackages =
                    Collections.unmodifiableSet(new LinkedHashSet<>(unhiddenPackages));
        }

        /** Same defaults as the existing {@link ThemeConfig} accessors. */
        static UiPreferences defaults() {
            return new UiPreferences(ThemeConfig.THEME_GLASSMORPHISM,
                    ThemeConfig.OPACITY_BALANCED, I18n.LANG_VI, true, false, 4,
                    Collections.<String>emptyList(), Collections.<String>emptySet(),
                    Collections.<String>emptySet());
        }
    }

    static final class Builder {
        int migrationVersion;
        String acceptedProfileId;
        int acceptedProfileRevision;
        String acceptedAdapterId;
        final Map<Role, RoleSelection> profileRoleDefaults = new EnumMap<>(Role.class);
        final Map<Role, RoleSelection> roleOverrides = new EnumMap<>(Role.class);
        boolean curatedAppVisibility = true;
        UiPreferences ui = UiPreferences.defaults();
        Integer navigationCityIndex;
        final Map<String, String> validationKeys = new TreeMap<>();

        LauncherConfiguration build() {
            return new LauncherConfiguration(this);
        }
    }

    private static Map<Role, RoleSelection> immutableRoles(Map<Role, RoleSelection> source) {
        Map<Role, RoleSelection> copy = new EnumMap<>(Role.class);
        copy.putAll(source);
        return Collections.unmodifiableMap(copy);
    }

    /** Existing head-unit defaults, kept so current users see unchanged behavior. */
    private static Map<Role, RoleSelection> builtInRoles() {
        Map<Role, RoleSelection> roles = new EnumMap<>(Role.class);
        roles.put(Role.NAVIGATION, new RoleSelection(CarIntentRouter.PKG_GOOGLE_MAPS, null,
                "google-navigation", null));
        roles.put(Role.BLUETOOTH_MUSIC, component(CarIntentRouter.PKG_BT_MUSIC,
                CarIntentRouter.CLS_BT_MAIN));
        roles.put(Role.LOCAL_MUSIC, component(CarIntentRouter.PKG_USB_MUSIC,
                CarIntentRouter.CLS_USB_MAIN));
        roles.put(Role.RADIO, new RoleSelection(CarIntentRouter.PKG_RADIO, null,
                "launch-package", null));
        roles.put(Role.DASHCAM, component(CarIntentRouter.PKG_DASHCAM,
                CarIntentRouter.CLS_DASHCAM_MAIN));
        roles.put(Role.EQUALIZER, component(CarIntentRouter.PKG_EQUALIZER,
                CarIntentRouter.CLS_EQUALIZER_APS));
        roles.put(Role.SETTINGS, new RoleSelection("com.android.settings", null,
                "launch-package", null));
        return Collections.unmodifiableMap(roles);
    }

    private static RoleSelection component(String packageName, String className) {
        return new RoleSelection(packageName, packageName + "/" + className, "launch-component",
                null);
    }
}
