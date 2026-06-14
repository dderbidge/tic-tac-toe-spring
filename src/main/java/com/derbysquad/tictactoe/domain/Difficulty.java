package com.derbysquad.tictactoe.domain;

/**
 * AI difficulty tiers. Port of the C# {@code Difficulty} enum in {@code core/Ai.cs}.
 *
 * <ul>
 *   <li>{@link #RECRUIT} — random legal move.</li>
 *   <li>{@link #CAPTAIN} — one-ply tactical: take a win, else block, else random.</li>
 *   <li>{@link #WARLORD} — full minimax: never loses.</li>
 * </ul>
 */
public enum Difficulty {
    RECRUIT,
    CAPTAIN,
    WARLORD
}
