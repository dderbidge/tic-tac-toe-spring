package com.derbysquad.tictactoe.dto;

/**
 * Client → server. Port of {@code GameHub.ChallengePlayer(string targetConnectionId)}.
 * Destination: {@code /app/challenge/send}.
 */
public record ChallengePlayerRequest(String targetConnId) {
}
