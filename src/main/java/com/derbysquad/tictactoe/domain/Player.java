package com.derbysquad.tictactoe.domain;

/**
 * The two factions plus the empty marker.
 *
 * <p>Port of the C# {@code Player} enum in {@code core/Board.cs}. Ordinals are
 * load-bearing and must stay {@code NONE=0, ASCENDANT=1, VANGUARD=2}: the legacy
 * multiplayer wire format serializes a cell as {@code (int)player}, so the
 * eventual STOMP layer depends on these exact values.
 */
public enum Player {
    NONE,
    ASCENDANT,
    VANGUARD
}
