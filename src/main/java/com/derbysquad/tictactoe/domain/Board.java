package com.derbysquad.tictactoe.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The 3x3 game grid and all win/draw rules.
 *
 * <p>Direct port of the C# {@code sealed class Board} in {@code core/Board.cs}.
 * The C# type is <em>mutable</em> ({@code TryPlace} mutates the grid), so this is
 * a plain class, not a {@code record}.
 *
 * <p><b>Behavioral fidelity notes:</b>
 * <ul>
 *   <li>{@link #winner()} returns {@code null} when there is no winner, mirroring
 *       the C# {@code Player?} return. (The C# code deliberately used nullable
 *       rather than {@code Player.None}; downstream layers serialize a null winner
 *       as "no winner", so this is preserved.)</li>
 *   <li>Java enum arrays default to {@code null}, whereas C# {@code Player[,]}
 *       defaults to {@code Player.None}. The constructor explicitly fills the grid
 *       with {@link Player#NONE} to match C# semantics.</li>
 * </ul>
 */
public final class Board {

    private final Player[][] cells = new Player[3][3];

    public Board() {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                cells[r][c] = Player.NONE;
            }
        }
    }

    public Player at(int row, int col) {
        return cells[row][col];
    }

    /**
     * Places {@code p} at the cell if it is in bounds, non-empty marker, and the
     * cell is unclaimed. Returns whether the placement happened. Mirrors C#
     * {@code TryPlace} exactly, including the out-of-bounds and {@code None} guards.
     */
    public boolean tryPlace(int row, int col, Player p) {
        if (row < 0 || row >= 3 || col < 0 || col >= 3) return false;
        if (p == Player.NONE) return false;
        if (cells[row][col] != Player.NONE) return false;
        cells[row][col] = p;
        return true;
    }

    /** @return the winning faction, or {@code null} if there is none. */
    public Player winner() {
        for (int r = 0; r < 3; r++) {
            if (cells[r][0] != Player.NONE
                    && cells[r][0] == cells[r][1]
                    && cells[r][1] == cells[r][2]) {
                return cells[r][0];
            }
        }
        for (int c = 0; c < 3; c++) {
            if (cells[0][c] != Player.NONE
                    && cells[0][c] == cells[1][c]
                    && cells[1][c] == cells[2][c]) {
                return cells[0][c];
            }
        }
        if (cells[0][0] != Player.NONE
                && cells[0][0] == cells[1][1]
                && cells[1][1] == cells[2][2]) {
            return cells[0][0];
        }
        if (cells[0][2] != Player.NONE
                && cells[0][2] == cells[1][1]
                && cells[1][1] == cells[2][0]) {
            return cells[0][2];
        }
        return null;
    }

    public boolean isDraw() {
        if (winner() != null) return false;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (cells[r][c] == Player.NONE) return false;
            }
        }
        return true;
    }

    public boolean isGameOver() {
        return winner() != null || isDraw();
    }

    public List<Move> emptyCells() {
        List<Move> result = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (cells[r][c] == Player.NONE) {
                    result.add(new Move(r, c));
                }
            }
        }
        return result;
    }

    /** Deep copy. Port of C# {@code Clone()} (renamed to avoid {@code Cloneable} baggage). */
    public Board copy() {
        Board other = new Board();
        for (int r = 0; r < 3; r++) {
            System.arraycopy(cells[r], 0, other.cells[r], 0, 3);
        }
        return other;
    }

    /**
     * The three cells of the winning line, or {@code null} if there is no winner.
     *
     * <p>Lifted into the domain per the migration plan: the C# code duplicated this
     * "ComputeWinningCells" logic across {@code web/Services/GameSession.cs},
     * {@code MatchRegistry.cs}, and {@code LocalGameSession.cs}. It lives here once.
     */
    public List<Move> winningLine() {
        for (int r = 0; r < 3; r++) {
            if (cells[r][0] != Player.NONE
                    && cells[r][0] == cells[r][1]
                    && cells[r][1] == cells[r][2]) {
                return List.of(new Move(r, 0), new Move(r, 1), new Move(r, 2));
            }
        }
        for (int c = 0; c < 3; c++) {
            if (cells[0][c] != Player.NONE
                    && cells[0][c] == cells[1][c]
                    && cells[1][c] == cells[2][c]) {
                return List.of(new Move(0, c), new Move(1, c), new Move(2, c));
            }
        }
        if (cells[0][0] != Player.NONE
                && cells[0][0] == cells[1][1]
                && cells[1][1] == cells[2][2]) {
            return List.of(new Move(0, 0), new Move(1, 1), new Move(2, 2));
        }
        if (cells[0][2] != Player.NONE
                && cells[0][2] == cells[1][1]
                && cells[1][1] == cells[2][0]) {
            return List.of(new Move(0, 2), new Move(1, 1), new Move(2, 0));
        }
        return null;
    }

    /** The opposing faction. Port of C# static {@code Board.Other}. */
    public static Player other(Player p) {
        return switch (p) {
            case ASCENDANT -> Player.VANGUARD;
            case VANGUARD -> Player.ASCENDANT;
            default -> Player.NONE;
        };
    }
}
