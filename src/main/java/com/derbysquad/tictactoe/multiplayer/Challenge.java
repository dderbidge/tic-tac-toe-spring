package com.derbysquad.tictactoe.multiplayer;

import java.time.Instant;

/**
 * A pending challenge between two players. Port of the C# {@code Challenge} record.
 */
public record Challenge(
        String challengeId,
        String fromConnId,
        String fromName,
        String toConnId,
        String toName,
        Instant expiresAt) {
}
