package com.cleanyourcar.launcher.config;

import com.cleanyourcar.launcher.PlusCodeCodec;

/**
 * Chooses the reference point used to recover short Plus Codes. A current location fix is used
 * only when permitted, finite, in range, at most ten minutes old, and accurate to 1,000m; the
 * explicit saved reference is the fallback. There is no implicit default city.
 */
public final class NavigationReference {

    public static final long MAX_LOCATION_AGE_MS = 10L * 60L * 1000L;
    public static final float MAX_LOCATION_ACCURACY_M = 1000f;

    public enum Source { CURRENT_LOCATION, SAVED }

    public enum Reason { NO_REFERENCE }

    private NavigationReference() {
    }

    /** A last-known location fix as reported by the platform. */
    public static final class LocationSample {
        /** No permission or no fix. */
        public static final LocationSample NONE =
                new LocationSample(false, Double.NaN, Double.NaN, Long.MAX_VALUE, Float.NaN);

        public final boolean permissionGranted;
        public final double lat;
        public final double lon;
        public final long ageMillis;
        /** Horizontal accuracy radius in meters; NaN when the fix reports none. */
        public final float accuracyMeters;

        public LocationSample(boolean permissionGranted, double lat, double lon, long ageMillis,
                float accuracyMeters) {
            this.permissionGranted = permissionGranted;
            this.lat = lat;
            this.lon = lon;
            this.ageMillis = ageMillis;
            this.accuracyMeters = accuracyMeters;
        }

        boolean isUsable() {
            return permissionGranted
                    && isUsableCoordinate(lat, lon)
                    && ageMillis >= 0 && ageMillis <= MAX_LOCATION_AGE_MS
                    && !Float.isNaN(accuracyMeters) && accuracyMeters >= 0f
                    && accuracyMeters <= MAX_LOCATION_ACCURACY_M;
        }
    }

    /** Reference explicitly chosen by the user, or {@link #NONE}. */
    public static final class SavedReference {
        public static final SavedReference NONE = new SavedReference(false, null, null, 0, 0);

        public final boolean present;
        /** City preset index when the reference is a preset, otherwise null. */
        public final Integer cityIndex;
        public final String label;
        public final double lat;
        public final double lon;

        private SavedReference(boolean present, Integer cityIndex, String label, double lat,
                double lon) {
            this.present = present;
            this.cityIndex = cityIndex;
            this.label = label;
            this.lat = lat;
            this.lon = lon;
        }

        /** Reference for a saved {@link PlusCodeCodec#VIETNAM_PRESETS} index; NONE if invalid. */
        public static SavedReference fromCityIndex(Integer index) {
            if (index == null || index < 0 || index >= PlusCodeCodec.VIETNAM_PRESETS.length) {
                return NONE;
            }
            PlusCodeCodec.CityPreset preset = PlusCodeCodec.VIETNAM_PRESETS[index];
            return new SavedReference(true, index, preset.name, preset.lat, preset.lon);
        }
    }

    public static final class ReferenceResult {
        public final boolean available;
        public final double lat;
        public final double lon;
        /** Where the coordinates came from; null when unavailable. */
        public final Source source;
        /** Why no reference exists; null when available. */
        public final Reason reason;

        private ReferenceResult(boolean available, double lat, double lon, Source source,
                Reason reason) {
            this.available = available;
            this.lat = lat;
            this.lon = lon;
            this.source = source;
            this.reason = reason;
        }
    }

    public static ReferenceResult resolve(LocationSample sample, SavedReference saved) {
        if (sample != null && sample.isUsable()) {
            return new ReferenceResult(true, sample.lat, sample.lon, Source.CURRENT_LOCATION,
                    null);
        }
        if (saved != null && saved.present) {
            return new ReferenceResult(true, saved.lat, saved.lon, Source.SAVED, null);
        }
        return new ReferenceResult(false, Double.NaN, Double.NaN, null, Reason.NO_REFERENCE);
    }

    private static boolean isUsableCoordinate(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || Double.isInfinite(lat)
                || Double.isInfinite(lon)) {
            return false;
        }
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            return false;
        }
        // A 0,0 fix is a common placeholder for "no location", not a real reference.
        return !(Math.abs(lat) < 0.00001 && Math.abs(lon) < 0.00001);
    }
}
