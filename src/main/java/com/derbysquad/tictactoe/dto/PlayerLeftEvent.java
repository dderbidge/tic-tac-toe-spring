package com.derbysquad.tictactoe.dto;

/**
 * Server → lobby (broadcast). Port of {@code GameHub} {@code OnPlayerLeft}
 * ({@code new { connId }}).
 * Destination: {@code /topic/lobby}.
 */
public record PlayerLeftEvent(String connId) {
}
