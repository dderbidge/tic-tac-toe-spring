package com.derbysquad.tictactoe.dto;

/**
 * Client → server. Port of {@code GameHub.RejoinMatch(string matchId)}, extended with the
 * server-issued {@code token} (from {@link ChallengeAcceptedEvent#yourToken()}).
 * Destination: {@code /app/match/rejoin}.
 *
 * <p>The token authenticates the rejoin: the server maps it to a faction slot, so a new
 * STOMP session can reclaim a disconnected slot without another client being able to hijack it.
 */
public record RejoinMatchRequest(String matchId, String token) {
}
