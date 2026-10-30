package com.cleanyourcar.navbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.net.URLDecoder;
import java.util.UUID;

/**
 * Shared protocol constants and payload extractor for BLE GATT and Hotspot Burst navigation bridge.
 */
public final class NavBridgeProtocol {
    public static final UUID SERVICE_UUID = UUID.fromString("0000c910-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_NAV_UUID = UUID.fromString("0000c911-0000-1000-8000-00805f9b34fb");
    public static final int PORT = 48920;

    public static final String TARGET_PKG = "com.cleanyourcar.launcher";
    public static final String TARGET_ACT = "com.cleanyourcar.launcher.NavDispatchActivity";
    public static final String ACTION_NAVIGATE = "com.cleanyourcar.launcher.action.NAVIGATE";

    private NavBridgeProtocol() {
    }

    public static String extractQueryFromHttpOrRaw(String requestOrDatagram) {
        if (requestOrDatagram == null) return "";
        String trimmed = requestOrDatagram.trim();
        if (trimmed.isEmpty()) return "";

        // Handle HTTP GET /nav?q=... HTTP/1.1
        if (trimmed.startsWith("GET ") || trimmed.startsWith("POST ")) {
            int firstLineEnd = trimmed.indexOf('\n');
            String firstLine = (firstLineEnd > 0) ? trimmed.substring(0, firstLineEnd).trim() : trimmed;
            int qIdx = firstLine.indexOf("?q=");
            if (qIdx < 0) qIdx = firstLine.indexOf("&q=");
            if (qIdx >= 0) {
                String rest = firstLine.substring(qIdx + 3);
                int spaceIdx = rest.indexOf(' ');
                if (spaceIdx > 0) rest = rest.substring(0, spaceIdx);
                int ampIdx = rest.indexOf('&');
                if (ampIdx > 0) rest = rest.substring(0, ampIdx);
                return urlDecode(rest);
            }
            // Check HTTP body after \r\n\r\n
            int bodySplit = trimmed.indexOf("\r\n\r\n");
            if (bodySplit >= 0 && bodySplit + 4 < trimmed.length()) {
                return urlDecode(trimmed.substring(bodySplit + 4).trim());
            }
            return "";
        }

        return urlDecode(trimmed);
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8").trim();
        } catch (Exception e) {
            return s.trim();
        }
    }

    public static void dispatchToLauncher(Context context, String queryPayload) {
        if (queryPayload == null || queryPayload.trim().isEmpty()) return;
        try {
            Intent intent = new Intent(ACTION_NAVIGATE);
            intent.setComponent(new ComponentName(TARGET_PKG, TARGET_ACT));
            intent.putExtra("q", queryPayload.trim());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }
}
