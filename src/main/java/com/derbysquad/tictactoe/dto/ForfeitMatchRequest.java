package com.derbysquad.tictactoe.dto;

/**
 * Client → server. Port of {@code GameHub.ForfeitMatch(string matchId)}.
 * Destination: {@code /app/match/forfeit}.
 */
public record ForfeitMatchRequest(String matchId) {
}
