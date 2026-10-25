package com.derbysquad.tictactoe.multiplayer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.derbysquad.tictactoe.domain.Player;
import com.derbysquad.tictactoe.dto.RejoinMatchRequest;
import com.derbysquad.tictactoe.dto.RespondToChallengeRequest;
import com.derbysquad.tictactoe.dto.PlaceMoveRequest;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

class MatchAuthorizationRegressionTest {
    final TestClock clock = new TestClock();
    final MatchRegistry matches = new MatchRegistry(clock);
    final PresenceTracker presence = new PresenceTracker();
    final TaskScheduler scheduler = mock(TaskScheduler.class);
    final List<ScheduledFuture<?>> timers = new ArrayList<>();
    final List<Runnable> callbacks = new ArrayList<>();
    final ChallengeBroker challenges = new ChallengeBroker(event -> {}, 30000);
    final GameMessageController controller = new GameMessageController(presence, matches,
            challenges, mock(LocalGameService.class),
            mock(SimpMessagingTemplate.class), scheduler, 30);

    MatchAuthorizationRegressionTest() {
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> {
            ScheduledFuture<?> timer = mock(ScheduledFuture.class);
            timers.add(timer);
            callbacks.add(call.getArgument(0));
            return timer;
        });
    }

    MultiplayerMatch start() {
        presence.add("a");
        presence.add("b");
        MultiplayerMatch match = matches.create("a", "A", "b", "B");
        presence.enterMatch("a", match.matchId());
        presence.enterMatch("b", match.matchId());
        return match;
    }

    void disconnect(String connectionId) {
        controller.onSessionDisconnect(new SessionDisconnectEvent(this,
                new GenericMessage<>(new byte[0]), connectionId, CloseStatus.NORMAL));
    }

    @Test
    void outsiderCannotForfeitOrChangeTheResult() {
        MultiplayerMatch match = start();
        assertNull(matches.forfeit(match.matchId(), "outsider"));
        assertNull(match.winner());
        assertSame(match, matches.get(match.matchId()));
        assertSame(match, matches.forfeit(match.matchId(), "b"));
        assertEquals(Player.ASCENDANT, match.winner());
        assertNull(matches.forfeit(match.matchId(), "a"), "terminal results are emitted once");
    }

    @Test
    void secondDisconnectDoesNotCancelFirstPlayersGrace() {
        start();
        disconnect("a");
        disconnect("b");
        assertEquals(2, timers.size());
        verify(timers.get(0), never()).cancel(anyBoolean());
    }

    @Test
    void firstPlayerReturningCancelsOnlyTheirOwnGrace() {
        MultiplayerMatch match = start();
        disconnect("a");
        disconnect("b");
        controller.rejoinMatch("a2", new RejoinMatchRequest(match.matchId(), match.tokenFor(Player.ASCENDANT)));
        verify(timers.get(0)).cancel(false);
        verify(timers.get(1), never()).cancel(anyBoolean());
        assertNotNull(presence.get("a2"), "rejoin must restore identity as well as its match mapping");
        assertEquals("A", presence.get("a2").name());
    }

    @Test
    void tokenCannotReplaceAStillConnectedSeatOrOccupyBothSeats() {
        MultiplayerMatch match = start();
        controller.rejoinMatch("stranger", new RejoinMatchRequest(match.matchId(), match.tokenFor(Player.ASCENDANT)));
        assertEquals("a", match.ascendantConnId());
        disconnect("a");
        controller.rejoinMatch("b", new RejoinMatchRequest(match.matchId(), match.tokenFor(Player.ASCENDANT)));
        assertEquals("a", match.ascendantConnId());
        assertEquals("b", match.vanguardConnId());
    }

    @Test
    void oldDisconnectCallbackCannotEndARejoinedOrLaterDisconnectedSeat() {
        MultiplayerMatch match = start();
        disconnect("a");
        controller.rejoinMatch("a2", new RejoinMatchRequest(match.matchId(), match.tokenFor(Player.ASCENDANT)));
        disconnect("a2");
        clock.now = clock.now.plusSeconds(31);
        callbacks.get(0).run();
        assertNull(match.winner());
        assertSame(match, matches.get(match.matchId()));
        callbacks.get(1).run();
        assertEquals(Player.VANGUARD, match.winner());
        assertNull(matches.get(match.matchId()));
    }

    @Test
    void rejoinAfterDeadlineIsRejectedEvenBeforeTimerRuns() {
        MultiplayerMatch match = start();
        disconnect("a");
        clock.now = clock.now.plusSeconds(30);
        controller.rejoinMatch("a2", new RejoinMatchRequest(match.matchId(), match.tokenFor(Player.ASCENDANT)));
        assertEquals("a", match.ascendantConnId());
        assertNull(presence.currentMatchOf("a2"));
        callbacks.get(0).run();
        assertEquals(Player.VANGUARD, match.winner());
    }

    @Test
    void duplicateDisconnectDoesNotExtendDeadlineAndFinishingCancelsAllTimers() {
        MultiplayerMatch match = start();
        disconnect("a");
        disconnect("a");
        disconnect("b");
        assertEquals(2, timers.size());
        matches.forfeit(match.matchId(), "b");
        for (var timer : timers) verify(timer).cancel(false);
    }

    @Test
    void outsiderCannotConsumeAChallengeAndAPlayerCannotAcceptTwoMatches() {
        presence.add("a"); presence.add("b"); presence.add("c");
        Challenge ab = challenges.create("a", "A", "b", "B");
        Challenge cb = challenges.create("c", "C", "b", "B");
        controller.respondToChallenge("outsider", new RespondToChallengeRequest(ab.challengeId(), true));
        assertNull(presence.currentMatchOf("a"));
        controller.respondToChallenge("b", new RespondToChallengeRequest(ab.challengeId(), true));
        String matchId = presence.currentMatchOf("a");
        assertNotNull(matchId);
        assertEquals(matchId, presence.currentMatchOf("b"));
        controller.respondToChallenge("b", new RespondToChallengeRequest(cb.challengeId(), true));
        assertEquals(matchId, presence.currentMatchOf("b"));
        assertNull(presence.currentMatchOf("c"));
    }

    @Test
    void invalidAndDuplicateMovesNeverChangeTurnOrBoard() {
        MultiplayerMatch match = start();
        String id = match.matchId();
        controller.placeMove("a", new PlaceMoveRequest(id, null, null));
        assertFalse(matches.tryPlace(id, "b", 0, 0).accepted());
        assertFalse(matches.tryPlace(id, "a", -1, 0).accepted());
        assertFalse(matches.tryPlace(id, "outsider", 0, 0).accepted());
        assertEquals(9, match.board().emptyCells().size());
        assertTrue(matches.tryPlace(id, "a", 0, 0).accepted());
        assertFalse(matches.tryPlace(id, "a", 0, 0).accepted());
        assertFalse(matches.tryPlace(id, "b", 0, 0).accepted());
        assertEquals(8, match.board().emptyCells().size());
        assertEquals(Player.VANGUARD, match.active());
        assertDoesNotThrow(() -> controller.placeMove("a", new PlaceMoveRequest(null, 0, 0)));
        assertDoesNotThrow(() -> controller.respondToChallenge("a", new RespondToChallengeRequest(null, true)));
    }

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-25T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
