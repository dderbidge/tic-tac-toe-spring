package com.derbysquad.tictactoe.console;

import com.derbysquad.tictactoe.domain.Player;
import com.derbysquad.tictactoe.domain.ThemeText;

/**
 * ANSI coloring for the terminal UI. Port of the C# {@code ConsolePalette}
 * ({@code ../legacy-csharp/console/ConsolePalette.cs}).
 *
 * <p>The C# version used a {@code static bool UseColor}; here it is an instance
 * field so color can be toggled per renderer (and forced off in tests) without
 * global state.
 */
public final class ConsolePalette {

    public static final String ANSI_RESET = "[0m";
    public static final String ANSI_GOLD = "[33m";
    public static final String ANSI_CRIMSON = "[31m";
    public static final String ANSI_DIM = "[2m";
    public static final String ANSI_BOLD = "[1m";

    private final boolean useColor;

    public ConsolePalette(boolean useColor) {
        this.useColor = useColor;
    }

    public String color(String ansi, String text) {
        return useColor ? ansi + text + ANSI_RESET : text;
    }

    public String coloredSymbolOf(Player p) {
        return switch (p) {
            case ASCENDANT -> color(ANSI_GOLD, ThemeText.SUN);
            case VANGUARD -> color(ANSI_CRIMSON, ThemeText.SPARKLE);
            default -> ThemeText.symbolOf(p);
        };
    }

    public String coloredNameOf(Player p) {
        return switch (p) {
            case ASCENDANT -> color(ANSI_GOLD, ThemeText.ASCENDANT_NAME);
            case VANGUARD -> color(ANSI_CRIMSON, ThemeText.VANGUARD_NAME);
            default -> ThemeText.nameOf(p);
        };
    }

    public String placementMessage(Player p, String sector) {
        return ThemeText.placementMessageFor(coloredNameOf(p), sector);
    }

    public String winMessage(Player p) {
        return ThemeText.winMessageFor(coloredNameOf(p));
    }
}
