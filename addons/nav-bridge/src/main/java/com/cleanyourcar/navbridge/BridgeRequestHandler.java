package com.cleanyourcar.navbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.Locale;

/**
 * Pure-Java gate between listeners and launcher dispatch. Every request is size-bounded, parsed
 * strictly and authorized before at most one destination is dispatched.
 */
public final class BridgeRequestHandler {

    public interface DestinationDispatcher {
        void dispatch(String destination, String credential);
    }

    public enum Outcome {
        ACCEPTED(200, "OK", "ok"),
        MALFORMED(400, "Bad Request", "malformed"),
        UNAUTHORIZED(401, "Unauthorized", "unauthorized"),
        NOT_FOUND(404, "Not Found", "not-found"),
        METHOD_NOT_ALLOWED(405, "Method Not Allowed", "method-not-allowed"),
        TIMEOUT(408, "Request Timeout", "timeout"),
        TOO_LARGE(413, "Payload Too Large", "too-large");

        public final int httpStatus;
        final String httpReason;
        final String code;

        Outcome(int httpStatus, String httpReason, String code) {
            this.httpStatus = httpStatus;
            this.httpReason = httpReason;
            this.code = code;
        }
    }

    private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};

    private final BridgeAuthorization authorization;
    private final DestinationDispatcher dispatcher;

    public BridgeRequestHandler(BridgeAuthorization authorization, DestinationDispatcher dispatcher) {
        this.authorization = authorization == null ? BridgeAuthorization.none() : authorization;
        this.dispatcher = dispatcher;
    }

    public Outcome handleHttp(byte[] request) {
        if (request == null || request.length == 0) return Outcome.MALFORMED;
        if (request.length > NavBridgeProtocol.MAX_REQUEST_BYTES) return Outcome.TOO_LARGE;
        int headerEnd = indexOf(request, HEADER_END, 0);
        if (headerEnd < 0) return Outcome.MALFORMED;
        String head = new String(request, 0, headerEnd, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n", -1);

        String[] requestLine = lines[0].split(" ", -1);
        if (requestLine.length != 3 || !requestLine[2].startsWith("HTTP/1.")) return Outcome.MALFORMED;
        String method = requestLine[0];
        boolean isPost = "POST".equals(method);
        if (!isPost && !"GET".equals(method)) return Outcome.METHOD_NOT_ALLOWED;

        String target = requestLine[1];
        int queryStart = target.indexOf('?');
        String path = queryStart >= 0 ? target.substring(0, queryStart) : target;
        String query = queryStart >= 0 ? target.substring(queryStart + 1) : "";
        if (!NavBridgeProtocol.HTTP_PATH.equals(path)) return Outcome.NOT_FOUND;

        String authHeader = null;
        String contentLength = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) return Outcome.MALFORMED;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if ("authorization".equals(name)) {
                if (authHeader != null) return Outcome.MALFORMED;
                authHeader = value;
            } else if ("content-length".equals(name)) {
                if (contentLength != null) return Outcome.MALFORMED;
                contentLength = value;
            }
        }

        int bodyStart = headerEnd + HEADER_END.length;
        int bodyLength = request.length - bodyStart;
        if (contentLength != null) {
            int declared = parseContentLength(contentLength);
            if (declared < 0 || declared != bodyLength) return Outcome.MALFORMED;
        } else if (bodyLength != 0) {
            return Outcome.MALFORMED;
        }

        String queryPayload;
        try {
            queryPayload = singleQParameter(query);
        } catch (IllegalArgumentException e) {
            return Outcome.MALFORMED;
        }

        if (!isAuthorizedBearer(authHeader)) return Outcome.UNAUTHORIZED;
        String credential = authHeader.substring(7).trim();

        String payload = queryPayload;
        if (payload == null && isPost && bodyLength > 0) {
            String body = new String(request, bodyStart, bodyLength, StandardCharsets.UTF_8);
            if (body.startsWith("q=")) {
                try {
                    payload = singleQParameter(body.trim());
                } catch (IllegalArgumentException e) {
                    return Outcome.MALFORMED;
                }
            } else {
                payload = body;
            }
        }
        return accept(payload, credential);
    }

    /** Handles one complete BLE characteristic value (single or reassembled long write). */
    public Outcome handleBleWrite(byte[] value) {
        if (value == null || value.length == 0) return Outcome.MALFORMED;
        if (value.length > NavBridgeProtocol.MAX_REQUEST_BYTES) return Outcome.TOO_LARGE;
        String text = new String(value, StandardCharsets.UTF_8);
        int firstBreak = text.indexOf('\n');
        if (firstBreak < 0) return Outcome.UNAUTHORIZED;
        String version = stripCr(text.substring(0, firstBreak));
        if (!version.startsWith("CYC-NAV/")) return Outcome.UNAUTHORIZED;
        if (!NavBridgeProtocol.BLE_ENVELOPE_V1.equals(version)) return Outcome.MALFORMED;
        int secondBreak = text.indexOf('\n', firstBreak + 1);
        if (secondBreak < 0) return Outcome.MALFORMED;
        String credential = stripCr(text.substring(firstBreak + 1, secondBreak));
        if (!authorization.verifyCredential(credential)) return Outcome.UNAUTHORIZED;
        return accept(text.substring(secondBreak + 1), credential);
    }

    private Outcome accept(String rawPayload, String credential) {
        if (rawPayload == null) return Outcome.MALFORMED;
        String payload = rawPayload.trim();
        if (payload.isEmpty()) return Outcome.MALFORMED;
        if (payload.getBytes(StandardCharsets.UTF_8).length > NavBridgeProtocol.MAX_PAYLOAD_BYTES) {
            return Outcome.TOO_LARGE;
        }
        for (int i = 0; i < payload.length(); i++) {
            if (Character.isISOControl(payload.charAt(i))) return Outcome.MALFORMED;
        }
        dispatcher.dispatch(payload, credential);
        return Outcome.ACCEPTED;
    }

    private boolean isAuthorizedBearer(String header) {
        if (header == null || header.length() < 7
                || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            authorization.verify(null, null);
            return false;
        }
        return authorization.verifyCredential(header.substring(7).trim());
    }

    /** Returns the decoded {@code q} value, null when absent; rejects any other parameter. */
    private static String singleQParameter(String query) {
        if (query.isEmpty()) return null;
        String value = null;
        for (String pair : query.split("&", -1)) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            if (!"q".equals(key) || value != null) throw new IllegalArgumentException("unexpected parameter");
            value = strictDecode(eq >= 0 ? pair.substring(eq + 1) : "");
        }
        return value;
    }

    private static String strictDecode(String s) {
        try {
            // '+' stays literal so Plus Codes such as QMVX+2P survive unencoded.
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static int parseContentLength(String value) {
        if (value.isEmpty() || value.length() > 5) return -1;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return -1;
        }
        return Integer.parseInt(value);
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    public static String httpResponse(Outcome outcome) {
        String body = outcome == Outcome.ACCEPTED
                ? "{\"status\":\"ok\"}\n"
                : "{\"status\":\"error\",\"reason\":\"" + outcome.code + "\"}\n";
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(outcome.httpStatus).append(' ').append(outcome.httpReason).append("\r\n");
        sb.append("Content-Type: application/json\r\n");
        if (outcome == Outcome.UNAUTHORIZED) sb.append("WWW-Authenticate: Bearer\r\n");
        sb.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        sb.append(body);
        return sb.toString();
    }

    /**
     * Reads one HTTP request without buffering more than {@code MAX_REQUEST_BYTES + 1} bytes.
     * Returns the request trimmed to its declared length; an oversize result is longer than the limit.
     *
     * @throws SocketTimeoutException when {@code timeoutNanos} elapses before the request completes
     */
    public static byte[] readHttpRequest(InputStream in, long timeoutNanos) throws IOException {
        return readHttpRequest(in, timeoutNanos, null);
    }

    /** Applies the remaining deadline (in ms) to the underlying socket before every read. */
    public interface ReadTimeoutSetter {
        void setReadTimeoutMillis(int millis) throws IOException;
    }

    public static byte[] readHttpRequest(InputStream in, long timeoutNanos, ReadTimeoutSetter timeoutSetter)
            throws IOException {
        long startNanos = System.nanoTime();
        int limit = NavBridgeProtocol.MAX_REQUEST_BYTES + 1;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[512];
        while (true) {
            long remainingNanos = timeoutNanos - (System.nanoTime() - startNanos);
            if (remainingNanos <= 0) throw new SocketTimeoutException("request deadline");
            if (timeoutSetter != null) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                timeoutSetter.setReadTimeoutMillis((int) Math.max(1L, Math.min(Integer.MAX_VALUE, remainingMillis)));
            }
            int n = in.read(chunk, 0, Math.min(chunk.length, limit - buffer.size()));
            if (n < 0) break;
            buffer.write(chunk, 0, n);
            byte[] data = buffer.toByteArray();
            if (data.length >= limit) return data;
            int headerEnd = indexOf(data, HEADER_END, 0);
            if (headerEnd >= 0) {
                int expected = headerEnd + HEADER_END.length + declaredLength(data, headerEnd);
                if (expected > NavBridgeProtocol.MAX_REQUEST_BYTES) {
                    byte[] oversize = new byte[limit];
                    System.arraycopy(data, 0, oversize, 0, data.length);
                    return oversize;
                }
                if (data.length >= expected) {
                    byte[] exact = new byte[expected];
                    System.arraycopy(data, 0, exact, 0, expected);
                    return exact;
                }
            }
            if (System.nanoTime() - startNanos > timeoutNanos) throw new SocketTimeoutException("request deadline");
        }
        return buffer.toByteArray();
    }

    private static int declaredLength(byte[] data, int headerEnd) {
        String head = new String(data, 0, headerEnd, StandardCharsets.ISO_8859_1);
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                int parsed = parseContentLength(line.substring(colon + 1).trim());
                return Math.max(parsed, 0);
            }
        }
        return 0;
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
