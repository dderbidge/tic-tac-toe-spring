package com.derbysquad.tictactoe.multiplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.derbysquad.tictactoe.domain.Ai;
import com.derbysquad.tictactoe.domain.Difficulty;
import com.derbysquad.tictactoe.domain.Player;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the session-keyed local game service (Task 7). It has no STOMP
 * transport yet (see {@link LocalGameService} flag), so it's verified directly here.
 */
class LocalGameServiceTest {

    private LocalGameService newService() {
        return new LocalGameService(new Ai(new Random(13)));
    }

    @Test
    void sessionsAreIsolatedAndStable() {
        LocalGameService svc = newService();
        LocalGame a1 = svc.getOrCreate("session-A");
        LocalGame a2 = svc.getOrCreate("session-A");
        LocalGame b = svc.getOrCreate("session-B");

        assertSame(a1, a2, "same session id returns the same game");
        assertNotSame(a1, b, "different sessions get different games");
        assertEquals(2, svc.activeSessionCount());

        svc.remove("session-A");
        assertNull(svc.get("session-A"));
        assertEquals(1, svc.activeSessionCount());
    }

    @Test
    void hotSeatGameToAscendantRowWinUpdatesScore() {
        LocalGame game = newService().getOrCreate("s");
        game.startHotSeat();
        // A=(0,0) B=(1,0) A=(0,1) B=(1,1) A=(0,2) → Ascendant top-row win
        game.tryHumanMove(0, 0);
        game.tryHumanMove(1, 0);
        game.tryHumanMove(0, 1);
        game.tryHumanMove(1, 1);
        game.tryHumanMove(0, 2);

        assertEquals(LocalGame.Phase.MATCH_OVER, game.phase());
        assertEquals(Player.ASCENDANT, game.winner());
        assertEquals(1, game.ascendantWins());
        assertEquals(3, game.winningCells().size());
    }

    @Test
    void versusWarlordHumanNeverWins() {
        LocalGame game = newService().getOrCreate("s");
        // Human is Ascendant; AI (Vanguard) is Warlord and replies after each human move.
        game.startVersusComputer(Player.ASCENDANT, Difficulty.WARLORD);
        int[][] attack = {{0, 0}, {0, 1}, {0, 2}, {1, 0}, {1, 1}};
        for (int[] m : attack) {
            game.tryHumanMove(m[0], m[1]);
            if (game.phase() == LocalGame.Phase.MATCH_OVER) break;
        }
        assertTrue(game.winner() != Player.ASCENDANT, "a perfect Warlord must never let the human win");
    }
}
