package com.cleanyourcar.navbridge;

/**
 * Tracks the real state of each transport for one bridge run. Policy approval moves a transport to
 * STARTING; only the transport's own success callback makes it ACTIVE, and a failure is sticky for
 * the run so a late success can never mask it.
 */
public final class BridgeSession {

    public enum TransportState { OFF, UNAVAILABLE, STARTING, ACTIVE, FAILED }

    public interface TransportCallback {
        void onStarted(String detail);

        void onFailed(String reason);
    }

    /** A receiver that starts asynchronously and reports the real outcome through the callback. */
    public interface Transport {
        void start(TransportCallback callback);

        void stop();
    }

    public interface Listener {
        void onSessionChanged(BridgeSession session);
    }

    private final Transport ble;
    private final Transport lan;
    private Listener listener;
    private int generation;
    private TransportState bleState = TransportState.OFF;
    private TransportState lanState = TransportState.OFF;
    private String bleDetail = "";
    private String lanDetail = "";
    private BridgeLifecyclePolicy.StartDecision lastDecision;

    public BridgeSession(Transport ble, Transport lan) {
        this.ble = ble;
        this.lan = lan;
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    public BridgeLifecyclePolicy.StartDecision start(int sdk, BridgeLifecyclePolicy.PermissionState permissions,
                                                     BridgeLifecyclePolicy.FeatureState features,
                                                     boolean userInitiated) {
        BridgeLifecyclePolicy.StartDecision decision =
                BridgeLifecyclePolicy.canStart(sdk, permissions, features, userInitiated);
        final int run;
        synchronized (this) {
            stopTransportsLocked();
            run = ++generation;
            lastDecision = decision;
            bleState = decision.startBle() ? TransportState.STARTING
                    : (features.bleRequested ? TransportState.UNAVAILABLE : TransportState.OFF);
            lanState = decision.startLan() ? TransportState.STARTING
                    : (features.lanRequested ? TransportState.UNAVAILABLE : TransportState.OFF);
            bleDetail = "";
            lanDetail = "";
        }
        notifyListener();
        if (decision.startBle()) ble.start(callback(run, true));
        if (decision.startLan()) lan.start(callback(run, false));
        return decision;
    }

    public void stop() {
        synchronized (this) {
            stopTransportsLocked();
            generation++;
            bleState = TransportState.OFF;
            lanState = TransportState.OFF;
            bleDetail = "";
            lanDetail = "";
        }
        notifyListener();
    }

    private void stopTransportsLocked() {
        if (bleState == TransportState.STARTING || bleState == TransportState.ACTIVE) ble.stop();
        if (lanState == TransportState.STARTING || lanState == TransportState.ACTIVE) lan.stop();
    }

    private TransportCallback callback(final int run, final boolean isBle) {
        return new TransportCallback() {
            @Override
            public void onStarted(String detail) {
                update(run, isBle, TransportState.ACTIVE, detail);
            }

            @Override
            public void onFailed(String reason) {
                update(run, isBle, TransportState.FAILED, reason);
            }
        };
    }

    private void update(int run, boolean isBle, TransportState next, String detail) {
        synchronized (this) {
            if (run != generation) return;
            TransportState current = isBle ? bleState : lanState;
            if (current == TransportState.FAILED || current == TransportState.OFF
                    || current == TransportState.UNAVAILABLE) {
                return;
            }
            if (isBle) {
                bleState = next;
                bleDetail = detail == null ? "" : detail;
            } else {
                lanState = next;
                lanDetail = detail == null ? "" : detail;
            }
        }
        if (next == TransportState.FAILED) {
            if (isBle) ble.stop();
            else lan.stop();
        }
        notifyListener();
    }

    private void notifyListener() {
        Listener l;
        synchronized (this) {
            l = listener;
        }
        if (l != null) l.onSessionChanged(this);
    }

    public synchronized TransportState bleState() {
        return bleState;
    }

    public synchronized TransportState lanState() {
        return lanState;
    }

    public synchronized String bleDetail() {
        return bleDetail;
    }

    public synchronized String lanDetail() {
        return lanDetail;
    }

    public synchronized BridgeLifecyclePolicy.StartDecision lastDecision() {
        return lastDecision;
    }

    /** True only when at least one transport confirmed it is actually receiving. */
    public synchronized boolean isReceiving() {
        return bleState == TransportState.ACTIVE || lanState == TransportState.ACTIVE;
    }

    /** True when nothing is starting or active, so the host service should stop. */
    public synchronized boolean allRequestedFailed() {
        return !isReceiving() && bleState != TransportState.STARTING && lanState != TransportState.STARTING;
    }
}
