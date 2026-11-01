package com.cleanyourcar.launcher;

import java.util.Locale;

/**
 * Pure-math Open Location Code (Plus Code) offline decoder supporting both full global codes
 * (e.g. "7P28QMVX+2P") and short local codes (e.g. "QMVX+2P") recovered near a reference city.
 */
public final class PlusCodeCodec {
    public static final String ALPHABET = "23456789CFGHJMPQRVWX";
    private static final double[] PAIR_RESOLUTIONS = new double[] {
            20.0, 1.0, 0.05, 0.0025, 0.000125
    };

    public static final class LatLon {
        public final double lat;
        public final double lon;
        public final String label;

        public LatLon(double lat, double lon, String label) {
            this.lat = lat;
            this.lon = lon;
            this.label = label;
        }
    }

    public static final class CityPreset {
        public final String name;
        public final double lat;
        public final double lon;

        public CityPreset(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    public static final CityPreset[] VIETNAM_PRESETS = new CityPreset[] {
            new CityPreset("TP. Hồ Chí Minh", 10.7769, 106.7009),
            new CityPreset("Hà Nội", 21.0285, 105.8542),
            new CityPreset("Đà Nẵng", 16.0544, 108.2022),
            new CityPreset("Cần Thơ", 10.0452, 105.7469),
            new CityPreset("Nha Trang", 12.2388, 109.1967),
            new CityPreset("Đà Lạt", 11.9404, 108.4583),
            new CityPreset("Vũng Tàu", 10.3460, 107.0843)
    };

    private PlusCodeCodec() {
    }

    public static String normalize(String rawCode) {
        if (rawCode == null) return "";
        String trimmed = rawCode.trim().toUpperCase(Locale.US);
        // Extract first token if city name was appended (e.g. "QMVX+2P Ho Chi Minh")
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx > 0) {
            trimmed = trimmed.substring(0, spaceIdx).trim();
        }
        // Allow pure 6/7-char local codes or 10-char full codes typed without '+'
        if (trimmed.indexOf('+') < 0) {
            if (trimmed.length() == 6 || trimmed.length() == 7) {
                trimmed = trimmed.substring(0, 4) + "+" + trimmed.substring(4);
            } else if (trimmed.length() == 10 || trimmed.length() == 11) {
                trimmed = trimmed.substring(0, 8) + "+" + trimmed.substring(8);
            }
        }
        return trimmed;
    }

    public static boolean isValidCode(String rawCode) {
        String code = normalize(rawCode);
        if (code.length() < 5 || code.length() > 13) return false;
        int plusIdx = code.indexOf('+');
        if (plusIdx != 4 && plusIdx != 6 && plusIdx != 8) return false;
        if (code.lastIndexOf('+') != plusIdx) return false;

        String afterPlus = code.substring(plusIdx + 1);
        if (afterPlus.length() < 2 || afterPlus.length() > 4) return false;

        for (int i = 0; i < code.length(); i++) {
            if (i == plusIdx) continue;
            char c = code.charAt(i);
            if (ALPHABET.indexOf(c) < 0) return false;
        }
        return true;
    }

    public static LatLon decode(String rawCode, double refLat, double refLon) {
        if (!isValidCode(rawCode)) return null;
        String code = normalize(rawCode);
        int plusIdx = code.indexOf('+');
        String prefix = code.substring(0, plusIdx);
        String suffix = code.substring(plusIdx + 1);

        if (plusIdx == 8) {
            return decodeFullNormalized(prefix + suffix, code);
        }

        // Short code (4 or 6 chars before '+'): recover relative to (refLat, refLon)
        double clampedRefLat = Math.max(-90.0, Math.min(90.0, refLat)) + 90.0;
        double clampedRefLon = Math.max(-180.0, Math.min(180.0, refLon)) + 180.0;

        // Decode suffix + existing prefix in local grid
        double padRes = (plusIdx == 4) ? 1.0 : 0.05;
        double baseLat = Math.floor(clampedRefLat / padRes) * padRes;
        double baseLon = Math.floor(clampedRefLon / padRes) * padRes;

        String localDigits = prefix + suffix;
        int startPairIndex = (8 - plusIdx) / 2; // 2 for 4-char prefix, 1 for 6-char prefix

        double localLat = 0.0;
        double localLon = 0.0;
        double lastLatRes = PAIR_RESOLUTIONS[4];
        double lastLonRes = PAIR_RESOLUTIONS[4];

        int pairCount = Math.min(localDigits.length() / 2, 5 - startPairIndex);
        for (int p = 0; p < pairCount; p++) {
            int latVal = ALPHABET.indexOf(localDigits.charAt(p * 2));
            int lonVal = ALPHABET.indexOf(localDigits.charAt(p * 2 + 1));
            if (latVal < 0 || lonVal < 0) return null;
            double res = PAIR_RESOLUTIONS[startPairIndex + p];
            localLat += latVal * res;
            localLon += lonVal * res;
            lastLatRes = res;
            lastLonRes = res;
        }

        double candidateLat = baseLat + localLat + (lastLatRes / 2.0);
        double candidateLon = baseLon + localLon + (lastLonRes / 2.0);

        if (candidateLat - clampedRefLat > padRes / 2.0 && candidateLat - padRes >= 0.0) {
            candidateLat -= padRes;
        } else if (clampedRefLat - candidateLat > padRes / 2.0 && candidateLat + padRes <= 180.0) {
            candidateLat += padRes;
        }

        if (candidateLon - clampedRefLon > padRes / 2.0) {
            candidateLon -= padRes;
        } else if (clampedRefLon - candidateLon > padRes / 2.0) {
            candidateLon += padRes;
        }

        double finalLat = candidateLat - 90.0;
        double finalLon = candidateLon - 180.0;
        if (finalLat < -90.0 || finalLat > 90.0 || finalLon < -180.0 || finalLon > 180.0) {
            return null;
        }
        return new LatLon(finalLat, finalLon, code);
    }

    private static LatLon decodeFullNormalized(String digitsWithoutPlus, String label) {
        if (digitsWithoutPlus.length() < 10) return null;
        double lat = 0.0;
        double lon = 0.0;
        double lastRes = PAIR_RESOLUTIONS[4];

        for (int p = 0; p < 5; p++) {
            int latVal = ALPHABET.indexOf(digitsWithoutPlus.charAt(p * 2));
            int lonVal = ALPHABET.indexOf(digitsWithoutPlus.charAt(p * 2 + 1));
            if (latVal < 0 || lonVal < 0) return null;
            double res = PAIR_RESOLUTIONS[p];
            lat += latVal * res;
            lon += lonVal * res;
            lastRes = res;
        }

        double centerLat = (lat + lastRes / 2.0) - 90.0;
        double centerLon = (lon + lastRes / 2.0) - 180.0;
        if (centerLat < -90.0 || centerLat > 90.0 || centerLon < -180.0 || centerLon > 180.0) {
            return null;
        }
        return new LatLon(centerLat, centerLon, label);
    }
}
