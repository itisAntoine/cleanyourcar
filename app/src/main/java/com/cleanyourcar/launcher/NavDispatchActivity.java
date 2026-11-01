package com.cleanyourcar.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;

import com.cleanyourcar.launcher.config.ConfigurationStore;
import com.cleanyourcar.launcher.config.NavigationReference;

/**
 * Exported zero-display trampoline activity that receives destination coordinates or Plus Codes
 * from addons/nav-bridge (BLE GATT / Hotspot Burst) or geo: intents, decodes them offline,
 * and sends them to the navigation app chosen in setup using its explicit strategy.
 */
public class NavDispatchActivity extends Activity {
    public static final String ACTION_NAVIGATE = "com.cleanyourcar.launcher.action.NAVIGATE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIncomingIntent(getIntent());
        finish();
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        if (intent.hasExtra("lat") && intent.hasExtra("lon")) {
            double lat = intent.getDoubleExtra("lat", 0.0);
            double lon = intent.getDoubleExtra("lon", 0.0);
            String label = intent.getStringExtra("label");
            if (NavPayloadParser.isValidLatLon(lat, lon)) {
                CarIntentRouter.launchNavigation(this, new PlusCodeCodec.LatLon(lat, lon, label));
                return;
            }
        }

        String rawPayload = intent.getStringExtra("q");
        if (rawPayload == null || rawPayload.isEmpty()) {
            rawPayload = intent.getStringExtra("payload");
        }
        if ((rawPayload == null || rawPayload.isEmpty()) && intent.getData() != null) {
            Uri data = intent.getData();
            rawPayload = data.toString();
        }

        // Full codes and coordinates bypass reference resolution entirely.
        NavigationReference.ReferenceResult reference = null;
        if (NavPayloadParser.requiresReference(rawPayload)) {
            reference = currentReference(this);
            if (!reference.available) {
                Toast.makeText(this, I18n.tr(this, I18n.KEY_NEED_REFERENCE), Toast.LENGTH_LONG)
                        .show();
                return;
            }
        }
        PlusCodeCodec.LatLon target = NavPayloadParser.parse(rawPayload, reference);
        if (target != null) {
            CarIntentRouter.launchNavigation(this, target);
        } else {
            Toast.makeText(this, I18n.tr(this, I18n.KEY_INVALID_DESTINATION), Toast.LENGTH_SHORT)
                    .show();
        }
    }

    /**
     * Reference for short Plus Codes following the setup choice: a fresh, accurate location fix
     * only in current-location mode with consent, else the city saved explicitly, else none.
     * The "None" choice disables both.
     */
    static NavigationReference.ReferenceResult currentReference(Context context) {
        NavigationReference.Mode mode = SetupActivity.referenceMode(context);
        boolean consented = SetupActivity.isConsented(context, SetupFlow.Feature.CURRENT_LOCATION);
        NavigationReference.LocationSample sample =
                NavigationReference.usesLocation(mode, consented)
                        ? locationSample(context) : NavigationReference.LocationSample.NONE;
        return NavigationReference.resolve(mode, consented, sample,
                ConfigurationStore.get(context).load(context).savedNavigationReference);
    }

    private static NavigationReference.LocationSample locationSample(Context context) {
        boolean granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            return NavigationReference.LocationSample.NONE;
        }
        Location newest = null;
        try {
            LocationManager lm = (LocationManager) context.getSystemService(LOCATION_SERVICE);
            if (lm != null) {
                for (String provider : lm.getProviders(true)) {
                    Location loc = lm.getLastKnownLocation(provider);
                    if (loc != null && (newest == null || loc.getElapsedRealtimeNanos()
                            > newest.getElapsedRealtimeNanos())) {
                        newest = loc;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        if (newest == null) {
            return NavigationReference.LocationSample.NONE;
        }
        long ageMillis = (SystemClock.elapsedRealtimeNanos() - newest.getElapsedRealtimeNanos())
                / 1_000_000L;
        float accuracy = newest.hasAccuracy() ? newest.getAccuracy() : Float.NaN;
        return new NavigationReference.LocationSample(true, newest.getLatitude(),
                newest.getLongitude(), ageMillis, accuracy);
    }
}
