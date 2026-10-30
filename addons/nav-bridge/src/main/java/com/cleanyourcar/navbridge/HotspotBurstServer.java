package com.cleanyourcar.navbridge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Lightweight UDP + HTTP listener on port 48920 for iOS Shortcut "Chớp Hotspot" burst delivery.
 * Immediately replies HTTP 200 OK {"status":"ok"} so the iPhone Shortcut can turn off Hotspot.
 */
public final class HotspotBurstServer {
    private static final String HTTP_OK_RESPONSE =
            "HTTP/1.1 200 OK\r\n"
            + "Content-Type: application/json\r\n"
            + "Access-Control-Allow-Origin: *\r\n"
            + "Connection: close\r\n\r\n"
            + "{\"status\":\"ok\"}\n";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private DatagramSocket udpSocket;
    private ServerSocket tcpServer;

    public HotspotBurstServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        Thread udpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runUdpLoop();
            }
        }, "NavBridge-UDP");
        udpThread.setDaemon(true);
        udpThread.start();

        Thread tcpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runTcpLoop();
            }
        }, "NavBridge-HTTP");
        tcpThread.setDaemon(true);
        tcpThread.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (udpSocket != null) {
                udpSocket.close();
                udpSocket = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (tcpServer != null) {
                tcpServer.close();
                tcpServer = null;
            }
        } catch (Exception ignored) {
        }
    }

    private void runUdpLoop() {
        try {
            udpSocket = new DatagramSocket(null);
            udpSocket.setReuseAddress(true);
            udpSocket.bind(new InetSocketAddress(NavBridgeProtocol.PORT));
            byte[] buf = new byte[1024];
            while (running && udpSocket != null && !udpSocket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                udpSocket.receive(packet);
                String raw = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                final String query = NavBridgeProtocol.extractQueryFromHttpOrRaw(raw);
                if (!query.isEmpty()) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            NavBridgeProtocol.dispatchToLauncher(context, query);
                        }
                    });
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void runTcpLoop() {
        try {
            tcpServer = new ServerSocket();
            tcpServer.setReuseAddress(true);
            tcpServer.bind(new InetSocketAddress(NavBridgeProtocol.PORT));
            while (running && tcpServer != null && !tcpServer.isClosed()) {
                Socket client = tcpServer.accept();
                handleHttpClient(client);
            }
        } catch (Throwable ignored) {
        }
    }

    private void handleHttpClient(Socket client) {
        try {
            client.setSoTimeout(2500);
            InputStream in = client.getInputStream();
            byte[] buf = new byte[2048];
            int read = in.read(buf);
            if (read > 0) {
                String req = new String(buf, 0, read, StandardCharsets.UTF_8);
                final String query = NavBridgeProtocol.extractQueryFromHttpOrRaw(req);
                OutputStream out = client.getOutputStream();
                out.write(HTTP_OK_RESPONSE.getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (!query.isEmpty()) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            NavBridgeProtocol.dispatchToLauncher(context, query);
                        }
                    });
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }
}
