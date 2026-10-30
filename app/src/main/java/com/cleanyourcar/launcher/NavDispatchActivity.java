package com.cleanyourcar.launcher;

import android.app.Activity;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Exported zero-display trampoline activity that receives destination coordinates or Plus Codes
 * from addons/nav-bridge (BLE GATT / Hotspot Burst) or geo: intents, decodes them offline,
 * and opens Google Maps Live framed by OverlayHudService.
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

        int cityIdx = ThemeConfig.prefs(this).getInt(ThemeConfig.KEY_CITY_INDEX, 0);
        if (cityIdx < 0 || cityIdx >= PlusCodeCodec.VIETNAM_PRESETS.length) cityIdx = 0;
        double refLat = PlusCodeCodec.VIETNAM_PRESETS[cityIdx].lat;
        double refLon = PlusCodeCodec.VIETNAM_PRESETS[cityIdx].lon;

        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm != null) {
                Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc != null && Math.abs(loc.getLatitude()) > 0.1) {
                    refLat = loc.getLatitude();
                    refLon = loc.getLongitude();
                }
            }
        } catch (SecurityException ignored) {
        }

        if (intent.hasExtra("lat") && intent.hasExtra("lon")) {
            double lat = intent.getDoubleExtra("lat", 0.0);
            double lon = intent.getDoubleExtra("lon", 0.0);
            String label = intent.getStringExtra("label");
            if (NavPayloadParser.isValidLatLon(lat, lon)) {
                CarIntentRouter.launchCoordinatesInMapsLive(this, lat, lon, label);
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

        PlusCodeCodec.LatLon target = NavPayloadParser.parse(rawPayload, refLat, refLon);
        if (target != null) {
            CarIntentRouter.launchCoordinatesInMapsLive(this, target.lat, target.lon, target.label);
        } else {
            Toast.makeText(this, "Không đọc được tọa độ điểm đến", Toast.LENGTH_SHORT).show();
        }
    }
}
