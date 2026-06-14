package com.derbysquad.tictactoe.dto;

/**
 * Client → server, <b>local play</b> (no SignalR ancestor — new contract for the redesign).
 * Destination: {@code /app/local/vs-computer}.
 *
 * <p>{@code faction} is {@code "Ascendant"} or {@code "Vanguard"}; {@code difficulty} is
 * {@code "Recruit"} / {@code "Captain"} / {@code "Warlord"}. Mapping to domain enums happens
 * in the controller.
 */
public record StartVsComputerRequest(String faction, String difficulty) {
}
