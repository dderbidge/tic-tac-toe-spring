package com.derbysquad.tictactoe.dto;

import java.util.List;

/**
 * Server → caller. Port of {@code GameHub} {@code OnLobbyState}
 * ({@code new { yourConnId, yourName, players }}).
 * Destination: {@code /user/queue/lobby-state}.
 */
public record LobbyStateEvent(String yourConnId, String yourName, List<PlayerDto> players) {
}
