package com.cleanyourcar.navbridge;

/** Revalidates queued destination work against the bridge session that accepted it. */
final class BridgeDispatchGate {

    interface Poster {
        void post(Runnable runnable);
    }

    interface AuthorizationSupplier {
        BridgeAuthorization get();
    }

    interface Dispatcher {
        void dispatch(String destination);
    }

    private long generation;
    private boolean active;
    private BridgeAuthorization authorization = BridgeAuthorization.none();

    synchronized long activate(BridgeAuthorization nextAuthorization) {
        generation++;
        active = true;
        authorization = nextAuthorization == null
                ? BridgeAuthorization.none() : nextAuthorization;
        return generation;
    }

    synchronized void invalidate() {
        generation++;
        active = false;
        authorization = BridgeAuthorization.none();
    }

    void enqueue(long expectedGeneration, String credential, String destination, Poster poster,
            AuthorizationSupplier currentAuthorization, Dispatcher dispatcher) {
        poster.post(() -> {
            synchronized (BridgeDispatchGate.this) {
                if (!active || generation != expectedGeneration
                        || !authorization.verifyCredential(credential)) {
                    return;
                }
                BridgeAuthorization latest = currentAuthorization.get();
                if (latest == null || !latest.verifyCredential(credential)) return;
                dispatcher.dispatch(destination);
            }
        });
    }
}
