package com.derbysquad.tictactoe.dto;

import java.util.List;

/**
 * Server → both players. Port of {@code GameHub.BroadcastMatchState}
 * ({@code new { matchId, board, activeFaction, winner, isDraw, winningCells }}).
 * Destination: {@code /user/queue/match-state}.
 *
 * <p>Field notes (wire fidelity with the C# anonymous payload):
 * <ul>
 *   <li>{@code board} — 3x3 of cell ordinals ({@code 0}=None, {@code 1}=Ascendant,
 *       {@code 2}=Vanguard), matching C# {@code SerializeBoard}. <b>Watch out:</b> a
 *       record with an array component uses identity equality for that field — fine for a
 *       serialize-only DTO, but don't rely on {@code equals}.</li>
 *   <li>{@code activeFaction} — {@code "Ascendant"} / {@code "Vanguard"}.</li>
 *   <li>{@code winner} — {@code "Ascendant"} / {@code "Vanguard"} or {@code null} when none.</li>
 *   <li>{@code winningCells} — {@code null} until there is a winner.</li>
 * </ul>
 */
public record MatchStateEvent(
        String matchId,
        int[][] board,
        String activeFaction,
        String winner,
        boolean isDraw,
        List<CellDto> winningCells) {
}
