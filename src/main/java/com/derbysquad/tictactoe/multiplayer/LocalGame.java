package com.derbysquad.tictactoe.multiplayer;

import com.derbysquad.tictactoe.domain.Ai;
import com.derbysquad.tictactoe.domain.Board;
import com.derbysquad.tictactoe.domain.Difficulty;
import com.derbysquad.tictactoe.domain.Move;
import com.derbysquad.tictactoe.domain.Player;
import java.util.List;

/**
 * One player's local (non-network) game: hot-seat or vs-CPU. Port of the game logic in
 * the C# {@code LocalGameSession}
 * ({@code ../legacy-csharp/web-multiplayer/Services/LocalGameSession.cs}).
 *
 * <p>The Blazor-specific bits are intentionally dropped: there is no {@code OnChange}
 * event, no {@code AiThinking} flag, and no {@code Task.Delay} "deliberation" — those were
 * UI concerns. The AI move is applied synchronously; callers read state afterward. The
 * shared {@link Ai} is injected.
 */
public final class LocalGame {

    public enum Phase { MAIN_MENU, MODE_SETUP, IN_MATCH, MATCH_OVER }

    private final Ai ai;

    private Phase phase = Phase.MAIN_MENU;
    private Board board = new Board();
    private Player active = Player.ASCENDANT;
    private boolean versusComputer;
    private Player humanSide = Player.ASCENDANT;
    private Difficulty aiDifficulty = Difficulty.RECRUIT;

    private int ascendantWins;
    private int vanguardWins;
    private int draws;

    private Player winner;
    private boolean isDraw;
    private List<Move> winningCells;

    public LocalGame(Ai ai) {
        this.ai = ai;
    }

    public void startHotSeat() {
        versusComputer = false;
        beginMatch();
    }

    public void goToModeSetup() {
        phase = Phase.MODE_SETUP;
    }

    public void startVersusComputer(Player humanSide, Difficulty difficulty) {
        this.versusComputer = true;
        this.humanSide = humanSide;
        this.aiDifficulty = difficulty;
        beginMatch();
        if (active != humanSide) {
            playAiTurn();
        }
    }

    private void beginMatch() {
        board = new Board();
        active = Player.ASCENDANT;
        winner = null;
        isDraw = false;
        winningCells = null;
        phase = Phase.IN_MATCH;
    }

    public void goToMainMenu() {
        phase = Phase.MAIN_MENU;
    }

    public void playAgain() {
        beginMatch();
        if (versusComputer && active != humanSide) {
            playAiTurn();
        }
    }

    public void tryHumanMove(int row, int col) {
        if (phase != Phase.IN_MATCH) return;
        if (versusComputer && active != humanSide) return;
        if (!board.tryPlace(row, col, active)) return;

        if (checkEnd()) return;
        active = Board.other(active);

        if (versusComputer) {
            playAiTurn();
        }
    }

    private void playAiTurn() {
        if (phase != Phase.IN_MATCH) return;
        Move move = ai.chooseMove(board, active, aiDifficulty);
        board.tryPlace(move.row(), move.col(), active);
        if (checkEnd()) return;
        active = Board.other(active);
    }

    public void resetScores() {
        ascendantWins = vanguardWins = draws = 0;
    }

    private boolean checkEnd() {
        Player w = board.winner();
        if (w != null) {
            winner = w;
            winningCells = board.winningLine();
            if (w == Player.ASCENDANT) ascendantWins++;
            else vanguardWins++;
            phase = Phase.MATCH_OVER;
            return true;
        }
        if (board.isDraw()) {
            isDraw = true;
            draws++;
            phase = Phase.MATCH_OVER;
            return true;
        }
        return false;
    }

    // ── Read-only state accessors ────────────────────────────────────────────
    public Phase phase() { return phase; }
    public Board board() { return board; }
    public Player active() { return active; }
    public boolean versusComputer() { return versusComputer; }
    public Player humanSide() { return humanSide; }
    public Difficulty aiDifficulty() { return aiDifficulty; }
    public int ascendantWins() { return ascendantWins; }
    public int vanguardWins() { return vanguardWins; }
    public int draws() { return draws; }
    public Player winner() { return winner; }
    public boolean isDraw() { return isDraw; }
    public List<Move> winningCells() { return winningCells; }
}
