package com.derbysquad.tictactoe.multiplayer;

import static com.derbysquad.tictactoe.multiplayer.StompTestSupport.nothingArrives;
import static com.derbysquad.tictactoe.multiplayer.StompTestSupport.poll;
import static com.derbysquad.tictactoe.multiplayer.StompTestSupport.sub;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.derbysquad.tictactoe.dto.LocalStateEvent;
import com.derbysquad.tictactoe.dto.PlayerJoinedEvent;
import java.util.concurrent.BlockingQueue;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Real transport regressions: only the server may publish events or resolve private queues. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrokerIsolationTest {
    @LocalServerPort
    int port;

    @Test
    void clientCannotForgeLobbyEvents() throws Exception {
        LobbyClient victim = LobbyClient.enterLobby(port);
        LobbyClient attacker = LobbyClient.connectOnly(port);
        try {
            // EnterLobby's own event proves this normal subscription is ready.
            poll(victim.joined);
            attacker.send("/topic/lobby/joined", new PlayerJoinedEvent("forged-session", "Forged player"));
            assertTrue(nothingArrives(victim.joined, 500), "client SEND must not impersonate a server event");
        } finally {
            if (victim.session.isConnected()) victim.disconnect();
            if (attacker.session.isConnected()) attacker.disconnect();
        }
    }

    @Test
    void clientCannotSubscribeToAnotherSessionsResolvedQueue() throws Exception {
        LobbyClient victim = LobbyClient.enterLobby(port);
        LobbyClient attacker = LobbyClient.connectOnly(port);
        try {
            BlockingQueue<LocalStateEvent> stolen = sub(attacker.session,
                    "/queue/local-state-user" + victim.connId, LocalStateEvent.class);
            // The following round-trip ensures the subscription was processed first.
            // Rejected subscriptions close the attacker's transport. Before the fix,
            // this round-trip supplies a barrier behind the malicious subscription.
            try {
                attacker.send("/app/local/state", null);
                attacker.localState.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (IllegalStateException closed) {
                assertFalse(attacker.session.isConnected());
            }
            victim.send("/app/local/state", null);
            poll(victim.localState);
            assertTrue(nothingArrives(stolen, 500), "resolved session queues must remain private");
        } finally {
            if (victim.session.isConnected()) victim.disconnect();
            if (attacker.session.isConnected()) attacker.disconnect();
        }
    }
}
