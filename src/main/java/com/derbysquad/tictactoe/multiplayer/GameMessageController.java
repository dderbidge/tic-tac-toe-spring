package com.derbysquad.tictactoe.multiplayer;

import com.derbysquad.tictactoe.domain.Board;
import com.derbysquad.tictactoe.domain.Move;
import com.derbysquad.tictactoe.domain.Player;
import com.derbysquad.tictactoe.dto.CellDto;
import com.derbysquad.tictactoe.dto.ChallengeAcceptedEvent;
import com.derbysquad.tictactoe.dto.ChallengeDeclinedEvent;
import com.derbysquad.tictactoe.dto.ChallengeExpiredEvent;
import com.derbysquad.tictactoe.dto.ChallengePlayerRequest;
import com.derbysquad.tictactoe.dto.IncomingChallengeEvent;
import com.derbysquad.tictactoe.dto.LobbyStateEvent;
import com.derbysquad.tictactoe.dto.MatchEndedEvent;
import com.derbysquad.tictactoe.dto.MatchStateEvent;
import com.derbysquad.tictactoe.dto.OpponentDisconnectedEvent;
import com.derbysquad.tictactoe.dto.OpponentReconnectedEvent;
import com.derbysquad.tictactoe.dto.PlaceMoveRequest;
import com.derbysquad.tictactoe.dto.PlayerDto;
import com.derbysquad.tictactoe.dto.PlayerJoinedEvent;
import com.derbysquad.tictactoe.dto.PlayerLeftEvent;
import com.derbysquad.tictactoe.dto.RejoinMatchRequest;
import com.derbysquad.tictactoe.dto.RespondToChallengeRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * STOMP port of the C# {@code GameHub} ({@code ../legacy-csharp/web-multiplayer/Hubs/GameHub.cs}).
 *
 * <p>Each SignalR callable method becomes a {@code @MessageMapping}; each
 * {@code Clients.*.SendAsync(...)} becomes a {@link SimpMessagingTemplate} push using the
 * Task-4 DTOs. Identity is the STOMP session id (no auth), read from the
 * {@code simpSessionId} header — the analog of {@code Context.ConnectionId}.
 *
 * <p>Lifecycle that SignalR put in {@code OnDisconnectedAsync} is handled via
 * {@link SessionDisconnectEvent}; the challenge-expiry bridge that C# wired in
 * {@code Program.cs} is an {@code @EventListener} for {@link ChallengeExpiredAppEvent}.
 */
@Controller
public class GameMessageController {

    private static final String LOBBY_JOINED = "/topic/lobby/joined";
    private static final String LOBBY_LEFT = "/topic/lobby/left";

    private final PresenceTracker presence;
    private final MatchRegistry matches;
    private final ChallengeBroker challenges;
    private final LocalGameService localGames;
    private final SimpMessagingTemplate messaging;
    private final TaskScheduler taskScheduler;
    private final int disconnectGraceSeconds;

    public GameMessageController(PresenceTracker presence, MatchRegistry matches,
                                 ChallengeBroker challenges, LocalGameService localGames,
                                 SimpMessagingTemplate messaging, TaskScheduler taskScheduler,
                                 @org.springframework.beans.factory.annotation.Value("${tictactoe.disconnect-grace-seconds:30}") int disconnectGraceSeconds) {
        this.presence = presence;
        this.matches = matches;
        this.challenges = challenges;
        this.localGames = localGames;
        this.messaging = messaging;
        this.taskScheduler = taskScheduler;
        this.disconnectGraceSeconds = disconnectGraceSeconds;
    }

    // ─── Lobby ─────────────────────────────────────────────────────

    @MessageMapping("/lobby/enter")
    public void enterLobby(@Header("simpSessionId") String connId) {
        if (presence.get(connId) == null) {
            presence.add(connId);
        }
        PlayerSession me = presence.get(connId);
        List<PlayerDto> players = presence.snapshotLobby().stream()
                .map(s -> new PlayerDto(s.connectionId(), s.name()))
                .toList();
        sendToUser(connId, "/queue/lobby-state", new LobbyStateEvent(connId, me.name(), players));
        // No SignalR "OthersInGroup": broadcast to all lobby subscribers; clients ignore their own connId.
        messaging.convertAndSend(LOBBY_JOINED, new PlayerJoinedEvent(connId, me.name()));
    }

    @MessageMapping("/lobby/leave")
    public void leaveLobby(@Header("simpSessionId") String connId) {
        presence.remove(connId);
        challenges.removeAllInvolving(connId);
        messaging.convertAndSend(LOBBY_LEFT, new PlayerLeftEvent(connId));
    }

    // ─── Challenges ────────────────────────────────────────────────

