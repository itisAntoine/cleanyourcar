package com.cleanyourcar.navbridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class BridgeDispatchGateTest {

    @Test
    public void queuedRequestIsCancelledWhenSessionStopsAndAuthorizationIsRevoked() {
        BridgeAuthorization.Credential credential =
                BridgeAuthorization.newCredential(new SecureRandom());
        BridgeAuthorization authorization = BridgeAuthorization.forClient(
                credential.clientId, credential.token);
        AtomicReference<BridgeAuthorization> currentAuthorization =
                new AtomicReference<>(authorization);
        BridgeDispatchGate gate = new BridgeDispatchGate();
        long session = gate.activate(authorization);
        List<Runnable> mainQueue = new ArrayList<>();
        List<String> destinations = new ArrayList<>();

        gate.enqueue(session, credential.bearerValue(), "QMVX+2P", mainQueue::add,
                currentAuthorization::get, destinations::add);
        assertEquals(1, mainQueue.size());

        gate.invalidate();
        currentAuthorization.set(BridgeAuthorization.none());
        mainQueue.remove(0).run();

        assertEquals(0, destinations.size());
    }

    @Test
    public void activeAuthorizedRequestStillDispatchesOnMainQueueDrain() {
        BridgeAuthorization.Credential credential =
                BridgeAuthorization.newCredential(new SecureRandom());
        BridgeAuthorization authorization = BridgeAuthorization.forClient(
                credential.clientId, credential.token);
        BridgeDispatchGate gate = new BridgeDispatchGate();
        long session = gate.activate(authorization);
        List<Runnable> mainQueue = new ArrayList<>();
        List<String> destinations = new ArrayList<>();

        gate.enqueue(session, credential.bearerValue(), "QMVX+2P", mainQueue::add,
                () -> authorization, destinations::add);
        mainQueue.remove(0).run();

        assertEquals(java.util.Collections.singletonList("QMVX+2P"), destinations);
    }

    @Test
    public void revokedCredentialIsRecheckedAtDispatchTime() {
        BridgeAuthorization.Credential credential =
                BridgeAuthorization.newCredential(new SecureRandom());
        BridgeAuthorization authorization = BridgeAuthorization.forClient(
                credential.clientId, credential.token);
        AtomicReference<BridgeAuthorization> currentAuthorization =
                new AtomicReference<>(authorization);
        BridgeDispatchGate gate = new BridgeDispatchGate();
        long session = gate.activate(authorization);
        List<Runnable> mainQueue = new ArrayList<>();
        List<String> destinations = new ArrayList<>();

        gate.enqueue(session, credential.bearerValue(), "QMVX+2P", mainQueue::add,
                currentAuthorization::get, destinations::add);
        currentAuthorization.set(BridgeAuthorization.none());
        mainQueue.remove(0).run();

        assertEquals(0, destinations.size());
    }
}
