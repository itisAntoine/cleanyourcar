package com.cleanyourcar.launcher;

import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses destination payloads arriving from Plus Code dialog, Bluetooth BLE GATT,
 * or Hotspot Burst (raw "lat,lon", "geo:...", Google Maps URLs, or Plus Codes).
 */
public final class NavPayloadParser {
    private static final Pattern COORD_PAIR_PATTERN = Pattern.compile(
            "(?<![0-9.-])(-?\\d{1,3}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)(?![0-9.])");

    private NavPayloadParser() {
    }

    public static PlusCodeCodec.LatLon parse(String rawPayload, double refLat, double refLon) {
        if (rawPayload == null) return null;
        String cleaned = rawPayload.trim();
        if (cleaned.isEmpty()) return null;

        try {
            if (cleaned.contains("%")) {
                cleaned = URLDecoder.decode(cleaned.replace("+", "%2B"), "UTF-8").trim();
            }
        } catch (Exception ignored) {
        }

        // Strip custom prefix if present (e.g. "@NAV:10.7769,106.7009")
        if (cleaned.startsWith("@NAV:") || cleaned.startsWith("@GEO:")) {
            cleaned = cleaned.substring(5).trim();
        }

        // 1. Check if it is directly a valid Plus Code
        PlusCodeCodec.LatLon plusResult = PlusCodeCodec.decode(cleaned, refLat, refLon);
        if (plusResult != null) {
            return plusResult;
        }

        // 2. Check for coordinate pair anywhere in string (raw "lat,lon", "geo:lat,lon", or Maps URL "@lat,lon")
        Matcher matcher = COORD_PAIR_PATTERN.matcher(cleaned);
        if (matcher.find()) {
            try {
                double lat = Double.parseDouble(matcher.group(1));
                double lon = Double.parseDouble(matcher.group(2));
                if (isValidLatLon(lat, lon)) {
                    return new PlusCodeCodec.LatLon(lat, lon, String.format("%.5f,%.5f", lat, lon));
                }
            } catch (NumberFormatException ignored) {
            }
        }

        return null;
    }

    public static boolean isValidLatLon(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return false;
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return false;
        return !(Math.abs(lat) < 0.00001 && Math.abs(lon) < 0.00001);
    }
}
