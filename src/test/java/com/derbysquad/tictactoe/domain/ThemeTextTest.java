package com.derbysquad.tictactoe.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ThemeTextTest {

    @Test
    void symbolsAndNamesMatchTheme() {
        assertEquals("☼", ThemeText.symbolOf(Player.ASCENDANT));
        assertEquals("✦", ThemeText.symbolOf(Player.VANGUARD));
        assertEquals(" ", ThemeText.symbolOf(Player.NONE));

        assertEquals("The Ascendant", ThemeText.nameOf(Player.ASCENDANT));
        assertEquals("The Crimson Vanguard", ThemeText.nameOf(Player.VANGUARD));
        assertEquals("Nobody", ThemeText.nameOf(Player.NONE));
    }

    @Test
    void difficultyNames() {
        assertEquals("Recruit", ThemeText.difficultyName(Difficulty.RECRUIT));
        assertEquals("Captain", ThemeText.difficultyName(Difficulty.CAPTAIN));
        assertEquals("Warlord", ThemeText.difficultyName(Difficulty.WARLORD));
    }

    @Test
    void messageTemplates() {
        assertEquals("The Ascendant claims sector A1.",
                ThemeText.placementMessage(Player.ASCENDANT, "A1"));
        assertEquals("The Crimson Vanguard holds the line — three sectors in a row.",
                ThemeText.winMessage(Player.VANGUARD));
        assertEquals("Stalemate. The galaxy holds its breath.", ThemeText.drawMessage());
        assertEquals("Sector B2 is already claimed.", ThemeText.occupiedMessage("B2"));
        assertEquals("Invalid coordinates, captain. Use A1 through C3.", ThemeText.invalidCoordMessage());
    }
}
