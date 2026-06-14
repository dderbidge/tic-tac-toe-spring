package com.derbysquad.tictactoe.multiplayer;

import static com.derbysquad.tictactoe.multiplayer.StompTestSupport.poll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.derbysquad.tictactoe.dto.ChallengeAcceptedEvent;
import com.derbysquad.tictactoe.dto.ChallengeExpiredEvent;
import com.derbysquad.tictactoe.dto.ChallengePlayerRequest;
import com.derbysquad.tictactoe.dto.IncomingChallengeEvent;
import com.derbysquad.tictactoe.dto.MatchEndedEvent;
import com.derbysquad.tictactoe.dto.OpponentDisconnectedEvent;
import com.derbysquad.tictactoe.dto.RespondToChallengeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Verifies the timers that replaced the C# {@code System.Threading.Timer}s actually fire:
 * the {@code @Scheduled} challenge-expiry sweep and the {@code TaskScheduler}
 * disconnect-grace forfeit. Timings are shrunk via properties so the test runs in ~seconds.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "tictactoe.challenge.lifetime-ms=300",
                "tictactoe.challenge.sweep-ms=150",
                "tictactoe.disconnect-grace-seconds=1"
        })
class ScheduledTimersTest {

    @LocalServerPort
    int port;

    @Test
    void scheduledSweepExpiresAnUnansweredChallengeToBothParties() throws Exception {
        LobbyClient a = LobbyClient.enterLobby(port);
        LobbyClient b = LobbyClient.enterLobby(port);

        a.send("/app/challenge/send", new ChallengePlayerRequest(b.connId));
        IncomingChallengeEvent ic = poll(b.incoming);

        // Neither responds. The @Scheduled sweep should expire it and notify both, by role.
        ChallengeExpiredEvent fromSide = poll(a.expired);
        ChallengeExpiredEvent toSide = poll(b.expired);
        assertEquals(ic.challengeId(), fromSide.challengeId());
        assertEquals("from", fromSide.role());
        assertEquals(ic.challengeId(), toSide.challengeId());
        assertEquals("to", toSide.role());

        a.disconnect();
        b.disconnect();
    }

    @Test
    void disconnectGraceTimerForfeitsTheMatchToTheOpponent() throws Exception {
        LobbyClient a = LobbyClient.enterLobby(port);
        LobbyClient b = LobbyClient.enterLobby(port);

        // a challenges -> a is Ascendant, b is Vanguard.
        a.send("/app/challenge/send", new ChallengePlayerRequest(b.connId));
        IncomingChallengeEvent ic = poll(b.incoming);
        b.send("/app/challenge/respond", new RespondToChallengeRequest(ic.challengeId(), true));
        ChallengeAcceptedEvent ca = poll(a.accepted);
        poll(b.accepted);
        poll(a.matchState);
        poll(b.matchState);

        // b drops mid-match. a is warned, then after the 1s grace the TaskScheduler forfeits to a.
        b.disconnect();
        OpponentDisconnectedEvent warn = poll(a.oppDisconnected);
        assertEquals(1, warn.secondsLeft());

        MatchEndedEvent ended = poll(a.matchEnded);
        assertEquals("win", ended.result());
        assertEquals("forfeit", ended.reason());

        a.disconnect();
    }
}
