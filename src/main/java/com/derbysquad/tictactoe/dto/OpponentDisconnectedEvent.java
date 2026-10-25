package com.derbysquad.tictactoe.dto;

/**
 * Server → remaining player. Port of {@code GameHub} {@code OnOpponentDisconnected}
 * ({@code new { secondsLeft }}).
 * Destination: {@code /user/queue/opponent-disconnected}.
 */
public record OpponentDisconnectedEvent(String matchId, int secondsLeft) {
}
