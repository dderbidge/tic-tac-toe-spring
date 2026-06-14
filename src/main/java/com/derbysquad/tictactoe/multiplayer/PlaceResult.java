package com.derbysquad.tictactoe.multiplayer;

/**
 * Outcome of a move attempt. Port of the C# {@code PlaceResult} record.
 * {@code match} is null only when the match id was unknown; {@code reason} is null on success.
 */
public record PlaceResult(boolean accepted, MultiplayerMatch match, String reason) {
}
