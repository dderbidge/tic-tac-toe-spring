package com.derbysquad.tictactoe.multiplayer;

import static com.derbysquad.tictactoe.multiplayer.StompTestSupport.nothingArrives;
import static com.derbysquad.tictactoe.multiplayer.StompTestSupport.poll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.derbysquad.tictactoe.dto.ChallengeAcceptedEvent;
import com.derbysquad.tictactoe.dto.ChallengePlayerRequest;
import com.derbysquad.tictactoe.dto.IncomingChallengeEvent;
import com.derbysquad.tictactoe.dto.MatchEndedEvent;
import com.derbysquad.tictactoe.dto.MatchStateEvent;
import com.derbysquad.tictactoe.dto.OpponentDisconnectedEvent;
import com.derbysquad.tictactoe.dto.PlaceMoveRequest;
import com.derbysquad.tictactoe.dto.RespondToChallengeRequest;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * End-to-end coverage of the multiplayer backend (the SignalR -> STOMP redesign): a full
 * match to a draw, two concurrent matches with no cross-talk, STOMP session-id identity,
 * and session-keyed state cleanup on disconnect. Asserts against the same singleton service
 * beans the server uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiplayerEndToEndTest {

    @LocalServerPort
    int port;

    @Autowired
    PresenceTracker presence;
    @Autowired
    MatchRegistry matches;
    @Autowired
    LocalGameService localGames;

    @Test
    void fullMatchToDraw() throws Exception {
        LobbyClient a = LobbyClient.enterLobby(port); // challenger -> Ascendant
        LobbyClient b = LobbyClient.enterLobby(port); // accepter  -> Vanguard
        String matchId = challengeAndStart(a, b);

        // A=(0,0)(0,2)(1,0)(2,1)(2,2)  B=(0,1)(1,1)(1,2)(2,0)  -> full board, no line.
        play(a, matchId, 0, 0, a, b);
        play(b, matchId, 0, 1, a, b);
        play(a, matchId, 0, 2, a, b);
        play(b, matchId, 1, 1, a, b);
        play(a, matchId, 1, 0, a, b);
        play(b, matchId, 1, 2, a, b);
        play(a, matchId, 2, 1, a, b);
        play(b, matchId, 2, 0, a, b);
        MatchStateEvent last = play(a, matchId, 2, 2, a, b);

        assertTrue(last.isDraw(), "final state must be a draw");
        assertNull(last.winner());

        MatchEndedEvent ea = poll(a.matchEnded);
        MatchEndedEvent eb = poll(b.matchEnded);
        assertEquals("draw", ea.result());
        assertEquals("draw", ea.reason());
        assertEquals("draw", eb.result());
        assertEquals("draw", eb.reason());

        // match removed from the registry once ended -> no leaked state.
        awaitNull(() -> matches.get(matchId));

        a.disconnect();
        b.disconnect();
    }

    @Test
    void twoConcurrentMatchesHaveNoCrossTalk() throws Exception {
        LobbyClient a = LobbyClient.enterLobby(port);
        LobbyClient b = LobbyClient.enterLobby(port);
        LobbyClient c = LobbyClient.enterLobby(port);
        LobbyClient d = LobbyClient.enterLobby(port);

        String m1 = challengeAndStart(a, b);
        String m2 = challengeAndStart(c, d);
        assertNotEquals(m1, m2, "each match gets its own id");

        // A move in match 1 reaches only match-1 participants.
        a.send("/app/match/move", new PlaceMoveRequest(m1, 0, 0));
        MatchStateEvent sa = poll(a.matchState);
        poll(b.matchState);
        assertEquals(m1, sa.matchId());
        assertTrue(nothingArrives(c.matchState, 700), "match-2 client C must not see match-1 state");
        assertTrue(nothingArrives(d.matchState, 700), "match-2 client D must not see match-1 state");

        // A move in match 2 reaches only match-2 participants.
        c.send("/app/match/move", new PlaceMoveRequest(m2, 1, 1));
        MatchStateEvent sc = poll(c.matchState);
        poll(d.matchState);
        assertEquals(m2, sc.matchId());
        assertTrue(nothingArrives(a.matchState, 700), "match-1 client A must not see match-2 state");
        assertTrue(nothingArrives(b.matchState, 700), "match-1 client B must not see match-2 state");

        assertNotNull(matches.get(m1));
        assertNotNull(matches.get(m2));

        a.disconnect();
        b.disconnect();
        c.disconnect();
        d.disconnect();
    }

    @Test
    void serverAssignedSessionIdentityIsConsistentAndLocalStateIsCleanedUpOnDisconnect() throws Exception {
        // Identity is the SERVER's session id (the analog of SignalR's Context.ConnectionId),
        // surfaced to the client as `yourConnId` — NOT the client library's own StompSession id.
        // Verify it's consistent across the server's views: an observer sees the same id for A...
        LobbyClient observer = LobbyClient.connectOnly(port); // subscribed before A joins
        LobbyClient a = LobbyClient.enterLobby(port);
        var seen = StompTestSupport.pollUntil(observer.joined, e -> e.connId().equals(a.connId));
        assertEquals(a.connId, seen.connId());

        // ...and the server keys session state by that very id: a local game created over A's
        // session is found under A's reported connId.
        a.send("/app/local/hotseat", null);
        poll(a.localState);
        String sid = a.connId;
        assertNotNull(localGames.get(sid), "local game-state is keyed by the server-assigned connId");

        a.disconnect();

        // The disconnect listener removes both the local game-state and presence (no leak).
        awaitNull(() -> localGames.get(sid));
        awaitNull(() -> presence.get(sid));

        observer.disconnect();
    }

    @Test
    void networkDisconnectNotifiesOpponentAndRemovesPresence() throws Exception {
        LobbyClient a = LobbyClient.enterLobby(port);
        LobbyClient b = LobbyClient.enterLobby(port);
        challengeAndStart(a, b);
        String bId = b.connId;

        b.disconnect();

        OpponentDisconnectedEvent ev = poll(a.oppDisconnected);
        assertEquals(30, ev.secondsLeft()); // default grace
        awaitNull(() -> presence.get(bId));

        a.disconnect();
    }

    // ── Helpers ───────────────────────────────────────────────────

    private String challengeAndStart(LobbyClient challenger, LobbyClient target) throws Exception {
        challenger.send("/app/challenge/send", new ChallengePlayerRequest(target.connId));
        IncomingChallengeEvent ic = poll(target.incoming);
        target.send("/app/challenge/respond", new RespondToChallengeRequest(ic.challengeId(), true));
        ChallengeAcceptedEvent ca = poll(challenger.accepted);
        poll(target.accepted);
        poll(challenger.matchState); // initial empty board
        poll(target.matchState);
        return ca.matchId();
    }

    private MatchStateEvent play(LobbyClient mover, String matchId, int r, int c,
                                 LobbyClient p1, LobbyClient p2) throws Exception {
        mover.send("/app/match/move", new PlaceMoveRequest(matchId, r, c));
        MatchStateEvent s = poll(p1.matchState);
        poll(p2.matchState);
        return s;
    }

    private static void awaitNull(Supplier<Object> supplier) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (supplier.get() == null) return;
            Thread.sleep(100);
        }
        throw new AssertionError("value did not become null within the timeout");
    }
}
