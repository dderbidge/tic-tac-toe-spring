package com.derbysquad.tictactoe.dto;

/**
 * Client → server. Port of {@code GameHub.RespondToChallenge(string challengeId, bool accept)}.
 * Destination: {@code /app/challenge/respond}.
 */
public record RespondToChallengeRequest(String challengeId, boolean accept) {
}
