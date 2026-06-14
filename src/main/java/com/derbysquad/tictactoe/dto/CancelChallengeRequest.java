package com.derbysquad.tictactoe.dto;

/**
 * Client → server. Port of {@code GameHub.CancelChallenge(string challengeId)}.
 * Destination: {@code /app/challenge/cancel}.
 */
public record CancelChallengeRequest(String challengeId) {
}
