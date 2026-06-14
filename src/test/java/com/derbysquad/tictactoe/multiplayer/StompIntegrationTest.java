package com.derbysquad.tictactoe.multiplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.derbysquad.tictactoe.dto.ChallengeAcceptedEvent;
import com.derbysquad.tictactoe.dto.ChallengePlayerRequest;
import com.derbysquad.tictactoe.dto.IncomingChallengeEvent;
import com.derbysquad.tictactoe.dto.LobbyStateEvent;
import com.derbysquad.tictactoe.dto.MatchEndedEvent;
import com.derbysquad.tictactoe.dto.MatchStateEvent;
import com.derbysquad.tictactoe.dto.PlaceMoveRequest;
import com.derbysquad.tictactoe.dto.PlayerJoinedEvent;
import com.derbysquad.tictactoe.dto.RespondToChallengeRequest;
import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * End-to-end STOMP test (checklist Task 10): drives two real WebSocket clients through the
 * full lobby → challenge → match flow against the embedded server, asserting on the pushed
 * DTO events. This is the parity check for the SignalR hub's observable behavior.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompIntegrationTest {

    @LocalServerPort
    int port;

    private static final long TIMEOUT_SECONDS = 5;

    private WebSocketStompClient newClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client;
    }

    private StompSession connect() throws Exception {
        return newClient()
                .connectAsync("ws://localhost:" + port + "/gamehub", new StompSessionHandlerAdapter() {})
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private <T> BlockingQueue<T> subscribe(StompSession session, String destination, Class<T> type) {
        BlockingQueue<T> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return type;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((T) payload);
            }
        });
        return queue;
    }

    private static <T> T poll(BlockingQueue<T> queue) throws InterruptedException {
        T value = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(value, "timed out waiting for a server push");
        return value;
    }

    /**
     * Polls until a message satisfies {@code predicate}. Needed because {@code /topic/lobby}
     * has no SignalR "others in group" semantics: a subscriber receives its own join/leave
     * broadcasts too, so callers filter their own {@code connId} (as the real client does).
     */
    private static <T> T pollUntil(BlockingQueue<T> queue, java.util.function.Predicate<T> predicate)
            throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            T value = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (value == null) break;
            if (predicate.test(value)) return value;
        }
        throw new AssertionError("no message matched the predicate in time");
    }

    @Test
    void fullLobbyChallengeMatchFlow() throws Exception {
        // ── Player A connects and enters the lobby ──────────────────────────
        StompSession a = connect();
        BlockingQueue<LobbyStateEvent> aLobbyState = subscribe(a, "/user/queue/lobby-state", LobbyStateEvent.class);
        BlockingQueue<PlayerJoinedEvent> aJoined = subscribe(a, "/topic/lobby/joined", PlayerJoinedEvent.class);
        BlockingQueue<ChallengeAcceptedEvent> aAccepted = subscribe(a, "/user/queue/challenge-accepted", ChallengeAcceptedEvent.class);
        BlockingQueue<MatchStateEvent> aState = subscribe(a, "/user/queue/match-state", MatchStateEvent.class);
        BlockingQueue<MatchEndedEvent> aEnded = subscribe(a, "/user/queue/match-ended", MatchEndedEvent.class);
        a.send("/app/lobby/enter", null);

        LobbyStateEvent aLobby = poll(aLobbyState);
        String aConnId = aLobby.yourConnId();
        assertNotNull(aConnId);
        assertNotNull(aLobby.yourName());

        // ── Player B connects and enters; A should see B join ───────────────
        StompSession b = connect();
        BlockingQueue<LobbyStateEvent> bLobbyState = subscribe(b, "/user/queue/lobby-state", LobbyStateEvent.class);
        BlockingQueue<IncomingChallengeEvent> bIncoming = subscribe(b, "/user/queue/challenge-incoming", IncomingChallengeEvent.class);
        BlockingQueue<ChallengeAcceptedEvent> bAccepted = subscribe(b, "/user/queue/challenge-accepted", ChallengeAcceptedEvent.class);
        BlockingQueue<MatchStateEvent> bState = subscribe(b, "/user/queue/match-state", MatchStateEvent.class);
        BlockingQueue<MatchEndedEvent> bEnded = subscribe(b, "/user/queue/match-ended", MatchEndedEvent.class);
        b.send("/app/lobby/enter", null);

        String bConnId = poll(bLobbyState).yourConnId();
        // A learns B's connId from the broadcast, skipping A's own join echo.
        PlayerJoinedEvent joined = pollUntil(aJoined, e -> bConnId.equals(e.connId()));
        assertEquals(bConnId, joined.connId());

        // ── A challenges B; B accepts ───────────────────────────────────────
        a.send("/app/challenge/send", new ChallengePlayerRequest(bConnId));
        IncomingChallengeEvent incoming = poll(bIncoming);
        assertEquals(aConnId, incoming.fromConnId());

        b.send("/app/challenge/respond", new RespondToChallengeRequest(incoming.challengeId(), true));

        ChallengeAcceptedEvent aAck = poll(aAccepted);
        ChallengeAcceptedEvent bAck = poll(bAccepted);
        assertEquals("Ascendant", aAck.yourFaction()); // challenger
        assertEquals("Vanguard", bAck.yourFaction());  // accepter
        String matchId = aAck.matchId();

        poll(aState); // initial empty-board broadcast
        poll(bState);

        // ── Play to an Ascendant (A) win on the top row ─────────────────────
        // A=(0,0); B=(1,0); A=(0,1); B=(1,1); A=(0,2) → A wins.
        a.send("/app/match/move", new PlaceMoveRequest(matchId, 0, 0));
        MatchStateEvent afterA1 = poll(aState);
        poll(bState);
        assertEquals(1, afterA1.board()[0][0]);          // Ascendant ordinal
        assertEquals("Vanguard", afterA1.activeFaction()); // turn passed to B

        b.send("/app/match/move", new PlaceMoveRequest(matchId, 1, 0));
        poll(aState);
        poll(bState);

        a.send("/app/match/move", new PlaceMoveRequest(matchId, 0, 1));
        poll(aState);
        poll(bState);

        b.send("/app/match/move", new PlaceMoveRequest(matchId, 1, 1));
        poll(aState);
        poll(bState);

        a.send("/app/match/move", new PlaceMoveRequest(matchId, 0, 2)); // winning move

        MatchStateEvent finalState = poll(aState);
        assertEquals("Ascendant", finalState.winner());
        assertNotNull(finalState.winningCells());
        assertEquals(3, finalState.winningCells().size());

        MatchEndedEvent aResult = poll(aEnded);
        MatchEndedEvent bResult = poll(bEnded);
        assertEquals("win", aResult.result());
        assertEquals("loss", bResult.result());
        assertEquals("win", aResult.reason());

        a.disconnect();
        b.disconnect();
    }

    @Test
    void declinedChallengeNotifiesChallenger() throws Exception {
        StompSession a = connect();
        BlockingQueue<LobbyStateEvent> aLobbyState = subscribe(a, "/user/queue/lobby-state", LobbyStateEvent.class);
        BlockingQueue<PlayerJoinedEvent> aJoined = subscribe(a, "/topic/lobby/joined", PlayerJoinedEvent.class);
        var aDeclined = subscribe(a, "/user/queue/challenge-declined", com.derbysquad.tictactoe.dto.ChallengeDeclinedEvent.class);
        a.send("/app/lobby/enter", null);
        String aConnId = poll(aLobbyState).yourConnId();

        StompSession b = connect();
        BlockingQueue<LobbyStateEvent> bLobbyState = subscribe(b, "/user/queue/lobby-state", LobbyStateEvent.class);
        BlockingQueue<IncomingChallengeEvent> bIncoming = subscribe(b, "/user/queue/challenge-incoming", IncomingChallengeEvent.class);
        b.send("/app/lobby/enter", null);
        String bConnId = poll(bLobbyState).yourConnId();
        pollUntil(aJoined, e -> bConnId.equals(e.connId())); // wait for B's join, skip A's own echo

        a.send("/app/challenge/send", new ChallengePlayerRequest(bConnId));
        IncomingChallengeEvent incoming = poll(bIncoming);

        b.send("/app/challenge/respond", new RespondToChallengeRequest(incoming.challengeId(), false));
        var declined = poll(aDeclined);
        assertTrue(declined.fromName() != null && !declined.fromName().isBlank());

        a.disconnect();
        b.disconnect();
    }
}
