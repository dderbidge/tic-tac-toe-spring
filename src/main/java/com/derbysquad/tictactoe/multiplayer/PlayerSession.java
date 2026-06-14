package com.derbysquad.tictactoe.multiplayer;

import java.time.Instant;

/**
 * A connected player's lobby identity. Port of the C# {@code PlayerSession} record.
 * {@code connectionId} is the STOMP session id.
 */
public record PlayerSession(String connectionId, String name, Instant connectedAt) {
}
