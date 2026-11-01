package com.cleanyourcar.navbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class HotspotBurstServerLifecycleTest {

    static final class DeferredExecutor implements Executor {
        final List<Runnable> pending = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            pending.add(command);
        }

        void runAll() {
            for (Runnable r : pending) r.run();
            pending.clear();
        }
    }

    static final class RecordingCallback implements BridgeSession.TransportCallback {
        final List<String> events = new ArrayList<>();
        final CountDownLatch started = new CountDownLatch(1);

        @Override
        public synchronized void onStarted(String detail) {
            events.add("started");
            started.countDown();
        }

        @Override
        public synchronized void onFailed(String reason) {
            events.add("failed");
        }
    }

    private static BridgeRequestHandler handler() {
        BridgeAuthorization.Credential credential = BridgeAuthorization.newCredential(new SecureRandom());
        return new BridgeRequestHandler(
                BridgeAuthorization.forClient(credential.clientId, credential.token),
                (destination, presentedCredential) -> { });
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return probe.getLocalPort();
        }
    }

    private static void assertPortFree(int port) throws IOException {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        }
    }

    @Test
    public void stopBeforeBindLeavesNoOrphanListener() throws Exception {
        int port = freePort();
        DeferredExecutor deferred = new DeferredExecutor();
        HotspotBurstServer server = new HotspotBurstServer(
                handler(), port, InetAddress.getLoopbackAddress(), deferred);
        RecordingCallback callback = new RecordingCallback();

        server.start(callback);
        server.stop();
        deferred.runAll();

        assertFalse(server.isRunning());
        assertEquals(-1, server.getLocalPort());
        assertTrue("no callback after stop: " + callback.events, callback.events.isEmpty());
        assertPortFree(port);
    }

    @Test
    public void restartAfterAbortedStartStillWorks() throws Exception {
        DeferredExecutor deferred = new DeferredExecutor();
        HotspotBurstServer server = new HotspotBurstServer(
                handler(), 0, InetAddress.getLoopbackAddress(), deferred);
        RecordingCallback stale = new RecordingCallback();
        server.start(stale);
        server.stop();

        RecordingCallback fresh = new RecordingCallback();
        server.start(fresh);
        deferred.runAll();
        try {
            assertTrue(stale.events.isEmpty());
            assertEquals(1, fresh.events.size());
            assertEquals("started", fresh.events.get(0));
            assertTrue(server.isRunning());
        } finally {
            server.stop();
        }
        assertFalse(server.isRunning());
    }

    @Test
    public void readDeadlineBoundsEachSocketRead() throws Exception {
        byte[] partial = "GET /nav?q=x HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII);
        final List<Integer> timeouts = new ArrayList<>();
        InputStream slow = new ByteArrayInputStream(partial) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                int n = super.read(b, off, Math.min(len, 1));
                if (n < 0) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ignored) {
                    }
                    return 0;
                }
                return n;
            }
        };
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(50);
        try {
            BridgeRequestHandler.readHttpRequest(slow, timeoutNanos, timeouts::add);
            throw new AssertionError("expected deadline");
        } catch (SocketTimeoutException expected) {
            // expected
        }
        assertFalse(timeouts.isEmpty());
        for (int ms : timeouts) {
            assertTrue("timeout " + ms + " must be within the remaining deadline", ms >= 1 && ms <= 50);
        }
        assertTrue("timeouts should shrink", timeouts.get(timeouts.size() - 1) < timeouts.get(0));
    }
}
