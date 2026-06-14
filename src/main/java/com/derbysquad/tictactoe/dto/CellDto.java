package com.derbysquad.tictactoe.dto;

/**
 * A board coordinate on the wire. Shared nested DTO used by {@link MatchStateEvent}
 * and {@link MatchEndedEvent} for the winning line.
 *
 * <p>Mirrors the C# anonymous {@code new { c.row, c.col }} payload. Kept separate from
 * the domain {@code Move} so the wire contract does not leak the domain type.
 */
public record CellDto(int row, int col) {
}
