package com.cleanyourcar.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import com.cleanyourcar.launcher.compat.EnvironmentFacts;
import com.cleanyourcar.launcher.config.ConfigurationStore;
import com.cleanyourcar.launcher.config.LauncherConfiguration;
import com.cleanyourcar.launcher.config.NavigationReference;
import com.cleanyourcar.launcher.config.RoleSelection.Role;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.Set;

/**
 * APK-first device setup: profile choice, an app per role, optional HOME, and the explicit
 * Plus Code reference. Every step is optional and parked-use only; consent is saved per feature
 * and nothing here marks a capability verified.
 */
public class SetupActivity extends Activity {

    static final String PREFS = "cleanyourcar_setup";
    static final String KEY_SETUP_SEEN = "setup_seen";
    private static final String KEY_CONSENT_PREFIX = "consent_";
    private static final String KEY_REFERENCE_MODE = "reference_mode";

    private static final int REQ_HOME = 1;
    private static final int REQ_IMPORT = 2;
    private static final int REQ_EXPORT = 3;
    private static final int REQ_LOCATION = 4;

    private ConfigurationStore store;
    private SetupFlow flow;
    private LinearLayout content;

    /** Opens setup once for generic installs (not legacy XYAuto, no accepted profile). */
    static void maybeAutoOpen(Activity activity) {
        LauncherConfiguration config = ConfigurationStore.get(activity).load(activity);
        boolean seen = setupPrefs(activity).getBoolean(KEY_SETUP_SEEN, false);
        if (SetupFlow.shouldAutoOpen(seen, config.legacyXyAutoUser,
                config.acceptedProfileId != null)) {
            activity.startActivity(new Intent(activity, SetupActivity.class));
        }
    }

    static SharedPreferences setupPrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static NavigationReference.Mode referenceMode(Context context) {
        return NavigationReference.Mode.fromKey(
                setupPrefs(context).getString(KEY_REFERENCE_MODE, null));
    }

    static void setReferenceMode(Context context, NavigationReference.Mode mode) {
        setupPrefs(context).edit().putString(KEY_REFERENCE_MODE, mode.key()).apply();
    }

    static boolean isConsented(Context context, SetupFlow.Feature feature) {
        return setupPrefs(context).getBoolean(consentKey(feature), false);
    }

    private static String consentKey(SetupFlow.Feature feature) {
        return KEY_CONSENT_PREFIX + feature.name().toLowerCase(java.util.Locale.US);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = ConfigurationStore.get(this);
        flow = new SetupFlow(savedConsents());
        // Shown once automatically; afterwards only from settings, even if the user skipped it.
        setupPrefs(this).edit().putBoolean(KEY_SETUP_SEEN, true).apply();

        ScrollView scroll = new ScrollView(this);
        content = SetupUi.card(this, ThemeConfig.getActivePalette(this));
        scroll.addView(content);
        setContentView(scroll);
        render();
    }

