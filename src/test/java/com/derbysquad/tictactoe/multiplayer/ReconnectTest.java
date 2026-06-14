package com.derbysquad.tictactoe.multiplayer;

import static com.derbysquad.tictactoe.multiplayer.StompTestSupport.nothingArrives;
import static com.derbysquad.tictactoe.multiplayer.StompTestSupport.poll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.derbysquad.tictactoe.dto.ChallengeAcceptedEvent;
import com.derbysquad.tictactoe.dto.ChallengePlayerRequest;
import com.derbysquad.tictactoe.dto.IncomingChallengeEvent;
import com.derbysquad.tictactoe.dto.MatchStateEvent;
import com.derbysquad.tictactoe.dto.PlaceMoveRequest;
import com.derbysquad.tictactoe.dto.RejoinMatchRequest;
import com.derbysquad.tictactoe.dto.RespondToChallengeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Durable reconnect: a dropped player reclaims its faction slot with the server-issued
 * token (the STOMP session id changes on reconnect, so it can't be the identity). Also
 * verifies the token is required — a bogus token cannot hijack a slot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReconnectTest {

    @LocalServerPort
    int port;

    /** A,B start a match; returns A's accepted event (matchId + A's Ascendant token). */
    private ChallengeAcceptedEvent startMatch(LobbyClient a, LobbyClient b) throws Exception {
        a.send("/app/challenge/send", new ChallengePlayerRequest(b.connId));
        IncomingChallengeEvent ic = poll(b.incoming);
        b.send("/app/challenge/respond", new RespondToChallengeRequest(ic.challengeId(), true));
        ChallengeAcceptedEvent aAck = poll(a.accepted);
        poll(b.accepted);
        poll(a.matchState);
        poll(b.matchState);
        return aAck;
    }

    @Test
    void reconnectWithTokenRestoresBoardAndNotifiesOpponent() throws Exception {
        LobbyClient a = LobbyClient.enterLobby(port);
        LobbyClient b = LobbyClient.enterLobby(port);
        ChallengeAcceptedEvent aAck = startMatch(a, b);
        String matchId = aAck.matchId();
        String aToken = aAck.yourToken();
        assertNotNull(aToken, "accept must issue a rejoin token");

        // A makes a move so the restored board is observably non-empty.
        a.send("/app/match/move", new PlaceMoveRequest(matchId, 0, 0));
        poll(a.matchState);
        poll(b.matchState);

        // A drops; B is warned.
        a.disconnect();
        poll(b.oppDisconnected);

        // A reconnects on a brand-new STOMP session and rejoins with the token.
        LobbyClient a2 = LobbyClient.connectOnly(port);
        a2.send("/app/match/rejoin", new RejoinMatchRequest(matchId, aToken));

        ChallengeAcceptedEvent restored = poll(a2.accepted);
        assertEquals("Ascendant", restored.yourFaction());
        assertEquals(aToken, restored.yourToken());

        MatchStateEvent state = poll(a2.matchState);
        assertEquals(1, state.board()[0][0], "the move made before the drop must survive the reconnect");

        // The opponent is told play resumed.
        poll(b.oppReconnected);

        // And the match is live again: A2 (Ascendant... it's Vanguard's turn now) — B moves, A2 sees it.
        b.send("/app/match/move", new PlaceMoveRequest(matchId, 1, 1));
        MatchStateEvent afterB = poll(a2.matchState);
        assertEquals(2, afterB.board()[1][1]);

        a2.disconnect();
        b.disconnect();
    }

    @Test
    void rejoinWithWrongTokenIsRejected() throws Exception {
        LobbyClient a = LobbyClient.enterLobby(port);
        LobbyClient b = LobbyClient.enterLobby(port);
        String matchId = startMatch(a, b).matchId();

        // An imposter tries to claim a slot with a bogus token.
        LobbyClient imposter = LobbyClient.connectOnly(port);
        imposter.send("/app/match/rejoin", new RejoinMatchRequest(matchId, "not-a-valid-token"));

        assertTrue(nothingArrives(imposter.accepted, 700), "imposter must not be admitted to the match");
        assertTrue(nothingArrives(imposter.matchState, 200), "imposter must not receive match state");

        // The real participants are unaffected: it's still A's (Ascendant) turn and A owns the slot.
        a.send("/app/match/move", new PlaceMoveRequest(matchId, 2, 2));
        MatchStateEvent s = poll(a.matchState);
        poll(b.matchState);
        assertEquals(1, s.board()[2][2]);

        imposter.disconnect();
        a.disconnect();
        b.disconnect();
    }
}