    @MessageMapping("/challenge/send")
    public void challengePlayer(@Header("simpSessionId") String connId, @Payload ChallengePlayerRequest req) {
        PlayerSession from = presence.get(connId);
        PlayerSession to = presence.get(req.targetConnId());
        if (from == null || to == null) return;
        if (presence.currentMatchOf(connId) != null) return;
        if (presence.currentMatchOf(req.targetConnId()) != null) return;

        Challenge c = challenges.create(connId, from.name(), req.targetConnId(), to.name());
        sendToUser(req.targetConnId(), "/queue/challenge-incoming",
                new IncomingChallengeEvent(c.challengeId(), connId, from.name()));
    }

    @MessageMapping("/challenge/respond")
    public void respondToChallenge(@Header("simpSessionId") String connId, @Payload RespondToChallengeRequest req) {
        Challenge c = challenges.take(req.challengeId());
        if (c == null) return;
        if (!c.toConnId().equals(connId)) return;

        if (!req.accept()) {
            sendToUser(c.fromConnId(), "/queue/challenge-declined", new ChallengeDeclinedEvent(c.toName()));
            return;
        }

        MultiplayerMatch match = matches.create(c.fromConnId(), c.fromName(), c.toConnId(), c.toName());
        presence.enterMatch(c.fromConnId(), match.matchId());
        presence.enterMatch(c.toConnId(), match.matchId());
        messaging.convertAndSend(LOBBY_LEFT, new PlayerLeftEvent(c.fromConnId()));
        messaging.convertAndSend(LOBBY_LEFT, new PlayerLeftEvent(c.toConnId()));

        sendToUser(c.fromConnId(), "/queue/challenge-accepted",
                new ChallengeAcceptedEvent(match.matchId(), c.toName(), "Ascendant", match.tokenFor(Player.ASCENDANT)));
        sendToUser(c.toConnId(), "/queue/challenge-accepted",
                new ChallengeAcceptedEvent(match.matchId(), c.fromName(), "Vanguard", match.tokenFor(Player.VANGUARD)));

        broadcastMatchState(match);
    }

    @MessageMapping("/challenge/cancel")
    public void cancelChallenge(@Header("simpSessionId") String connId, @Payload com.derbysquad.tictactoe.dto.CancelChallengeRequest req) {
        challenges.cancel(req.challengeId(), connId);
    }

    // ─── Match ─────────────────────────────────────────────────────

    @MessageMapping("/match/move")
    public void placeMove(@Header("simpSessionId") String connId, @Payload PlaceMoveRequest req) {
        PlaceResult result = matches.tryPlace(req.matchId(), connId, req.row(), req.col());
        if (!result.accepted() || result.match() == null) return;

        broadcastMatchState(result.match());

        if (result.match().winner() != null || result.match().isDraw()) {
            broadcastMatchEnded(result.match(), result.match().isDraw() ? "draw" : "win");
            endMatchAndReturnPlayersToLobby(result.match());
        }
    }

    @MessageMapping("/match/forfeit")
    public void forfeitMatch(@Header("simpSessionId") String connId, @Payload com.derbysquad.tictactoe.dto.ForfeitMatchRequest req) {
        MultiplayerMatch m = matches.forfeit(req.matchId(), connId);
        if (m == null) return;
        broadcastMatchEnded(m, "forfeit");
        endMatchAndReturnPlayersToLobby(m);
    }

    @MessageMapping("/match/rematch")
    public void requestRematch(@Payload com.derbysquad.tictactoe.dto.RequestRematchRequest req) {
        // v1 no-op (clients return to lobby and re-challenge); mirrors the C# stub.
    }

    @MessageMapping("/match/rejoin")
    public void rejoinMatch(@Header("simpSessionId") String connId, @Payload RejoinMatchRequest req) {
        MultiplayerMatch m = matches.get(req.matchId());
        if (m == null) return; // match gone (e.g. grace expired) — client falls back to the menu
        Player faction = m.factionForToken(req.token());
        if (faction == Player.NONE) return; // bad/missing token — ignore (anti-hijack)

        String oldConnId = faction == Player.ASCENDANT ? m.ascendantConnId() : m.vanguardConnId();
        if (faction == Player.ASCENDANT) {
            m.setAscendantConnId(connId);
        } else {
            m.setVanguardConnId(connId);
        }

        boolean wasDisconnected = oldConnId.equals(m.disconnectedConnId());
        if (wasDisconnected) {
            m.setDisconnectedConnId(null);
            cancelGraceTimer(m);
        }

        presence.enterMatch(connId, m.matchId());
        String partner = faction == Player.ASCENDANT ? m.vanguardConnId() : m.ascendantConnId();
        if (wasDisconnected) {
            sendToUser(partner, "/queue/opponent-reconnected", new OpponentReconnectedEvent());
        }
        // Restore the rejoining client's context (faction/opponent/token), then the board.
        String opponentName = faction == Player.ASCENDANT ? m.vanguardName() : m.ascendantName();
        sendToUser(connId, "/queue/challenge-accepted",
                new ChallengeAcceptedEvent(m.matchId(), opponentName, factionName(faction), m.tokenFor(faction)));
        broadcastMatchState(m);
    }

