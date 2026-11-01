package com.cleanyourcar.navbridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Verifies the locally paired client credential. Tokens are random 256-bit values compared in
 * constant time; credentials travel in an HTTP header or a BLE envelope, never in a URL.
 */
public final class BridgeAuthorization {
    public static final int TOKEN_BYTES = 32;
    public static final int CLIENT_ID_BYTES = 8;

    private static final byte[] UNKNOWN_CLIENT_DIGEST = sha256("cleanyourcar-navbridge-no-client");

    private final String clientId;
    private final byte[] tokenDigest;

    private BridgeAuthorization(String clientId, String token) {
        this.clientId = clientId;
        this.tokenDigest = token == null ? null : sha256(token);
    }

    /** Authorization that accepts nothing (no paired client). */
    public static BridgeAuthorization none() {
        return new BridgeAuthorization(null, null);
    }

    public static BridgeAuthorization forClient(String clientId, String token) {
        if (!isValidClientId(clientId) || !isValidToken(token)) return none();
        return new BridgeAuthorization(clientId, token);
    }

    public static Credential newCredential(SecureRandom random) {
        byte[] id = new byte[CLIENT_ID_BYTES];
        byte[] token = new byte[TOKEN_BYTES];
        random.nextBytes(id);
        random.nextBytes(token);
        return new Credential(hex(id), hex(token));
    }

    public boolean verify(String clientId, String token) {
        byte[] presented = sha256(token == null ? "" : token);
        boolean known = this.clientId != null && this.tokenDigest != null && this.clientId.equals(clientId);
        byte[] expected = known ? tokenDigest : UNKNOWN_CLIENT_DIGEST;
        boolean match = MessageDigest.isEqual(expected, presented);
        return known && token != null && match;
    }

    /** Verifies a combined {@code <clientId>.<token>} credential as carried in headers and envelopes. */
    public boolean verifyCredential(String credential) {
        if (credential == null) return verify(null, null);
        int dot = credential.indexOf('.');
        if (dot <= 0) return verify(null, credential);
        return verify(credential.substring(0, dot), credential.substring(dot + 1));
    }

    static boolean isValidClientId(String clientId) {
        return isLowerHex(clientId, CLIENT_ID_BYTES * 2);
    }

    static boolean isValidToken(String token) {
        return isLowerHex(token, TOKEN_BYTES * 2);
    }

    private static boolean isLowerHex(String s, int length) {
        if (s == null || s.length() != length) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = digits[(bytes[i] >> 4) & 0x0f];
            out[i * 2 + 1] = digits[bytes[i] & 0x0f];
        }
        return new String(out);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A freshly generated paired-client credential. */
    public static final class Credential {
        public final String clientId;
        public final String token;

        Credential(String clientId, String token) {
            this.clientId = clientId;
            this.token = token;
        }

        /** Value placed after {@code Bearer } in the Authorization header and in the BLE envelope. */
        public String bearerValue() {
            return clientId + "." + token;
        }

        @Override
        public String toString() {
            return "Credential{clientId=" + clientId + ", token=<redacted>}";
        }
    }
}
