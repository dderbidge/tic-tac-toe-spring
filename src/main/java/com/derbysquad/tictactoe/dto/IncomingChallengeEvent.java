package com.derbysquad.tictactoe.dto;

/**
 * Server → challenged player. Port of {@code GameHub} {@code OnIncomingChallenge}
 * ({@code new { challengeId, fromConnId, fromName }}).
 * Destination: {@code /user/queue/challenge-incoming}.
 */
public record IncomingChallengeEvent(String challengeId, String fromConnId, String fromName) {
}
