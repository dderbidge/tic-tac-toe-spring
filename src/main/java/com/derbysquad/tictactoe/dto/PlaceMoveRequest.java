package com.derbysquad.tictactoe.dto;

/**
 * Client → server. Port of {@code GameHub.PlaceMove(string matchId, int row, int col)}.
 * Destination: {@code /app/match/move}.
 */
public record PlaceMoveRequest(String matchId, int row, int col) {
}