    private void render() {
        final ThemeConfig.Palette p = ThemeConfig.getActivePalette(this);
        LauncherConfiguration config = store.load(this);
        content.removeAllViews();

        content.addView(SetupUi.title(this, p, I18n.tr(this, I18n.KEY_SETUP_TITLE)));
        content.addView(SetupUi.note(this, p, I18n.tr(this, I18n.KEY_SETUP_PARKED)));
        EnvironmentFacts facts = EnvironmentFacts.collect(this);
        if (EnvironmentFacts.PLATFORM_AAOS.equals(facts.platform)) {
            content.addView(SetupUi.note(this, p, I18n.tr(this, I18n.KEY_SETUP_AAOS)));
        }
        if (store.isReadOnly()) {
            content.addView(SetupUi.note(this, p, I18n.tr(this, I18n.KEY_READ_ONLY)));
        }

        // 1. Device profile
        content.addView(SetupUi.section(this, p, I18n.tr(this, I18n.KEY_SETUP_PROFILE)));
        content.addView(SetupUi.note(this, p, config.acceptedProfileId != null
                ? config.acceptedProfileId : I18n.tr(this, I18n.KEY_NOT_SET)));
        SetupUi.addRow(content, SetupUi.button(this, p, I18n.tr(this, I18n.KEY_SETUP_CHECK),
                false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        CompatibilityDialog.showMatch(SetupActivity.this, store, rerender());
                    }
                }));
        SetupUi.addRow(content, SetupUi.button(this, p, I18n.tr(this, I18n.KEY_SETUP_IMPORT),
                false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        pick.addCategory(Intent.CATEGORY_OPENABLE);
                        pick.setType("*/*");
                        pick.putExtra(Intent.EXTRA_MIME_TYPES,
                                new String[] {"application/json", "text/plain"});
                        startForResult(pick, REQ_IMPORT);
                    }
                }));
        SetupUi.addRow(content, SetupUi.button(this, p, I18n.tr(this, I18n.KEY_SETUP_EXPORT),
                false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        create.addCategory(Intent.CATEGORY_OPENABLE);
                        create.setType("application/json");
                        create.putExtra(Intent.EXTRA_TITLE, "cleanyourcar-config.json");
                        startForResult(create, REQ_EXPORT);
                    }
                }));

        // 2. App per role
        content.addView(SetupUi.section(this, p, I18n.tr(this, I18n.KEY_SETUP_ROLES)));
        for (final Role role : Role.values()) {
            CarIntentRouter.ResolvedRole resolved = CarIntentRouter.resolveRole(config, role);
            String value = resolved.isAvailable()
                    ? resolved.selection.packageName + (role == Role.NAVIGATION
                            ? " • " + RolePickerDialog.strategyLabel(this,
                                    resolved.selection.strategyId) : "")
                    : I18n.tr(this, I18n.KEY_NOT_SET);
            SetupUi.addRow(content, SetupUi.button(this, p,
                    RolePickerDialog.roleLabel(this, role) + ": " + value, false,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            RolePickerDialog.show(SetupActivity.this, role, rerender());
                        }
                    }));
        }

        // 3. Optional HOME
        content.addView(SetupUi.section(this, p, I18n.tr(this, I18n.KEY_SETUP_HOME)));
        boolean isHome = isDefaultHome();
        SetupUi.addRow(content, SetupUi.button(this, p, I18n.tr(this, isHome
                ? I18n.KEY_HOME_DONE : I18n.KEY_SETUP_HOME), isHome, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestHome();
                    }
                }));

        // 4. Plus Code reference
        content.addView(SetupUi.section(this, p, I18n.tr(this, I18n.KEY_SETUP_REFERENCE)));
        NavigationReference.Mode mode = referenceMode(this);
        Integer saved = mode == NavigationReference.Mode.NONE
                ? null : config.savedNavigationReference.cityIndex;
        for (int i = 0; i < PlusCodeCodec.VIETNAM_PRESETS.length; i++) {
            final int index = i;
            SetupUi.addRow(content, SetupUi.button(this, p, PlusCodeCodec.VIETNAM_PRESETS[i].name,
                    saved != null && saved == i, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            ThemeConfig.prefs(SetupActivity.this).edit()
                                    .putInt(ThemeConfig.KEY_CITY_INDEX, index).apply();
                            setReferenceMode(SetupActivity.this, NavigationReference.Mode.CITY);
                            render();
                        }
                    }));
        }
        SetupUi.addRow(content, SetupUi.button(this, p,
                I18n.tr(this, I18n.KEY_USE_CURRENT_LOCATION),
                mode == NavigationReference.Mode.CURRENT_LOCATION
                        && flow.isConsented(SetupFlow.Feature.CURRENT_LOCATION)
                        && hasLocationPermission(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chooseReference(SetupFlow.ReferenceChoice.CURRENT_LOCATION);
                    }
                }));
        SetupUi.addRow(content, SetupUi.button(this, p, I18n.tr(this, I18n.KEY_NO_REFERENCE),
                mode == NavigationReference.Mode.NONE, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chooseReference(SetupFlow.ReferenceChoice.NONE);
                    }
                }));

        SetupUi.addRow(content, SetupUi.button(this, p, I18n.tr(this, I18n.KEY_SETUP_DONE),
                true, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (flow.canFinish()) finish();
                    }
                }));
    }

    private Runnable rerender() {
        return new Runnable() {
            @Override
            public void run() {
                render();
            }
        };
    }

    private void chooseReference(SetupFlow.ReferenceChoice choice) {
        if (choice == SetupFlow.ReferenceChoice.NONE) {
            ThemeConfig.prefs(this).edit().remove(ThemeConfig.KEY_CITY_INDEX).apply();
            setReferenceMode(this, NavigationReference.Mode.NONE);
            flow.onLocationPermissionResult(false);
            saveConsents();
            render();
            return;
        }
        // A saved city stays as the fallback when no fresh location fix is available.
        if (SetupFlow.requiresLocationPermission(choice)) {
            if (hasLocationPermission()) {
                onLocationResult(true);
            } else {
                requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            boolean granted = false;
            for (int result : grantResults) {
                granted |= result == PackageManager.PERMISSION_GRANTED;
            }
            onLocationResult(granted);
        }
    }

    private void onLocationResult(boolean granted) {
        flow.onLocationPermissionResult(granted);
        saveConsents();
        if (granted) {
            setReferenceMode(this, NavigationReference.Mode.CURRENT_LOCATION);
        } else {
            toast(I18n.tr(this, I18n.KEY_LOCATION_DENIED));
        }
        render();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestHome() {
        if (isDefaultHome()) {
            onHomeResult(SetupFlow.HomeResult.SET_AS_HOME);
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager roleManager = getSystemService(RoleManager.class);
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    startActivityForResult(
                            roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME);
                    return;
                }
            }
            startActivityForResult(new Intent(Settings.ACTION_HOME_SETTINGS), REQ_HOME);
        } catch (RuntimeException e) {
            onHomeResult(SetupFlow.HomeResult.UNAVAILABLE);
        }
    }

    private void onHomeResult(SetupFlow.HomeResult result) {
        flow.onHomeResult(result);
        saveConsents();
        int key = result == SetupFlow.HomeResult.SET_AS_HOME ? I18n.KEY_HOME_DONE
                : result == SetupFlow.HomeResult.UNAVAILABLE ? I18n.KEY_HOME_UNAVAILABLE
                : I18n.KEY_HOME_DECLINED;
        toast(I18n.tr(this, key));
        render();
    }

    private boolean isDefaultHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = getPackageManager().resolveActivity(home,
                PackageManager.MATCH_DEFAULT_ONLY);
        return info != null && info.activityInfo != null
                && getPackageName().equals(info.activityInfo.packageName);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_HOME) {
            if (isDefaultHome()) {
                onHomeResult(SetupFlow.HomeResult.SET_AS_HOME);
            } else {
                onHomeResult(resultCode == RESULT_CANCELED ? SetupFlow.HomeResult.CANCELLED
                        : SetupFlow.HomeResult.DECLINED);
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQ_IMPORT) {
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                if (in == null) throw new IOException("no stream");
                CompatibilityDialog.importAndPreview(this, store, in, rerender());
            } catch (IOException | RuntimeException e) {
                toast(I18n.tr(this, I18n.KEY_FILE_ERROR));
            }
        } else if (requestCode == REQ_EXPORT) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new IOException("no stream");
                store.exportSanitized(out);
                toast(I18n.tr(this, I18n.KEY_EXPORT_DONE));
            } catch (IOException | RuntimeException e) {
                toast(I18n.tr(this, I18n.KEY_FILE_ERROR));
            }
        }
    }

    private void startForResult(Intent intent, int requestCode) {
        try {
            startActivityForResult(intent, requestCode);
        } catch (RuntimeException e) {
            toast(I18n.tr(this, I18n.KEY_FILE_ERROR));
        }
    }

    private Set<SetupFlow.Feature> savedConsents() {
        SharedPreferences prefs = setupPrefs(this);
        EnumSet<SetupFlow.Feature> consents = EnumSet.noneOf(SetupFlow.Feature.class);
        for (SetupFlow.Feature feature : SetupFlow.Feature.values()) {
            if (prefs.getBoolean(consentKey(feature), false)) {
                consents.add(feature);
            }
        }
        return consents;
    }

    private void saveConsents() {
        SharedPreferences.Editor editor = setupPrefs(this).edit();
        for (SetupFlow.Feature feature : SetupFlow.Feature.values()) {
            editor.putBoolean(consentKey(feature), flow.isConsented(feature));
        }
        editor.apply();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
