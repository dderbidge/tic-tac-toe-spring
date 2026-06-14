package com.derbysquad.tictactoe.dto;

/**
 * Server → remaining player. Port of {@code GameHub} {@code OnOpponentReconnected},
 * which carried <em>no payload</em> in C#.
 * Destination: {@code /user/queue/opponent-reconnected}.
 *
 * <p>Defined as a zero-field record so the messaging layer always sends a typed body
 * (never a raw/Map payload); serializes to {@code {}}.
 */
public record OpponentReconnectedEvent() {
}
