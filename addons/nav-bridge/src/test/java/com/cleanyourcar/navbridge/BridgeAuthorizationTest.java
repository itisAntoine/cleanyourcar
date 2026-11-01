package com.cleanyourcar.navbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BridgeAuthorizationTest {

    static final class FakeDispatcher implements BridgeRequestHandler.DestinationDispatcher {
        final List<String> acceptedDestinations = Collections.synchronizedList(new ArrayList<String>());
        final List<String> acceptedCredentials = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void dispatch(String destination, String credential) {
            acceptedDestinations.add(destination);
            acceptedCredentials.add(credential);
        }
    }

    private static BridgeAuthorization.Credential newCredential() {
        return BridgeAuthorization.newCredential(new SecureRandom());
    }

    private static byte[] httpGet(String target, String authorization) {
        StringBuilder sb = new StringBuilder();
        sb.append("GET ").append(target).append(" HTTP/1.1\r\n");
        sb.append("Host: 192.0.2.10:48920\r\n");
        if (authorization != null) {
            sb.append("Authorization: ").append(authorization).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] httpPost(String body, String authorization) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "POST /nav HTTP/1.1\r\n"
                + "Host: 192.0.2.10:48920\r\n"
                + "Authorization: " + authorization + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[headBytes.length + bodyBytes.length];
        System.arraycopy(headBytes, 0, all, 0, headBytes.length);
        System.arraycopy(bodyBytes, 0, all, headBytes.length, bodyBytes.length);
        return all;
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

    @Test
    public void missingOrWrongTokenNeverDispatches() {
        BridgeAuthorization.Credential credential = newCredential();
        assertEquals(64, credential.token.length());
        assertTrue(credential.token.matches("[0-9a-f]{64}"));
        assertNotEquals(credential.token, newCredential().token);

        BridgeAuthorization authorization = BridgeAuthorization.forClient(credential.clientId, credential.token);
        assertFalse(authorization.verify("unknown-client", "wrong-token"));
        assertFalse(authorization.verify(credential.clientId, "wrong-token"));
        assertFalse(authorization.verify(credential.clientId, null));
        assertFalse(authorization.verify(null, credential.token));
        assertFalse(authorization.verify("unknown-client", credential.token));
        assertFalse(BridgeAuthorization.none().verify(credential.clientId, credential.token));
        assertTrue(authorization.verify(credential.clientId, credential.token));

        FakeDispatcher fakeDispatcher = new FakeDispatcher();
        BridgeRequestHandler handler = new BridgeRequestHandler(authorization, fakeDispatcher);
        String wrongToken = BridgeAuthorization.newCredential(new SecureRandom()).token;

        assertEquals(BridgeRequestHandler.Outcome.UNAUTHORIZED,
                handler.handleHttp(httpGet("/nav?q=10.7769%2C106.7009", null)));
        assertEquals(BridgeRequestHandler.Outcome.UNAUTHORIZED,
                handler.handleHttp(httpGet("/nav?q=10.7769%2C106.7009",
                        "Bearer " + credential.clientId + "." + wrongToken)));
        assertEquals(BridgeRequestHandler.Outcome.UNAUTHORIZED,
                handler.handleHttp(httpGet("/nav?q=10.7769%2C106.7009", "Basic Zm9vOmJhcg==")));
        // Credentials must never travel in the URL, even if correct.
        assertEquals(BridgeRequestHandler.Outcome.MALFORMED,
                handler.handleHttp(httpGet("/nav?q=QMVX%2B2P&token=" + credential.token, null)));
        assertEquals(BridgeRequestHandler.Outcome.MALFORMED,
                handler.handleHttp(httpGet("/nav?q=QMVX%2B2P&token=" + credential.token,
                        "Bearer " + credential.bearerValue())));
        // Legacy raw BLE strings and wrong envelope credentials are rejected.
        assertEquals(BridgeRequestHandler.Outcome.UNAUTHORIZED,
                handler.handleBleWrite("10.7769,106.7009".getBytes(StandardCharsets.UTF_8)));
        assertEquals(BridgeRequestHandler.Outcome.UNAUTHORIZED,
                handler.handleBleWrite(("CYC-NAV/1\n" + credential.clientId + "." + wrongToken
                        + "\nQMVX+2P").getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, fakeDispatcher.acceptedDestinations.size());

        // Revoked credentials (no paired client) never authorize anything.
        BridgeRequestHandler revoked = new BridgeRequestHandler(BridgeAuthorization.none(), fakeDispatcher);
        assertEquals(BridgeRequestHandler.Outcome.UNAUTHORIZED,
                revoked.handleHttp(httpGet("/nav?q=QMVX%2B2P", "Bearer " + credential.bearerValue())));
        assertEquals(0, fakeDispatcher.acceptedDestinations.size());

        // Exactly one dispatch per accepted request.
        assertEquals(BridgeRequestHandler.Outcome.ACCEPTED,
                handler.handleHttp(httpGet("/nav?q=10.7769%2C106.7009", "Bearer " + credential.bearerValue())));
        assertEquals(1, fakeDispatcher.acceptedDestinations.size());
        assertEquals("10.7769,106.7009", fakeDispatcher.acceptedDestinations.get(0));
        assertEquals(credential.bearerValue(), fakeDispatcher.acceptedCredentials.get(0));
        assertEquals(BridgeRequestHandler.Outcome.ACCEPTED,
                handler.handleBleWrite(("CYC-NAV/1\n" + credential.bearerValue() + "\nQMVX+2P")
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, fakeDispatcher.acceptedDestinations.size());
        assertEquals("QMVX+2P", fakeDispatcher.acceptedDestinations.get(1));
        assertEquals(BridgeRequestHandler.Outcome.ACCEPTED,
                handler.handleHttp(httpPost("https://maps.app.goo.gl/abc", "Bearer " + credential.bearerValue())));
        assertEquals(3, fakeDispatcher.acceptedDestinations.size());
        assertEquals("https://maps.app.goo.gl/abc", fakeDispatcher.acceptedDestinations.get(2));
    }

    @Test
    public void oversizeOrMalformedRequestRejected() throws Exception {
        assertEquals(4096, NavBridgeProtocol.MAX_REQUEST_BYTES);
        assertEquals(1024, NavBridgeProtocol.MAX_PAYLOAD_BYTES);
        assertEquals(2000, NavBridgeProtocol.SOCKET_READ_TIMEOUT_MS);

        BridgeAuthorization.Credential credential = newCredential();
        String bearer = "Bearer " + credential.bearerValue();
        FakeDispatcher fakeDispatcher = new FakeDispatcher();
        BridgeRequestHandler handler = new BridgeRequestHandler(
                BridgeAuthorization.forClient(credential.clientId, credential.token), fakeDispatcher);

        assertEquals(BridgeRequestHandler.Outcome.TOO_LARGE,
                handler.handleHttp(httpGet("/nav?q=" + repeat('A', 5000), bearer)));
        assertEquals(BridgeRequestHandler.Outcome.TOO_LARGE,
                handler.handleHttp(httpGet("/nav?q=" + repeat('A', 1025), bearer)));
        assertEquals(BridgeRequestHandler.Outcome.TOO_LARGE,
                handler.handleHttp(httpPost(repeat('B', 1025), bearer)));
        assertEquals(BridgeRequestHandler.Outcome.TOO_LARGE,
                handler.handleBleWrite(("CYC-NAV/1\n" + credential.bearerValue() + "\n" + repeat('C', 1025))
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals(BridgeRequestHandler.Outcome.MALFORMED,
                handler.handleHttp("GARBAGE\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(BridgeRequestHandler.Outcome.MALFORMED,
                handler.handleHttp(("GET /nav?q=QMVX%2B2P HTTP/1.1\r\nAuthorization: " + credential.bearerValue())
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals(BridgeRequestHandler.Outcome.MALFORMED,
                handler.handleHttp(httpGet("/nav?q=A&q=B", bearer)));
        assertEquals(BridgeRequestHandler.Outcome.MALFORMED,
                handler.handleHttp(httpGet("/nav", bearer)));
        assertEquals(BridgeRequestHandler.Outcome.MALFORMED,
                handler.handleHttp(httpGet("/nav?q=%0A%0D", bearer)));
        assertEquals(BridgeRequestHandler.Outcome.MALFORMED,
                handler.handleHttp(httpGet("/nav?q=line%0Abreak", bearer)));
        assertEquals(BridgeRequestHandler.Outcome.NOT_FOUND,
                handler.handleHttp(httpGet("/admin?q=QMVX%2B2P", bearer)));
        assertEquals(BridgeRequestHandler.Outcome.METHOD_NOT_ALLOWED,
                handler.handleHttp("DELETE /nav?q=x HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(BridgeRequestHandler.Outcome.MALFORMED,
                handler.handleBleWrite(("CYC-NAV/2\n" + credential.bearerValue() + "\nQMVX+2P")
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals(BridgeRequestHandler.Outcome.MALFORMED, handler.handleHttp(null));
        assertEquals(BridgeRequestHandler.Outcome.MALFORMED, handler.handleBleWrite(new byte[0]));
        assertEquals(0, fakeDispatcher.acceptedDestinations.size());

        // The bounded reader never buffers more than MAX_REQUEST_BYTES + 1 bytes.
        byte[] huge = httpGet("/nav?q=" + repeat('Z', 20000), bearer);
        byte[] read = BridgeRequestHandler.readHttpRequest(new ByteArrayInputStream(huge), Long.MAX_VALUE);
        assertTrue(read.length <= NavBridgeProtocol.MAX_REQUEST_BYTES + 1);
        assertEquals(BridgeRequestHandler.Outcome.TOO_LARGE, handler.handleHttp(read));

        byte[] post = httpPost("QMVX+2P", bearer);
        byte[] withTrailing = new byte[post.length + 10];
        System.arraycopy(post, 0, withTrailing, 0, post.length);
        byte[] readPost = BridgeRequestHandler.readHttpRequest(new ByteArrayInputStream(withTrailing), Long.MAX_VALUE);
        assertEquals(post.length, readPost.length);
        assertEquals(BridgeRequestHandler.Outcome.ACCEPTED, handler.handleHttp(readPost));
        assertEquals(1, fakeDispatcher.acceptedDestinations.size());
    }

    @Test
    public void legacyUdpDisabled() throws Exception {
        BridgeAuthorization.Credential credential = newCredential();
        FakeDispatcher fakeDispatcher = new FakeDispatcher();
        BridgeRequestHandler handler = new BridgeRequestHandler(
                BridgeAuthorization.forClient(credential.clientId, credential.token), fakeDispatcher);
        HotspotBurstServer server = new HotspotBurstServer(handler, 0, InetAddress.getLoopbackAddress());
        server.start();
        try {
            int port = server.getLocalPort();
            assertTrue(port > 0);
            assertTrue(server.isRunning());

            // Nothing in the bridge holds the UDP port: an unrelated socket can bind it.
            DatagramSocket probe = new DatagramSocket(null);
            try {
                probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            } finally {
                probe.close();
            }

            DatagramSocket sender = new DatagramSocket();
            try {
                byte[] legacy = "10.7769,106.7009".getBytes(StandardCharsets.UTF_8);
                sender.send(new DatagramPacket(legacy, legacy.length, InetAddress.getLoopbackAddress(), port));
            } finally {
                sender.close();
            }

            // A legacy raw (non-HTTP) TCP write is rejected as well.
            assertTrue(exchange(port, "10.7769,106.7009\n".getBytes(StandardCharsets.UTF_8)).startsWith("HTTP/1.1 4"));
            assertTrue(exchange(port, httpGet("/nav?q=QMVX%2B2P", null)).startsWith("HTTP/1.1 401"));
            Thread.sleep(200);
            assertEquals(0, fakeDispatcher.acceptedDestinations.size());

            assertTrue(exchange(port, httpGet("/nav?q=QMVX%2B2P", "Bearer " + credential.bearerValue()))
                    .startsWith("HTTP/1.1 200"));
            assertEquals(1, fakeDispatcher.acceptedDestinations.size());
            assertEquals("QMVX+2P", fakeDispatcher.acceptedDestinations.get(0));
        } finally {
            server.stop();
        }
        assertFalse(server.isRunning());
    }

    private static String exchange(int port, byte[] request) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();
            socket.shutdownOutput();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[1024];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString();
        } finally {
            socket.close();
        }
    }
}
