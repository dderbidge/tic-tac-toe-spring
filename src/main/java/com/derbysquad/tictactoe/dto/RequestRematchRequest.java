package com.derbysquad.tictactoe.dto;

/**
 * Client → server. Port of {@code GameHub.RequestRematch(string matchId)}.
 * Destination: {@code /app/match/rematch}.
 *
 * <p>Like the C# original this is a forward-compat no-op in v1 (clients return to the
 * lobby and re-challenge), but the typed message is defined for completeness.
 */
public record RequestRematchRequest(String matchId) {
}
