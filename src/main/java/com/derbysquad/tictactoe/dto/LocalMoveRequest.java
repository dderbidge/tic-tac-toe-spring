package com.derbysquad.tictactoe.dto;

/**
 * Client → server, <b>local play</b> (new contract). A human move in a hot-seat or vs-CPU
 * game. Destination: {@code /app/local/move}.
 */
public record LocalMoveRequest(Integer row, Integer col) {
}
