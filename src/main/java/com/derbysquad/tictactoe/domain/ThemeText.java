package com.derbysquad.tictactoe.domain;

/**
 * All user-facing strings, faction names, and sigils. Port of the C#
 * {@code static class ThemeText} in {@code core/ThemeText.cs}.
 *
 * <p>Stateless utility, so it is a {@code final} class with a private constructor
 * rather than a record. Message text is preserved verbatim.
 */
public final class ThemeText {

    private ThemeText() {
    }

    public static final String ASCENDANT_NAME = "The Ascendant";
    public static final String VANGUARD_NAME = "The Crimson Vanguard";

    public static final String SUN = "☼";      // ☼
    public static final String SPARKLE = "✦";  // ✦

    public static String symbolOf(Player p) {
        return switch (p) {
            case ASCENDANT -> SUN;
            case VANGUARD -> SPARKLE;
            default -> " ";
        };
    }

    public static String nameOf(Player p) {
        return switch (p) {
            case ASCENDANT -> ASCENDANT_NAME;
            case VANGUARD -> VANGUARD_NAME;
            default -> "Nobody";
        };
    }

    public static String difficultyName(Difficulty d) {
        return switch (d) {
            case RECRUIT -> "Recruit";
            case CAPTAIN -> "Captain";
            case WARLORD -> "Warlord";
        };
    }

    // Templates that take a pre-rendered name (for both plain and colored callers).
    public static String placementMessageFor(String renderedName, String sector) {
        return renderedName + " claims sector " + sector + ".";
    }

    public static String winMessageFor(String renderedName) {
        return renderedName + " holds the line — three sectors in a row.";
    }

    // Player-keyed convenience overloads using the plain name.
    public static String placementMessage(Player p, String sector) {
        return placementMessageFor(nameOf(p), sector);
    }

    public static String winMessage(Player p) {
        return winMessageFor(nameOf(p));
    }

    public static String drawMessage() {
        return "Stalemate. The galaxy holds its breath.";
    }

    public static String occupiedMessage(String sector) {
        return "Sector " + sector + " is already claimed.";
    }

    public static String invalidCoordMessage() {
        return "Invalid coordinates, captain. Use A1 through C3.";
    }
}
