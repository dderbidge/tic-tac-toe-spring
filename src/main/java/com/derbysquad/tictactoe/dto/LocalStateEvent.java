package com.derbysquad.tictactoe.dto;

import java.util.List;

/**
 * Server → the owning session, <b>local play</b> (new contract — the legacy local game was
 * driven by in-process Blazor calls, not the hub). A full snapshot of one session's local
 * game, pushed after every local command. Destination: {@code /user/queue/local-state}.
 *
 * <p>{@code phase} is {@code "MainMenu"} / {@code "ModeSetup"} / {@code "InMatch"} /
 * {@code "MatchOver"}; {@code active}/{@code humanSide}/{@code winner} use
 * {@code "Ascendant"}/{@code "Vanguard"} ({@code winner} null until decided);
 * {@code board} is 3x3 cell ordinals; {@code winningCells} null until a win.
 */
public record LocalStateEvent(
        String phase,
        int[][] board,
        String active,
        boolean versusComputer,
        String humanSide,
        String aiDifficulty,
        String winner,
        boolean isDraw,
        List<CellDto> winningCells,
        int ascendantWins,
        int vanguardWins,
        int draws) {
}
