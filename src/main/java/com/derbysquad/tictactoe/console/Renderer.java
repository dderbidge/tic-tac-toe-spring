package com.derbysquad.tictactoe.console;

import com.derbysquad.tictactoe.domain.Board;
import com.derbysquad.tictactoe.domain.Move;
import com.derbysquad.tictactoe.domain.Player;
import com.derbysquad.tictactoe.domain.ThemeText;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Terminal input/output. Port of the C# {@code Renderer}
 * ({@code ../legacy-csharp/console/Renderer.cs}).
 *
 * <p>Output formatting (the board box, menus, messages) is reproduced verbatim so
 * the smoke-test substrings match the original. The C# {@code Renderer} was static
 * over the global {@code Console}; here the {@link PrintStream} and
 * {@link BufferedReader} are injected, which is what lets the smoke test drive it
 * in-process.
 */
public final class Renderer {

    private final PrintStream out;
    private final BufferedReader in;
    private final ConsolePalette palette;

    public Renderer(PrintStream out, BufferedReader in, ConsolePalette palette) {
        this.out = out;
        this.in = in;
        this.palette = palette;
    }

    public void drawTitle() {
        out.println();
        out.println(palette.color(ConsolePalette.ANSI_BOLD,
                "═══ ORDER VS REBELLION — A SECTOR DUEL ═══"));
        out.println("  " + palette.coloredNameOf(Player.ASCENDANT) + " (" + palette.coloredSymbolOf(Player.ASCENDANT) + ")  "
                + "vs  " + palette.coloredNameOf(Player.VANGUARD) + " (" + palette.coloredSymbolOf(Player.VANGUARD) + ")");
        out.println();
    }

    public void drawBoard(Board board) {
        out.println();
        out.println("       A   B   C");
        out.println("     ┌───┬───┬───┐");
        out.println("   1 │ " + cell(board, 0, 0) + " │ " + cell(board, 0, 1) + " │ " + cell(board, 0, 2) + " │");
        out.println("     ├───┼───┼───┤");
        out.println("   2 │ " + cell(board, 1, 0) + " │ " + cell(board, 1, 1) + " │ " + cell(board, 1, 2) + " │");
        out.println("     ├───┼───┼───┤");
        out.println("   3 │ " + cell(board, 2, 0) + " │ " + cell(board, 2, 1) + " │ " + cell(board, 2, 2) + " │");
        out.println("     └───┴───┴───┘");
        out.println();
    }

    private String cell(Board board, int r, int c) {
        return palette.coloredSymbolOf(board.at(r, c));
    }

    public void drawScore(int ascendantWins, int vanguardWins, int draws) {
        String a = palette.coloredNameOf(Player.ASCENDANT) + " " + palette.coloredSymbolOf(Player.ASCENDANT) + " " + ascendantWins;
        String v = palette.coloredNameOf(Player.VANGUARD) + " " + palette.coloredSymbolOf(Player.VANGUARD) + " " + vanguardWins;
        String d = "Stalemates " + draws;
        out.println("  " + a + "   —   " + v + "   —   " + d);
    }

    public void announce(String message) {
        out.println();
        out.println("  " + message);
        out.println();
    }

    public void error(String message) {
        out.println("  " + palette.color(ConsolePalette.ANSI_CRIMSON, "!") + "  " + message);
    }

    /** The end-of-stream farewell. Mirrors the C# {@code Program.Main} EOF catch (stdout). */
    public void announceStreamClosed() {
        out.println();
        out.println("  Input stream closed. Goodbye, captain.");
    }

    // Pass-throughs to the palette so the game loop need not hold a palette reference.
    public String coloredNameOf(Player p) {
        return palette.coloredNameOf(p);
    }

    public String placementMessage(Player p, String sector) {
        return palette.placementMessage(p, sector);
    }

    public String winMessage(Player p) {
        return palette.winMessage(p);
    }

    /** Reads a line, throwing {@link EOFException} when the stream is closed (mirrors C# {@code EndOfStreamException}). */
    public String readLine(String prompt) throws IOException {
        out.print(prompt);
        String line = in.readLine();
        if (line == null) {
            throw new EOFException("Input stream closed. Quitting.");
        }
        return line;
    }

    /** Parses {@code A1}–{@code C3} into a {@link Move}, or {@code null} if malformed. Mirrors C# {@code ParseCoordinate}. */
    public static Move parseCoordinate(String input) {
        if (input == null || input.isBlank()) return null;

        char letter = '\0';
        char digit = '\0';

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            } else if (Character.isLetter(ch)) {
                if (letter != '\0') return null;
                letter = Character.toUpperCase(ch);
            } else if (Character.isDigit(ch)) {
                if (digit != '\0') return null;
                digit = ch;
            } else {
                return null;
            }
        }

        if (letter < 'A' || letter > 'C') return null;
        if (digit < '1' || digit > '3') return null;

        int col = letter - 'A';
        int row = digit - '1';
        return new Move(row, col);
    }

    public static String sectorLabel(int row, int col) {
        return "" + (char) ('A' + col) + (row + 1);
    }

    public Move promptForCoordinate(Player active, Board board) throws IOException {
        while (true) {
            String line = readLine("  " + palette.coloredNameOf(active) + " — claim a sector (A1–C3): ");
            Move parsed = parseCoordinate(line);
            if (parsed == null) {
                error(ThemeText.invalidCoordMessage());
                continue;
            }
            if (board.at(parsed.row(), parsed.col()) != Player.NONE) {
                error(ThemeText.occupiedMessage(sectorLabel(parsed.row(), parsed.col())));
                continue;
            }
            return parsed;
        }
    }

    public int promptMenu(String title, String[] options) throws IOException {
        while (true) {
            out.println();
            out.println("  " + palette.color(ConsolePalette.ANSI_BOLD, title));
            for (int i = 0; i < options.length; i++) {
                out.println("    " + (i + 1) + ". " + options[i]);
            }

            String line = readLine("  Select: ").trim();
            try {
                int n = Integer.parseInt(line);
                if (n >= 1 && n <= options.length) {
                    return n - 1;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the error prompt
            }
            error("Pick a number from the menu.");
        }
    }

    public boolean promptYesNo(String question) throws IOException {
        while (true) {
            String line = readLine("  " + question + " (y/n): ").trim().toLowerCase();
            if (line.equals("y") || line.equals("yes")) return true;
            if (line.equals("n") || line.equals("no")) return false;
            error("Answer y or n.");
        }
    }
}