    // ─── Lifecycle (was OnDisconnectedAsync) ───────────────────────

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        String connId = event.getSessionId();

        String matchId = presence.currentMatchOf(connId);
        if (matchId != null) {
            MultiplayerMatch m = matches.get(matchId);
            if (m != null && m.winner() == null && !m.isDraw()) {
                m.setDisconnectedConnId(connId);
                cancelGraceTimer(m);
                m.setDisconnectTask(taskScheduler.schedule(
                        () -> onGraceExpired(matchId, connId),
                        Instant.now().plusSeconds(disconnectGraceSeconds)));
                String partner = m.ascendantConnId().equals(connId) ? m.vanguardConnId() : m.ascendantConnId();
                sendToUser(partner, "/queue/opponent-disconnected",
                        new OpponentDisconnectedEvent(disconnectGraceSeconds));
            }
        }
        presence.remove(connId);
        challenges.removeAllInvolving(connId);
        localGames.remove(connId);
        messaging.convertAndSend(LOBBY_LEFT, new PlayerLeftEvent(connId));
    }

    // ─── Challenge-expiry bridge (was the Program.cs broker.Expired wiring) ───

    @EventListener
    public void onChallengeExpired(ChallengeExpiredAppEvent event) {
        Challenge c = event.challenge();
        sendToUser(c.fromConnId(), "/queue/challenge-expired", new ChallengeExpiredEvent(c.challengeId(), "from"));
        sendToUser(c.toConnId(), "/queue/challenge-expired", new ChallengeExpiredEvent(c.challengeId(), "to"));
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private void broadcastMatchState(MultiplayerMatch m) {
        MatchStateEvent payload = new MatchStateEvent(
                m.matchId(),
                serializeBoard(m.board()),
                factionName(m.active()),
                factionName(m.winner()),
                m.isDraw(),
                toCellDtos(m.winningCells()));
        sendToUser(m.ascendantConnId(), "/queue/match-state", payload);
        sendToUser(m.vanguardConnId(), "/queue/match-state", payload);
    }

    private void broadcastMatchEnded(MultiplayerMatch m, String reason) {
        List<CellDto> cells = toCellDtos(m.winningCells());
        sendToUser(m.ascendantConnId(), "/queue/match-ended",
                new MatchEndedEvent(m.matchId(), resultFor(m, Player.ASCENDANT), reason, cells));
        sendToUser(m.vanguardConnId(), "/queue/match-ended",
                new MatchEndedEvent(m.matchId(), resultFor(m, Player.VANGUARD), reason, cells));
    }

    private void endMatchAndReturnPlayersToLobby(MultiplayerMatch m) {
        presence.leaveMatch(m.ascendantConnId());
        presence.leaveMatch(m.vanguardConnId());
        matches.remove(m.matchId());
    }

    private void onGraceExpired(String matchId, String disconnectedConnId) {
        MultiplayerMatch m = matches.get(matchId);
        if (m == null || !disconnectedConnId.equals(m.disconnectedConnId())) return;
        m.setWinner(m.ascendantConnId().equals(disconnectedConnId) ? Player.VANGUARD : Player.ASCENDANT);
        String partner = m.ascendantConnId().equals(disconnectedConnId) ? m.vanguardConnId() : m.ascendantConnId();
        sendToUser(partner, "/queue/match-ended", new MatchEndedEvent(m.matchId(), "win", "forfeit", null));
        endMatchAndReturnPlayersToLobby(m);
    }

    private void cancelGraceTimer(MultiplayerMatch m) {
        if (m.disconnectTask() != null) {
            m.disconnectTask().cancel(false);
            m.setDisconnectTask(null);
        }
    }

    /** Result token from a recipient's perspective: win / loss / draw. */
    private static String resultFor(MultiplayerMatch m, Player recipient) {
        Player w = m.winner();
        if (w == recipient) return "win";
        if (w == Board.other(recipient)) return "loss";
        return "draw";
    }

    /** Sends a payload to a single STOMP session (identity = session id, no Principal needed). */
    private void sendToUser(String sessionId, String destination, Object payload) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headerAccessor.setSessionId(sessionId);
        headerAccessor.setLeaveMutable(true);
        messaging.convertAndSendToUser(sessionId, destination, payload, headerAccessor.getMessageHeaders());
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
