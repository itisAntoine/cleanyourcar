package com.cleanyourcar.navbridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Authenticated HTTP listener for the optional iOS Shortcut LAN flow. Only TCP/HTTP is served;
 * the legacy raw unauthenticated UDP receiver was removed. Each connection gets a 2s read timeout,
 * a 4KiB request bound and at most one dispatch through {@link BridgeRequestHandler}.
 */
public final class HotspotBurstServer implements BridgeSession.Transport {
    private final BridgeRequestHandler handler;
    private final int port;
    private final InetAddress bindAddress;
    private volatile boolean running;
    /** Incremented by {@link #stop()}; pending asynchronous starts from older generations abort. */
    private long startGeneration;
    private ServerSocket tcpServer;

    /**
     * @param bindAddress address to bind, or null for all local interfaces (hotspot address varies)
     */
    public HotspotBurstServer(BridgeRequestHandler handler, int port, InetAddress bindAddress) {
        this(handler, port, bindAddress, new Executor() {
            @Override
            public void execute(Runnable command) {
                Thread starter = new Thread(command, "NavBridge-HTTP-start");
                starter.setDaemon(true);
                starter.start();
            }
        });
    }

    /** Test seam: {@code starterExecutor} runs the asynchronous bind of {@link #start(BridgeSession.TransportCallback)}. */
    HotspotBurstServer(BridgeRequestHandler handler, int port, InetAddress bindAddress, Executor starterExecutor) {
        this.handler = handler;
        this.port = port;
        this.bindAddress = bindAddress;
        this.starterExecutor = starterExecutor;
    }

    private final Executor starterExecutor;

    private volatile BridgeSession.TransportCallback callback;

    /** Binds synchronously; success is reported only after the socket is actually listening. */
    public void start() throws IOException {
        long generation;
        synchronized (this) {
            generation = startGeneration;
        }
        startListening(null, generation);
    }

    @Override
    public void start(final BridgeSession.TransportCallback callback) {
        final long generation;
        synchronized (this) {
            generation = startGeneration;
        }
        starterExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (startListening(callback, generation)) {
                        callback.onStarted("port " + getLocalPort());
                    }
                } catch (IOException | RuntimeException e) {
                    if (isCurrent(generation)) {
                        callback.onFailed("LAN listener could not bind port " + port + ": "
                                + e.getClass().getSimpleName());
                    }
                }
            }
        });
    }

    private synchronized boolean isCurrent(long generation) {
        return generation == startGeneration;
    }

    /**
     * Binds under the same lock as {@link #stop()}. A start whose generation was superseded by a
     * stop never binds, so a late asynchronous start cannot leave an orphaned listener running.
     *
     * @return true only if this call bound a new listener
     */
    private synchronized boolean startListening(BridgeSession.TransportCallback cb, long generation)
            throws IOException {
        if (generation != startGeneration || running) return false;
        final ServerSocket server = new ServerSocket();
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(bindAddress, port));
        } catch (IOException e) {
            closeQuietly(server);
            throw e;
        }
        tcpServer = server;
        callback = cb;
        running = true;
        Thread tcpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runAcceptLoop(server);
            }
        }, "NavBridge-HTTP");
        tcpThread.setDaemon(true);
        tcpThread.start();
        return true;
    }

    @Override
    public synchronized void stop() {
        startGeneration++;
        running = false;
        callback = null;
        if (tcpServer != null) {
            closeQuietly(tcpServer);
            tcpServer = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized int getLocalPort() {
        return tcpServer == null ? -1 : tcpServer.getLocalPort();
    }

    /**
     * Single-threaded by design: connections are served one at a time. This is acceptable because
     * LAN reception is meant for a trusted network and each connection is bounded by the 2s
     * per-connection deadline and the 4KiB request limit.
     */
    private void runAcceptLoop(ServerSocket server) {
        try {
            while (running && !server.isClosed()) {
                Socket client = server.accept();
                handleClient(client);
            }
        } catch (IOException | RuntimeException e) {
            if (running) {
                BridgeSession.TransportCallback cb = callback;
                synchronized (this) {
                    running = false;
                    if (tcpServer == server) tcpServer = null;
                }
                closeQuietly(server);
                if (cb != null) cb.onFailed("LAN listener stopped: " + e.getClass().getSimpleName());
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(NavBridgeProtocol.SOCKET_READ_TIMEOUT_MS);
            BridgeRequestHandler.Outcome outcome;
            try {
                InputStream in = client.getInputStream();
                byte[] request = BridgeRequestHandler.readHttpRequest(in,
                        TimeUnit.MILLISECONDS.toNanos(NavBridgeProtocol.SOCKET_READ_TIMEOUT_MS),
                        client::setSoTimeout);
                outcome = handler.handleHttp(request);
            } catch (SocketTimeoutException e) {
                outcome = BridgeRequestHandler.Outcome.TIMEOUT;
            }
            OutputStream out = client.getOutputStream();
            out.write(BridgeRequestHandler.httpResponse(outcome).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException | RuntimeException ignored) {
        } finally {
            closeQuietly(client);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
