package com.derbysquad.tictactoe.console;

import com.derbysquad.tictactoe.domain.Ai;
import com.derbysquad.tictactoe.domain.Board;
import com.derbysquad.tictactoe.domain.Difficulty;
import com.derbysquad.tictactoe.domain.Move;
import com.derbysquad.tictactoe.domain.Player;
import com.derbysquad.tictactoe.domain.ThemeText;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintStream;

/**
 * The console game loop. Port of the C# {@code Program}
 * ({@code ../legacy-csharp/console/Program.cs}).
 *
 * <p>Behavior is reproduced exactly: the same menu structure, the same match flow,
 * the same end-of-stream handling. The AI is an injected {@link Ai} (the C# version
 * called a static {@code Ai} backed by a static RNG); see the migration notes.
 */
public final class GameConsole {

    private final Renderer renderer;
    private final PrintStream err;
    private final Ai ai;

    public GameConsole(Renderer renderer, PrintStream err, Ai ai) {
        this.renderer = renderer;
        this.err = err;
        this.ai = ai;
    }

    /** @return process exit code (0 success / EOF, 1 fatal). Mirrors C# {@code Program.Main}. */
    public int run() {
        try {
            renderer.drawTitle();
            runMainMenu();
            return 0;
        } catch (EOFException e) {
            renderer.announceStreamClosed();
            return 0;
        } catch (IOException e) {
            err.println();
            err.println("  A subspace fault has interrupted the game.");
            err.println("  " + e.getMessage());
            return 1;
        }
    }

    private void runMainMenu() throws IOException {
        while (true) {
            int choice = renderer.promptMenu(
                    "Main Menu",
                    new String[] {"Hot-seat (two humans)", "Versus the computer", "Quit"});

            switch (choice) {
                case 0 -> runHotSeatSession();
                case 1 -> runVersusComputerSession();
                case 2 -> {
                    return;
                }
            }
        }
    }

    private void runHotSeatSession() throws IOException {
        int[] score = {0, 0, 0}; // asc, van, draws
        while (true) {
            MatchResult result = playMatch(Player.ASCENDANT, false, Difficulty.RECRUIT);
            updateScore(result, score);
            renderer.drawScore(score[0], score[1], score[2]);
            if (!renderer.promptYesNo("Play again?")) return;
        }
    }

    private void runVersusComputerSession() throws IOException {
        int factionChoice = renderer.promptMenu(
                "Choose your faction",
                new String[] {
                    ThemeText.ASCENDANT_NAME + " (" + ThemeText.SUN + ") — moves first",
                    ThemeText.VANGUARD_NAME + " (" + ThemeText.SPARKLE + ") — moves second"
                });
        Player human = factionChoice == 0 ? Player.ASCENDANT : Player.VANGUARD;

        int diffChoice = renderer.promptMenu(
                "Choose opponent difficulty",
                new String[] {"Recruit (random)", "Captain (tactical)", "Warlord (unbeatable)"});
        Difficulty diff = Difficulty.values()[diffChoice];

        int[] score = {0, 0, 0};
        while (true) {
            MatchResult result = playMatch(human, true, diff);
            updateScore(result, score);
            renderer.drawScore(score[0], score[1], score[2]);
            if (!renderer.promptYesNo("Play again?")) return;
        }
    }

    private MatchResult playMatch(Player humanSide, boolean opponentIsAi, Difficulty aiDifficulty) throws IOException {
        Board board = new Board();
        Player active = Player.ASCENDANT;

        while (true) {
            renderer.drawBoard(board);

            Move move;
            if (opponentIsAi && active != humanSide) {
                renderer.announce(renderer.coloredNameOf(active)
                        + " (" + ThemeText.difficultyName(aiDifficulty) + ") considers the grid…");
                move = ai.chooseMove(board, active, aiDifficulty);
            } else {
                move = renderer.promptForCoordinate(active, board);
            }

            board.tryPlace(move.row(), move.col(), active);
            renderer.announce(renderer.placementMessage(active, Renderer.sectorLabel(move.row(), move.col())));

            Player winner = board.winner();
            if (winner != null) {
                renderer.drawBoard(board);
                renderer.announce(renderer.winMessage(winner));
                return winner == Player.ASCENDANT ? MatchResult.ASCENDANT_WINS : MatchResult.VANGUARD_WINS;
            }
            if (board.isDraw()) {
                renderer.drawBoard(board);
                renderer.announce(ThemeText.drawMessage());
                return MatchResult.DRAW;
            }

            active = Board.other(active);
        }
    }

    private void updateScore(MatchResult result, int[] score) {
        switch (result) {
            case ASCENDANT_WINS -> score[0]++;
            case VANGUARD_WINS -> score[1]++;
            case DRAW -> score[2]++;
        }
    }

    private enum MatchResult {
        ASCENDANT_WINS, VANGUARD_WINS, DRAW
    }
}
