package com.derbysquad.tictactoe.dto;

/**
 * Server → remaining player. Port of {@code GameHub} {@code OnOpponentReconnected},
 * now tagged with its match id so a delayed event cannot affect a later match.
 * Destination: {@code /user/queue/opponent-reconnected}.
 *
 */
public record OpponentReconnectedEvent(String matchId) {
}
