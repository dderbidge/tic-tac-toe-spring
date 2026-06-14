package com.derbysquad.tictactoe.dto;

import java.util.List;

/**
 * Server → each player. Port of {@code GameHub.BroadcastMatchEnded} / {@code OnGraceExpired}
 * ({@code new { matchId, result, reason, winningCells }}).
 * Destination: {@code /user/queue/match-ended}.
 *
 * <p>{@code result} is per-recipient: {@code "win"} / {@code "loss"} / {@code "draw"}.
 * {@code reason} is {@code "win"} / {@code "draw"} / {@code "forfeit"}.
 * {@code winningCells} may be {@code null}.
 */
public record MatchEndedEvent(
        String matchId,
        String result,
        String reason,
        List<CellDto> winningCells) {
}
