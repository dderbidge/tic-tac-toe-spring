package com.derbysquad.tictactoe.dto;

/**
 * Server → challenger. Port of {@code GameHub} {@code OnChallengeDeclined}
 * ({@code new { fromName }}).
 * Destination: {@code /user/queue/challenge-declined}.
 *
 * <p>Note: in the C# original {@code fromName} is set to the <em>decliner's</em> name
 * ({@code c.ToName}); the field name is preserved verbatim.
 */
public record ChallengeDeclinedEvent(String fromName) {
}
