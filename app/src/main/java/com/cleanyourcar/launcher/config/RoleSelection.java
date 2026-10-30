package com.cleanyourcar.launcher.config;

import com.cleanyourcar.launcher.compat.DeviceProfile;

/**
 * App chosen for a launcher role: package, optional flattened component, built-in launch
 * strategy, and the package version identity observed when it was selected (may be null).
 */
public final class RoleSelection {

    /** Launcher roles, mapped 1:1 to {@link DeviceProfile#ROLE_KEYS}. */
    public enum Role {
        NAVIGATION("navigation"),
        BLUETOOTH_MUSIC("bluetooth-music"),
        LOCAL_MUSIC("local-music"),
        RADIO("radio"),
        DASHCAM("dashcam"),
        EQUALIZER("equalizer"),
        SETTINGS("settings");

        private final String key;

        Role(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        /** Returns the role for a profile role key, or null when unknown. */
        public static Role fromKey(String key) {
            for (Role role : values()) {
                if (role.key.equals(key)) {
                    return role;
                }
            }
            return null;
        }
    }

    public final String packageName;
    /** Flattened component ("package/class" or "package/.Class"), or null. */
    public final String componentName;
    public final String strategyId;
    public final String versionIdentity;

    public RoleSelection(String packageName, String componentName, String strategyId,
            String versionIdentity) {
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("packageName is required");
        }
        if (!DeviceProfile.STRATEGY_IDS.contains(strategyId)) {
            throw new IllegalArgumentException("strategyId is not built in");
        }
        this.packageName = packageName.trim();
        this.componentName = emptyToNull(componentName);
        this.strategyId = strategyId;
        this.versionIdentity = emptyToNull(versionIdentity);
    }

    static RoleSelection fromProfileDefault(DeviceProfile.RoleDefault roleDefault) {
        return new RoleSelection(roleDefault.packageName, roleDefault.componentName,
                roleDefault.strategyId, null);
    }

    /** Same app identity, version, and launch path. */
    boolean sameAs(RoleSelection other) {
        return other != null && packageName.equals(other.packageName)
                && equalOrNull(componentName, other.componentName)
                && strategyId.equals(other.strategyId)
                && equalOrNull(versionIdentity, other.versionIdentity);
    }

    private static boolean equalOrNull(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
