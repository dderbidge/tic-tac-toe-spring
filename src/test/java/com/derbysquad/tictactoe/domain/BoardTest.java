package com.derbysquad.tictactoe.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardTest {

    // ── Ordinal contract (load-bearing for the future wire format) ──────────

    @Test
    void playerOrdinalsAreStable() {
        assertEquals(0, Player.NONE.ordinal());
        assertEquals(1, Player.ASCENDANT.ordinal());
        assertEquals(2, Player.VANGUARD.ordinal());
    }

    // ── Placement ───────────────────────────────────────────────────────────

    @Test
    void emptyBoardHasNoWinnerAndIsNotDraw() {
        Board b = new Board();
        assertNull(b.winner());
        assertFalse(b.isDraw());
        assertFalse(b.isGameOver());
        assertEquals(9, b.emptyCells().size());
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                assertEquals(Player.NONE, b.at(r, c));
            }
        }
    }

    @Test
    void tryPlaceOnEmptyCellSucceeds() {
        Board b = new Board();
        assertTrue(b.tryPlace(1, 1, Player.ASCENDANT));
        assertEquals(Player.ASCENDANT, b.at(1, 1));
        assertEquals(8, b.emptyCells().size());
    }

    @Test
    void tryPlaceOnOccupiedCellFails() {
        Board b = new Board();
        assertTrue(b.tryPlace(0, 0, Player.ASCENDANT));
        assertFalse(b.tryPlace(0, 0, Player.VANGUARD));
        assertEquals(Player.ASCENDANT, b.at(0, 0)); // unchanged
    }

    @Test
    void tryPlaceWithNoneMarkerFails() {
        Board b = new Board();
        assertFalse(b.tryPlace(0, 0, Player.NONE));
        assertEquals(Player.NONE, b.at(0, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 3, 99})
    void tryPlaceOutOfBoundsRowFails(int badRow) {
        Board b = new Board();
        assertFalse(b.tryPlace(badRow, 0, Player.ASCENDANT));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 3, 99})
    void tryPlaceOutOfBoundsColFails(int badCol) {
        Board b = new Board();
        assertFalse(b.tryPlace(0, badCol, Player.ASCENDANT));
    }

    // ── Wins ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void detectsRowWin(int row) {
        Board b = new Board();
        for (int c = 0; c < 3; c++) b.tryPlace(row, c, Player.ASCENDANT);
        assertEquals(Player.ASCENDANT, b.winner());
        assertTrue(b.isGameOver());
        assertFalse(b.isDraw());
        assertEquals(List.of(new Move(row, 0), new Move(row, 1), new Move(row, 2)), b.winningLine());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void detectsColumnWin(int col) {
        Board b = new Board();
        for (int r = 0; r < 3; r++) b.tryPlace(r, col, Player.VANGUARD);
        assertEquals(Player.VANGUARD, b.winner());
        assertEquals(List.of(new Move(0, col), new Move(1, col), new Move(2, col)), b.winningLine());
    }

    @Test
    void detectsMainDiagonalWin() {
        Board b = new Board();
        b.tryPlace(0, 0, Player.ASCENDANT);
        b.tryPlace(1, 1, Player.ASCENDANT);
        b.tryPlace(2, 2, Player.ASCENDANT);
        assertEquals(Player.ASCENDANT, b.winner());
        assertEquals(List.of(new Move(0, 0), new Move(1, 1), new Move(2, 2)), b.winningLine());
    }

    @Test
    void detectsAntiDiagonalWin() {
        Board b = new Board();
        b.tryPlace(0, 2, Player.VANGUARD);
        b.tryPlace(1, 1, Player.VANGUARD);
        b.tryPlace(2, 0, Player.VANGUARD);
        assertEquals(Player.VANGUARD, b.winner());
        assertEquals(List.of(new Move(0, 2), new Move(1, 1), new Move(2, 0)), b.winningLine());
    }

    // ── Draw ────────────────────────────────────────────────────────────────

    @Test
    void detectsFullBoardDraw() {
        // A X A / A V V / V A A  → full grid, no three-in-a-row.
        Board b = new Board();
        b.tryPlace(0, 0, Player.ASCENDANT);
        b.tryPlace(0, 1, Player.VANGUARD);
        b.tryPlace(0, 2, Player.ASCENDANT);
        b.tryPlace(1, 0, Player.ASCENDANT);
        b.tryPlace(1, 1, Player.VANGUARD);
        b.tryPlace(1, 2, Player.VANGUARD);
        b.tryPlace(2, 0, Player.VANGUARD);
        b.tryPlace(2, 1, Player.ASCENDANT);
        b.tryPlace(2, 2, Player.ASCENDANT);

        assertNull(b.winner());
        assertNull(b.winningLine());
        assertTrue(b.isDraw());
        assertTrue(b.isGameOver());
        assertEquals(0, b.emptyCells().size());
    }

    @Test
    void winningBoardIsNotADrawEvenWhenFull() {
        // Full board that also contains a win must report winner, not draw.
        Board b = new Board();
        b.tryPlace(0, 0, Player.ASCENDANT);
        b.tryPlace(0, 1, Player.ASCENDANT);
        b.tryPlace(0, 2, Player.ASCENDANT); // top row win
        b.tryPlace(1, 0, Player.VANGUARD);
        b.tryPlace(1, 1, Player.VANGUARD);
        b.tryPlace(1, 2, Player.ASCENDANT);
        b.tryPlace(2, 0, Player.ASCENDANT);
        b.tryPlace(2, 1, Player.VANGUARD);
        b.tryPlace(2, 2, Player.VANGUARD);
        assertEquals(Player.ASCENDANT, b.winner());
        assertFalse(b.isDraw());
    }

    // ── copy() and other() ──────────────────────────────────────────────────

    @Test
    void copyIsIndependentOfOriginal() {
        Board original = new Board();
        original.tryPlace(0, 0, Player.ASCENDANT);
        Board snapshot = original.copy();

        original.tryPlace(1, 1, Player.VANGUARD); // mutate original after copy

        assertEquals(Player.ASCENDANT, snapshot.at(0, 0));
        assertEquals(Player.NONE, snapshot.at(1, 1)); // copy unaffected
    }

    @Test
    void otherFlipsFactionsAndIsNoneForNone() {
        assertEquals(Player.VANGUARD, Board.other(Player.ASCENDANT));
        assertEquals(Player.ASCENDANT, Board.other(Player.VANGUARD));
        assertEquals(Player.NONE, Board.other(Player.NONE));
    }
}
