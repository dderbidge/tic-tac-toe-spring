package com.derbysquad.tictactoe.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AiTest {

    /** Fixed seed so {@code RECRUIT}'s random choices are reproducible. */
    private Ai ai() {
        return new Ai(new Random(13));
    }

    // ── Minimax optimality: a Warlord never loses, from any position ─────────

    @Test
    void warlordNeverLosesMovingFirst() {
        assertNeverLoses(new Board(), Player.ASCENDANT, Player.ASCENDANT);
    }

    @Test
    void warlordNeverLosesMovingSecond() {
        // Opponent (Ascendant) moves first into an empty board; Warlord is Vanguard.
        assertNeverLoses(new Board(), Player.VANGUARD, Player.ASCENDANT);
    }

    /**
     * Exhaustively walks the game tree. On the AI's turn it plays the single Warlord
     * move; on the opponent's turn it branches over <em>every</em> legal reply. If the
     * AI is truly optimal, the opponent loses against this exhaustive adversary in zero
     * lines — every leaf is an AI win or a draw.
     */
    private void assertNeverLoses(Board board, Player aiSide, Player toMove) {
        Player winner = board.winner();
        if (winner != null) {
            assertNotEquals(Board.other(aiSide), winner,
                    "Warlord lost — minimax is not optimal. Board:\n" + render(board));
            return;
        }
        if (board.isDraw()) {
            return;
        }

        if (toMove == aiSide) {
            Move m = ai().chooseMove(board, aiSide, Difficulty.WARLORD);
            Board next = board.copy();
            assertTrue(next.tryPlace(m.row(), m.col(), aiSide), "Warlord returned an illegal move");
            assertNeverLoses(next, aiSide, Board.other(toMove));
        } else {
            for (Move m : board.emptyCells()) {
                Board next = board.copy();
                next.tryPlace(m.row(), m.col(), toMove);
                assertNeverLoses(next, aiSide, Board.other(toMove));
            }
        }
    }

    // ── Forced win: take the immediate winning move ─────────────────────────

    @Test
    void warlordTakesImmediateWinningMove() {
        // Ascendant has A1,A2; A3 completes the top row.
        Board b = new Board();
        b.tryPlace(0, 0, Player.ASCENDANT);
        b.tryPlace(0, 1, Player.ASCENDANT);
        b.tryPlace(1, 0, Player.VANGUARD);
        b.tryPlace(1, 1, Player.VANGUARD);

        Move m = ai().chooseMove(b, Player.ASCENDANT, Difficulty.WARLORD);
        assertEquals(new Move(0, 2), m);
    }

    @Test
    void warlordPrefersFasterWinViaDepthWeighting() {
        // Warlord (Ascendant) to move with an immediate win available at (0,2).
        // Depth weighting must make it take the win now rather than dally.
        Board b = new Board();
        b.tryPlace(0, 0, Player.ASCENDANT);
        b.tryPlace(0, 1, Player.ASCENDANT);
        b.tryPlace(2, 0, Player.VANGUARD);
        b.tryPlace(2, 1, Player.VANGUARD);

        Move m = ai().chooseMove(b, Player.ASCENDANT, Difficulty.WARLORD);
        Board after = b.copy();
        after.tryPlace(m.row(), m.col(), Player.ASCENDANT);
        assertEquals(Player.ASCENDANT, after.winner());
    }

    // ── Forced draw: perfect vs perfect always draws ────────────────────────

    @Test
    void warlordVsWarlordAlwaysDraws() {
        Board b = new Board();
        Player toMove = Player.ASCENDANT;
        while (!b.isGameOver()) {
            Move m = ai().chooseMove(b, toMove, Difficulty.WARLORD);
            assertTrue(b.tryPlace(m.row(), m.col(), toMove));
            toMove = Board.other(toMove);
        }
        assertTrue(b.isDraw(), "Two perfect players must draw. Board:\n" + render(b));
    }

    // ── Captain: one-ply tactics ────────────────────────────────────────────

    @Test
    void captainTakesImmediateWinOverBlock() {
        // Captain (Vanguard) can win at (1,2); opponent also threatens (0,2).
        // Winning takes priority over blocking.
        Board b = new Board();
        b.tryPlace(1, 0, Player.VANGUARD);
        b.tryPlace(1, 1, Player.VANGUARD);   // Vanguard threatens row 1
        b.tryPlace(0, 0, Player.ASCENDANT);
        b.tryPlace(0, 1, Player.ASCENDANT);  // Ascendant threatens row 0

        Move m = ai().chooseMove(b, Player.VANGUARD, Difficulty.CAPTAIN);
        assertEquals(new Move(1, 2), m);
    }

    @Test
    void captainBlocksOpponentWinWhenItHasNoWinOfItsOwn() {
        // Ascendant threatens to complete the top row at (0,2); Captain (Vanguard)
        // has no immediate win, so it must block.
        Board b = new Board();
        b.tryPlace(0, 0, Player.ASCENDANT);
        b.tryPlace(0, 1, Player.ASCENDANT);
        b.tryPlace(2, 2, Player.VANGUARD);

        Move m = ai().chooseMove(b, Player.VANGUARD, Difficulty.CAPTAIN);
        assertEquals(new Move(0, 2), m);
    }

    // ── Recruit: legal and deterministic under a fixed seed ─────────────────

    @Test
    void recruitReturnsALegalEmptyCell() {
        Board b = new Board();
        b.tryPlace(0, 0, Player.ASCENDANT);
        Move m = ai().chooseMove(b, Player.VANGUARD, Difficulty.RECRUIT);
        assertTrue(b.emptyCells().contains(m));
        assertEquals(Player.NONE, b.at(m.row(), m.col()));
    }

    @Test
    void recruitIsDeterministicForAFixedSeed() {
        Move first = new Ai(new Random(13)).chooseMove(new Board(), Player.ASCENDANT, Difficulty.RECRUIT);
        Move second = new Ai(new Random(13)).chooseMove(new Board(), Player.ASCENDANT, Difficulty.RECRUIT);
        assertEquals(first, second);
    }

    @ParameterizedTest
    @EnumSource(Difficulty.class)
    void everyDifficultyReturnsALegalMove(Difficulty difficulty) {
        Board b = new Board();
        b.tryPlace(1, 1, Player.ASCENDANT);
        Move m = ai().chooseMove(b, Player.VANGUARD, difficulty);
        assertTrue(b.emptyCells().contains(m));
    }

    private static String render(Board b) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                sb.append(switch (b.at(r, c)) {
                    case ASCENDANT -> 'A';
                    case VANGUARD -> 'V';
                    default -> '.';
                });
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
