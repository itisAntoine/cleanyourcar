package com.cleanyourcar.launcher;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import com.cleanyourcar.launcher.compat.CapabilityRegistry;
import com.cleanyourcar.launcher.compat.DeviceProfile;
import com.cleanyourcar.launcher.compat.EnvironmentFacts;
import com.cleanyourcar.launcher.compat.ProfileCodec;
import com.cleanyourcar.launcher.compat.ProfileFormatException;
import com.cleanyourcar.launcher.compat.ProfileMatcher;
import com.cleanyourcar.launcher.config.ConfigurationStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Shows how this device matches the bundled profiles, and previews a profile's role defaults and
 * capability claims before the user explicitly accepts it. Claims are never shown as verified.
 */
final class CompatibilityDialog {

    private CompatibilityDialog() {
    }

    /** Matches bundled profiles against this device and lets the user pick one to preview. */
    static void showMatch(final Context context, final ConfigurationStore store,
            final Runnable onApplied) {
        final ThemeConfig.Palette p = ThemeConfig.getActivePalette(context);
        final Dialog dialog = SetupUi.newDialog(context);
        LinearLayout root = SetupUi.card(context, p);
        root.addView(SetupUi.title(context, p, I18n.tr(context, I18n.KEY_SETUP_PROFILE)));

        EnvironmentFacts facts = EnvironmentFacts.collect(context);
        root.addView(SetupUi.note(context, p, String.format(
                I18n.tr(context, I18n.KEY_PLATFORM), facts.platform + " • SDK " + facts.sdk)));

        ProfileMatcher.MatchResult match =
                ProfileMatcher.match(loadBundledProfiles(context), facts);
        String status;
        switch (match.status) {
            case EXACT:
                status = String.format(I18n.tr(context, I18n.KEY_MATCH_EXACT),
                        match.profile().profileId);
                break;
            case PARTIAL:
                status = String.format(I18n.tr(context, I18n.KEY_MATCH_PARTIAL),
                        join(new ArrayList<>(match.missingFacts)));
                break;
            case CONFLICT:
                status = I18n.tr(context, I18n.KEY_MATCH_CONFLICT);
                break;
            case UNKNOWN:
            default:
                status = I18n.tr(context, I18n.KEY_MATCH_UNKNOWN);
                break;
        }
        root.addView(SetupUi.note(context, p, status));

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        // Candidates are only offered; nothing is applied without the preview confirmation.
        for (final DeviceProfile candidate : match.candidates) {
            SetupUi.addRow(list, SetupUi.button(context, p, candidate.profileId + " ("
                    + candidate.adapterId + ")", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    showPreview(context, store, candidate, store.previewProfile(candidate),
                            onApplied);
                }
            }));
        }
        SetupUi.addRow(list, SetupUi.button(context, p, I18n.tr(context, I18n.KEY_CLOSE), false,
                SetupUi.dismissOnClick(dialog)));
        root.addView(scroll(context, list));
        SetupUi.showDialog(dialog, root, false);
    }

    /** Lists what accepting the profile changes; applies it only after explicit confirmation. */
    static void showPreview(final Context context, final ConfigurationStore store,
            DeviceProfile profile, final ConfigurationStore.ConfigurationChange change,
            final Runnable onApplied) {
        final ThemeConfig.Palette p = ThemeConfig.getActivePalette(context);
        final Dialog dialog = SetupUi.newDialog(context);
        LinearLayout root = SetupUi.card(context, p);
        root.addView(SetupUi.title(context, p, profile.profileId + " • " + profile.adapterId));
        root.addView(SetupUi.note(context, p, I18n.tr(context, I18n.KEY_SETUP_PARKED)));

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(SetupUi.section(context, p, I18n.tr(context, I18n.KEY_PROFILE_CHANGES)));
        for (Map.Entry<String, DeviceProfile.RoleDefault> entry : profile.roleDefaults.entrySet()) {
            DeviceProfile.RoleDefault role = entry.getValue();
            list.addView(SetupUi.note(context, p, entry.getKey() + " → " + role.packageName
                    + " [" + role.strategyId + "]"));
        }

        list.addView(SetupUi.section(context, p, I18n.tr(context, I18n.KEY_CAPABILITY_CLAIMS)));
        for (CapabilityRegistry.Capability capability : CapabilityRegistry.Capability.values()) {
            CapabilityRegistry.Availability claimed =
                    CapabilityRegistry.claimedAvailability(profile, capability);
            CapabilityRegistry.CapabilityStatus status = CapabilityRegistry.evaluate(capability,
                    CapabilityRegistry.Evidence.untested(),
                    new CapabilityRegistry.RuntimeConditions(claimed,
                            Collections.<String, String>emptyMap(),
                            runtimeBlockers(context, capability), false, false));
            StringBuilder line = new StringBuilder(capability.id()).append(": ")
                    .append(status.availability.name().toLowerCase(java.util.Locale.US))
                    .append(" / ")
                    .append(status.validation.name().toLowerCase(java.util.Locale.US));
            if (!status.blockers.isEmpty()) {
                line.append(" — ").append(String.format(
                        I18n.tr(context, I18n.KEY_CAPABILITY_BLOCKED), join(status.blockers)));
            }
            list.addView(SetupUi.note(context, p, line.toString()));
        }

        SetupUi.addRow(list, SetupUi.button(context, p, I18n.tr(context, I18n.KEY_APPLY), true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        apply(context, store, change, onApplied);
                    }
                }));
        SetupUi.addRow(list, SetupUi.button(context, p, I18n.tr(context, I18n.KEY_CANCEL), false,
                SetupUi.dismissOnClick(dialog)));
        root.addView(scroll(context, list));
        SetupUi.showDialog(dialog, root, false);
    }

    /** Validates an imported document fully before previewing it. */
    static void importAndPreview(Context context, ConfigurationStore store, InputStream in,
            Runnable onApplied) {
        try {
            byte[] bytes = readBounded(in);
            ConfigurationStore.ConfigurationChange change = store.importProfile(bytes);
            DeviceProfile profile = ProfileCodec.parse(
                    new String(bytes, StandardCharsets.UTF_8), versionCode(context));
            showPreview(context, store, profile, change, onApplied);
        } catch (ProfileFormatException e) {
            toast(context, profileErrorText(context, e));
        } catch (IOException e) {
            toast(context, I18n.tr(context, I18n.KEY_FILE_ERROR));
        }
    }

    private static void apply(Context context, ConfigurationStore store,
            ConfigurationStore.ConfigurationChange change, Runnable onApplied) {
        try {
            store.applyProfile(change);
            toast(context, I18n.tr(context, I18n.KEY_PROFILE_APPLIED));
            if (onApplied != null) onApplied.run();
        } catch (ConfigurationStore.NewerFormatException e) {
            toast(context, I18n.tr(context, I18n.KEY_READ_ONLY));
        } catch (ProfileFormatException e) {
            toast(context, profileErrorText(context, e));
        } catch (IOException e) {
            toast(context, I18n.tr(context, I18n.KEY_SAVE_FAILED));
        }
    }

    /** Localized text derived from the stable reason code; never the exception message. */
    static String profileErrorText(Context context, ProfileFormatException e) {
        int key;
        switch (e.getReason()) {
            case TOO_LARGE:
                key = I18n.KEY_PROFILE_TOO_LARGE;
                break;
            case UNSUPPORTED_SCHEMA:
            case UNKNOWN_ADAPTER:
            case UNKNOWN_STRATEGY:
                key = I18n.KEY_PROFILE_UNSUPPORTED;
                break;
            case APP_TOO_OLD:
                key = I18n.KEY_PROFILE_APP_TOO_OLD;
                break;
            case TOO_MANY_PACKAGES:
                key = I18n.KEY_PROFILE_TOO_MANY;
                break;
            default:
                key = I18n.KEY_PROFILE_MALFORMED;
                break;
        }
        return I18n.tr(context, key) + " (" + e.getReason().code() + ")";
    }

    /** Profiles bundled from profiles/devices; unreadable or invalid ones are skipped. */
    static List<DeviceProfile> loadBundledProfiles(Context context) {
        List<DeviceProfile> profiles = new ArrayList<>();
        int versionCode = versionCode(context);
        try {
            String[] dirs = context.getAssets().list("");
            if (dirs == null) return profiles;
            for (String dir : dirs) {
                try (InputStream in = context.getAssets().open(dir + "/profile.json")) {
                    profiles.add(ProfileCodec.parse(
                            new String(readBounded(in), StandardCharsets.UTF_8), versionCode));
                } catch (IOException | ProfileFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return profiles;
    }

    private static List<String> runtimeBlockers(Context context,
            CapabilityRegistry.Capability capability) {
        List<String> blockers = new ArrayList<>();
        if (capability == CapabilityRegistry.Capability.OVERLAY
                && !Settings.canDrawOverlays(context)) {
            blockers.add(CapabilityRegistry.KEY_PERMISSION_OVERLAY);
        }
        if (capability == CapabilityRegistry.Capability.BRIGHTNESS
                && !Settings.System.canWrite(context)) {
            blockers.add(CapabilityRegistry.KEY_PERMISSION_WRITE_SETTINGS);
        }
        return blockers;
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > ProfileCodec.MAX_PROFILE_BYTES) {
                break;
            }
        }
        return out.toByteArray();
    }

    @SuppressWarnings("deprecation")
    private static int versionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode()
                    : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(", ");
            out.append(value);
        }
        return out.toString();
    }

    private static ScrollView scroll(Context context, LinearLayout list) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        return scroll;
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
