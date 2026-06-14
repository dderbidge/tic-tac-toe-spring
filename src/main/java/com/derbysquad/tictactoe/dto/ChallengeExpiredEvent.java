package com.derbysquad.tictactoe.dto;

/**
 * Server → both parties. Port of the {@code OnChallengeExpired} push wired in C#
 * {@code Program.Main} ({@code new { challengeId, role }}).
 * Destination: {@code /user/queue/challenge-expired}.
 *
 * <p>{@code role} is {@code "from"} for the challenger, {@code "to"} for the challenged.
 */
public record ChallengeExpiredEvent(String challengeId, String role) {
}
