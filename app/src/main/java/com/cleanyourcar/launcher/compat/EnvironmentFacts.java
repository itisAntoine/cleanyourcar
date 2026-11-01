package com.cleanyourcar.launcher.compat;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable public facts about the running device used for profile matching. Only public
 * framework values are read; unavailable, blank, or "unknown" values are kept missing.
 */
public final class EnvironmentFacts {

    public static final String PLATFORM_AFTERMARKET = "aftermarket";
    public static final String PLATFORM_AAOS = "aaos";

    public final String platform;
    /** API level from {@code Build.VERSION.SDK_INT}; release text is never parsed. */
    public final int sdk;
    /** Present facts only, keyed by {@link DeviceProfile#BUILD_PROPERTY_KEYS}, sorted by key. */
    public final Map<String, String> buildProperties;

    private EnvironmentFacts(String platform, int sdk, Map<String, String> buildProperties) {
        this.platform = platform;
        this.sdk = sdk;
        this.buildProperties = Collections.unmodifiableMap(buildProperties);
    }

    /** Pure factory; unknown keys and blank or "unknown" values are dropped. */
    public static EnvironmentFacts of(String platform, int sdk, Map<String, String> raw) {
        if (!DeviceProfile.PLATFORMS.contains(platform)) {
            throw new IllegalArgumentException("unknown platform: " + platform);
        }
        Map<String, String> present = new TreeMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String value = entry.getValue();
            if (DeviceProfile.BUILD_PROPERTY_KEYS.contains(entry.getKey()) && isKnown(value)) {
                present.put(entry.getKey(), value);
            }
        }
        return new EnvironmentFacts(platform, sdk, present);
    }

    public static EnvironmentFacts collect(Context context) {
        Map<String, String> raw = new TreeMap<>();
        raw.put("board", Build.BOARD);
        raw.put("product", Build.PRODUCT);
        raw.put("manufacturer", Build.MANUFACTURER);
        raw.put("display", Build.DISPLAY);
        raw.put("fingerprint", Build.FINGERPRINT);
        boolean automotive = context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
        return of(automotive ? PLATFORM_AAOS : PLATFORM_AFTERMARKET, Build.VERSION.SDK_INT, raw);
    }

    /** SHA-256 hex digest of platform, sdk, and sorted build properties. */
    public String environmentKey() {
        StringBuilder canonical = new StringBuilder();
        canonical.append("platform=").append(platform).append('\n');
        canonical.append("sdk=").append(sdk).append('\n');
        for (Map.Entry<String, String> entry : buildProperties.entrySet()) {
            canonical.append(entry.getKey()).append('=').append(entry.getValue().length())
                    .append(':').append(entry.getValue()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean isKnown(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return !trimmed.isEmpty() && !"unknown".equalsIgnoreCase(trimmed);
    }
}
