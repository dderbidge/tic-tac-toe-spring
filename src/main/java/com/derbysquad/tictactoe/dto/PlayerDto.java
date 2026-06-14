package com.derbysquad.tictactoe.dto;

/**
 * A lobby participant. Shared nested DTO.
 *
 * <p>Normalizes the legacy contract, which was inconsistent: {@code OnLobbyState}
 * carried {@code PlayerSession} ({@code connectionId}, {@code name}, {@code connectedAt})
 * while {@code OnPlayerJoined} sent {@code connId}, {@code name}. We standardize on
 * {@code connId}/{@code name} and drop the unused {@code connectedAt}.
 */
public record PlayerDto(String connId, String name) {
}
