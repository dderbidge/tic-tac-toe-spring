package com.derbysquad.tictactoe.dto;

/**
 * Server → lobby (broadcast). Port of {@code GameHub} {@code OnPlayerJoined}
 * ({@code new { connId, name }}).
 * Destination: {@code /topic/lobby}.
 *
 * <p>Legacy used {@code Clients.OthersInGroup}; STOMP has no "others" semantics, so this
 * broadcasts to everyone and the client ignores its own {@code connId}.
 */
public record PlayerJoinedEvent(String connId, String name) {
}
