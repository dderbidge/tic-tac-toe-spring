package com.derbysquad.tictactoe.multiplayer;

import com.derbysquad.tictactoe.domain.Board;
import com.derbysquad.tictactoe.domain.Difficulty;
import com.derbysquad.tictactoe.domain.Move;
import com.derbysquad.tictactoe.domain.Player;
import com.derbysquad.tictactoe.dto.CellDto;
import com.derbysquad.tictactoe.dto.LocalMoveRequest;
import com.derbysquad.tictactoe.dto.LocalStateEvent;
import com.derbysquad.tictactoe.dto.StartVsComputerRequest;
import java.util.List;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * Local (non-network) play over STOMP — a <b>new contract</b> with no SignalR ancestor.
 * The legacy {@code LocalGameSession} was driven by in-process Blazor calls; here the thin
 * JS client drives the session-keyed {@link LocalGameService} through {@code /app/local/*}
 * messages, and the server pushes a full {@link LocalStateEvent} snapshot to
 * {@code /user/queue/local-state} after each command.
 *
 * <p>Identity is the STOMP session id ({@code simpSessionId} header), same as the network hub.
 */
@Controller
public class LocalGameController {

    private final LocalGameService localGames;
    private final SimpMessagingTemplate messaging;

    public LocalGameController(LocalGameService localGames, SimpMessagingTemplate messaging) {
        this.localGames = localGames;
        this.messaging = messaging;
    }

    @MessageMapping("/local/state")
    public void requestState(@Header("simpSessionId") String connId) {
        pushState(connId, localGames.getOrCreate(connId));
    }

    @MessageMapping("/local/hotseat")
    public void startHotSeat(@Header("simpSessionId") String connId) {
        LocalGame game = localGames.getOrCreate(connId);
        game.startHotSeat();
        pushState(connId, game);
    }

    @MessageMapping("/local/setup")
    public void goToModeSetup(@Header("simpSessionId") String connId) {
        LocalGame game = localGames.getOrCreate(connId);
        game.goToModeSetup();
        pushState(connId, game);
    }

    @MessageMapping("/local/vs-computer")
    public void startVsComputer(@Header("simpSessionId") String connId, @Payload StartVsComputerRequest req) {
        LocalGame game = localGames.getOrCreate(connId);
        game.startVersusComputer(parseFaction(req.faction()), parseDifficulty(req.difficulty()));
        pushState(connId, game);
    }

    @MessageMapping("/local/move")
    public void move(@Header("simpSessionId") String connId, @Payload LocalMoveRequest req) {
        LocalGame game = localGames.getOrCreate(connId);
        game.tryHumanMove(req.row(), req.col());
        pushState(connId, game);
    }

    @MessageMapping("/local/play-again")
    public void playAgain(@Header("simpSessionId") String connId) {
        LocalGame game = localGames.getOrCreate(connId);
        game.playAgain();
        pushState(connId, game);
    }

    @MessageMapping("/local/main-menu")
    public void mainMenu(@Header("simpSessionId") String connId) {
        LocalGame game = localGames.getOrCreate(connId);
        game.goToMainMenu();
        pushState(connId, game);
    }

    @MessageMapping("/local/reset")
    public void resetScores(@Header("simpSessionId") String connId) {
        LocalGame game = localGames.getOrCreate(connId);
        game.resetScores();
        pushState(connId, game);
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private void pushState(String connId, LocalGame g) {
        LocalStateEvent payload = new LocalStateEvent(
                phaseName(g.phase()),
                serializeBoard(g.board()),
                factionName(g.active()),
                g.versusComputer(),
                factionName(g.humanSide()),
                g.aiDifficulty().name().charAt(0) + g.aiDifficulty().name().substring(1).toLowerCase(),
                factionName(g.winner()),
                g.isDraw(),
                toCellDtos(g.winningCells()),
                g.ascendantWins(),
                g.vanguardWins(),
                g.draws());
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headerAccessor.setSessionId(connId);
        headerAccessor.setLeaveMutable(true);
        messaging.convertAndSendToUser(connId, "/queue/local-state", payload, headerAccessor.getMessageHeaders());
    }

    private static String phaseName(LocalGame.Phase phase) {
        return switch (phase) {
            case MAIN_MENU -> "MainMenu";
            case MODE_SETUP -> "ModeSetup";
            case IN_MATCH -> "InMatch";
            case MATCH_OVER -> "MatchOver";
        };
    }

    private static Player parseFaction(String f) {
        return "Vanguard".equalsIgnoreCase(f) ? Player.VANGUARD : Player.ASCENDANT;
    }

    private static Difficulty parseDifficulty(String d) {
        if ("Warlord".equalsIgnoreCase(d)) return Difficulty.WARLORD;
        if ("Captain".equalsIgnoreCase(d)) return Difficulty.CAPTAIN;
        return Difficulty.RECRUIT;
    }

    private static String factionName(Player p) {
        if (p == Player.ASCENDANT) return "Ascendant";
        if (p == Player.VANGUARD) return "Vanguard";
        return null;
    }

    private static int[][] serializeBoard(Board b) {
        int[][] rows = new int[3][3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                rows[r][c] = b.at(r, c).ordinal();
            }
        }
        return rows;
    }

    private static List<CellDto> toCellDtos(List<Move> cells) {
        if (cells == null) return null;
        return cells.stream().map(m -> new CellDto(m.row(), m.col())).toList();
    }
}
