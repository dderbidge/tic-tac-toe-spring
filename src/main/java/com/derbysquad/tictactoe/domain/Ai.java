package com.derbysquad.tictactoe.domain;

import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Move selection for the three difficulty tiers. Port of the C# {@code static class Ai}
 * in {@code core/Ai.cs}.
 *
 * <p><b>Key redesign (per the migration plan):</b> the C# version held a {@code static}
 * {@link Random} seeded from the {@code TICTACTOE_SEED} environment variable inside a
 * static initializer. That static global state is removed here. {@code Ai} is an
 * ordinary object that receives its {@link Random} via the constructor, so callers
 * (and tests) control determinism. The seeded-{@code Random} bean is wired up in the
 * {@code config} package; this class stays framework-free.
 *
 * <p>The minimax logic is preserved exactly, including the depth-weighted scoring
 * ({@code 10 - depth}) that makes the AI prefer faster wins and slower losses.
 */
public final class Ai {

    private final Random rng;

    public Ai(Random rng) {
        this.rng = Objects.requireNonNull(rng, "rng");
    }

    public Move chooseMove(Board board, Player self, Difficulty difficulty) {
        return switch (difficulty) {
            case RECRUIT -> chooseRandom(board);
            case CAPTAIN -> chooseCaptain(board, self);
            case WARLORD -> chooseWarlord(board, self);
        };
    }

    private Move chooseRandom(Board board) {
        List<Move> cells = board.emptyCells();
        return cells.get(rng.nextInt(cells.size()));
    }

    private Move chooseCaptain(Board board, Player self) {
        Move winning = findWinningMove(board, self);
        if (winning != null) return winning;
        Move blocking = findWinningMove(board, Board.other(self));
        if (blocking != null) return blocking;
        return chooseRandom(board);
    }

    /** @return a move that immediately wins for {@code p}, or {@code null} if none exists. */
    private Move findWinningMove(Board board, Player p) {
        for (Move m : board.emptyCells()) {
            Board probe = board.copy();
            probe.tryPlace(m.row(), m.col(), p);
            if (probe.winner() == p) {
                return m;
            }
        }
        return null;
    }

    private Move chooseWarlord(Board board, Player self) {
        int bestScore = Integer.MIN_VALUE;
        Move bestMove = null;

        for (Move m : board.emptyCells()) {
            Board probe = board.copy();
            probe.tryPlace(m.row(), m.col(), self);
            int score = minimax(probe, Board.other(self), self, 1);
            if (score > bestScore) {
                bestScore = score;
                bestMove = m;
            }
        }

        return bestMove != null ? bestMove : chooseRandom(board);
    }

    /**
     * Minimax score from {@code self}'s perspective:
     * {@code +(10-depth)} for a self win, {@code -(10-depth)} for an opponent win,
     * {@code 0} for a draw. Subtracting depth makes the AI prefer faster wins and
     * slower losses.
     */
    private int minimax(Board board, Player toMove, Player self, int depth) {
        Player winner = board.winner();
        if (winner == self) return 10 - depth;
        if (winner == Board.other(self)) return depth - 10;
        if (board.isDraw()) return 0;

        boolean maximizing = toMove == self;
        int best = maximizing ? Integer.MIN_VALUE : Integer.MAX_VALUE;

        for (Move m : board.emptyCells()) {
            Board probe = board.copy();
            probe.tryPlace(m.row(), m.col(), toMove);
            int score = minimax(probe, Board.other(toMove), self, depth + 1);
            best = maximizing ? Math.max(best, score) : Math.min(best, score);
        }
        return best;
    }
}
