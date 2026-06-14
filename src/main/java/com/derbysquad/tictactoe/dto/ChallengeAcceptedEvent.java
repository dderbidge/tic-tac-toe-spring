package com.derbysquad.tictactoe.dto;

/**
 * Server → each participant. Port of {@code GameHub} {@code OnChallengeAccepted}
 * ({@code new { matchId, opponentName, yourFaction }}), extended with {@code yourToken}.
 * Destination: {@code /user/queue/challenge-accepted}.
 *
 * <p>{@code yourFaction} is per-recipient: {@code "Ascendant"} for the challenger,
 * {@code "Vanguard"} for the accepter.
 *
 * <p>{@code yourToken} is a server-issued secret the client stores and presents on
 * {@code /app/match/rejoin} to prove it owns this faction's slot after a reconnect (the
 * STOMP session id changes on reconnect, so it cannot be the identity for rejoin). No
 * SignalR ancestor — added for durable reconnect.
 */
public record ChallengeAcceptedEvent(String matchId, String opponentName, String yourFaction, String yourToken) {
}
