package com.cleanyourcar.launcher.compat;

/** Rejected device profile. {@link #getReason()} is a stable, user-displayable code. */
public final class ProfileFormatException extends Exception {

    public enum Reason {
        TOO_LARGE("too_large"),
        MALFORMED("malformed"),
        MISSING_FIELD("missing_field"),
        UNKNOWN_FIELD("unknown_field"),
        BAD_TYPE("bad_type"),
        INVALID_VALUE("invalid_value"),
        UNSUPPORTED_SCHEMA("unsupported_schema"),
        UNKNOWN_ADAPTER("unknown_adapter"),
        UNKNOWN_STRATEGY("unknown_strategy"),
        APP_TOO_OLD("app_too_old"),
        TOO_MANY_PACKAGES("too_many_packages");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Reason reason;

    public ProfileFormatException(Reason reason, String message) {
        super(reason.code() + ": " + message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
