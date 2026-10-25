package com.derbysquad.tictactoe.multiplayer;

import com.derbysquad.tictactoe.domain.Board;
import com.derbysquad.tictactoe.domain.Move;
import com.derbysquad.tictactoe.domain.Player;
import com.derbysquad.tictactoe.dto.CellDto;
import com.derbysquad.tictactoe.dto.ChallengeAcceptedEvent;
import com.derbysquad.tictactoe.dto.ChallengeDeclinedEvent;
import com.derbysquad.tictactoe.dto.ChallengeExpiredEvent;
import com.derbysquad.tictactoe.dto.ChallengePlayerRequest;
import com.derbysquad.tictactoe.dto.ChallengeSentEvent;
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
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
 *
 * <p>Handlers and expiry callbacks share this controller's monitor. Occupancy validation,
 * registry mutation, presence changes, and queued snapshots form one transition; two
 * simultaneous challenge accepts cannot put a player in two matches.
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
    public synchronized void enterLobby(@Header("simpSessionId") String connId) {
        if (presence.currentMatchOf(connId) != null) return;
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
    public synchronized void leaveLobby(@Header("simpSessionId") String connId) {
        if (presence.currentMatchOf(connId) != null) return;
        presence.remove(connId);
        challenges.removeAllInvolving(connId);
        messaging.convertAndSend(LOBBY_LEFT, new PlayerLeftEvent(connId));
    }

    // ─── Challenges ────────────────────────────────────────────────

    @MessageMapping("/challenge/send")
    public synchronized void challengePlayer(@Header("simpSessionId") String connId, @Payload ChallengePlayerRequest req) {
        if (req == null || connId.equals(req.targetConnId())) return;
        PlayerSession from = presence.get(connId);
        PlayerSession to = presence.get(req.targetConnId());
        if (from == null || to == null) return;
        if (presence.currentMatchOf(connId) != null) return;
        if (presence.currentMatchOf(req.targetConnId()) != null) return;

        challenges.removeAllInvolving(connId);
        Challenge c = challenges.create(connId, from.name(), req.targetConnId(), to.name());
        sendToUser(connId, "/queue/challenge-sent",
                new ChallengeSentEvent(c.challengeId(), c.toConnId(), c.toName()));
        sendToUser(req.targetConnId(), "/queue/challenge-incoming",
                new IncomingChallengeEvent(c.challengeId(), connId, from.name()));
    }

    @MessageMapping("/challenge/respond")
    public synchronized void respondToChallenge(@Header("simpSessionId") String connId, @Payload RespondToChallengeRequest req) {
        if (req == null) return;
        Challenge c = challenges.take(req.challengeId(), connId);
        if (c == null) return;

        if (!req.accept()) {
            sendToUser(c.fromConnId(), "/queue/challenge-declined", new ChallengeDeclinedEvent(c.challengeId(), c.toName()));
            return;
        }

        if (presence.get(c.fromConnId()) == null || presence.get(c.toConnId()) == null
                || presence.currentMatchOf(c.fromConnId()) != null || presence.currentMatchOf(c.toConnId()) != null) {
            onChallengeExpired(new ChallengeExpiredAppEvent(c));
            return;
        }
        challenges.removeAllInvolving(c.fromConnId());
        challenges.removeAllInvolving(c.toConnId());
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
    public synchronized void cancelChallenge(@Header("simpSessionId") String connId, @Payload com.derbysquad.tictactoe.dto.CancelChallengeRequest req) {
        if (req != null) challenges.cancel(req.challengeId(), connId);
    }

    // ─── Match ─────────────────────────────────────────────────────

    @MessageMapping("/match/move")
    public synchronized void placeMove(@Header("simpSessionId") String connId, @Payload PlaceMoveRequest req) {
        if (req == null) return;
        MultiplayerMatch current = matches.get(req.matchId());
        if (current == null || !MatchRegistry.isParticipant(current, connId)) return;
        if (req.row() == null || req.col() == null) {
            sendMatchState(current, connId);
            return;
        }
        PlaceResult result = matches.tryPlace(req.matchId(), connId, req.row(), req.col());
        if (!result.accepted() || result.match() == null) {
            sendMatchState(current, connId);
            return;
        }

        broadcastMatchState(result.match());

        if (result.match().winner() != null || result.match().isDraw()) {
            broadcastMatchEnded(result.match(), result.match().isDraw() ? "draw" : "win");
            endMatchAndReturnPlayersToLobby(result.match());
        }
    }

    @MessageMapping("/match/forfeit")
    public synchronized void forfeitMatch(@Header("simpSessionId") String connId, @Payload com.derbysquad.tictactoe.dto.ForfeitMatchRequest req) {
        if (req == null) return;
        MultiplayerMatch m = matches.forfeit(req.matchId(), connId);
        if (m == null) return;
        broadcastMatchEnded(m, "forfeit");
        endMatchAndReturnPlayersToLobby(m);
    }

    @MessageMapping("/match/rematch")
    public synchronized void requestRematch(@Payload com.derbysquad.tictactoe.dto.RequestRematchRequest req) {
        // v1 no-op (clients return to lobby and re-challenge); mirrors the C# stub.
    }

    @MessageMapping("/match/rejoin")
    public synchronized void rejoinMatch(@Header("simpSessionId") String connId, @Payload RejoinMatchRequest req) {
        if (req == null) return;
        var restored = matches.tryRejoin(req.matchId(), connId, req.token());
        if (restored == null) return;
        MultiplayerMatch m = restored.match();
        Player faction = restored.faction();
        String name = faction == Player.ASCENDANT ? m.ascendantName() : m.vanguardName();
        presence.restoreMatch(connId, name, m.matchId());
        challenges.removeAllInvolving(connId);
        messaging.convertAndSend(LOBBY_LEFT, new PlayerLeftEvent(connId));
        String partner = faction == Player.ASCENDANT ? m.vanguardConnId() : m.ascendantConnId();
        if (restored.restored()) {
            sendToUser(partner, "/queue/opponent-reconnected", new OpponentReconnectedEvent(m.matchId()));
        }
        // Restore the rejoining client's context (faction/opponent/token), then the board.
        String opponentName = faction == Player.ASCENDANT ? m.vanguardName() : m.ascendantName();
        sendToUser(connId, "/queue/challenge-accepted",
                new ChallengeAcceptedEvent(m.matchId(), opponentName, factionName(faction), m.tokenFor(faction)));
        broadcastMatchState(m);
        int remaining = matches.remainingGraceSeconds(m, partner);
        if (remaining > 0) sendToUser(connId, "/queue/opponent-disconnected", new OpponentDisconnectedEvent(m.matchId(), remaining));
    }

    // ─── Lifecycle (was OnDisconnectedAsync) ───────────────────────

    @EventListener
    public synchronized void onSessionDisconnect(SessionDisconnectEvent event) {
        String connId = event.getSessionId();

        String matchId = presence.currentMatchOf(connId);
        if (matchId != null) {
            MultiplayerMatch m = matches.get(matchId);
            if (m != null && matches.markDisconnected(matchId, connId,
                    Duration.ofSeconds(disconnectGraceSeconds), taskScheduler, this::onGraceExpired)) {
                String partner = m.ascendantConnId().equals(connId) ? m.vanguardConnId() : m.ascendantConnId();
                sendToUser(partner, "/queue/opponent-disconnected",
                        new OpponentDisconnectedEvent(matchId, disconnectGraceSeconds));
            }
        }
        presence.remove(connId);
        challenges.removeAllInvolving(connId);
        localGames.remove(connId);
        messaging.convertAndSend(LOBBY_LEFT, new PlayerLeftEvent(connId));
    }

    // ─── Challenge-expiry bridge (was the Program.cs broker.Expired wiring) ───

    @EventListener
    public synchronized void onChallengeExpired(ChallengeExpiredAppEvent event) {
        Challenge c = event.challenge();
        sendToUser(c.fromConnId(), "/queue/challenge-expired", new ChallengeExpiredEvent(c.challengeId(), "from"));
        sendToUser(c.toConnId(), "/queue/challenge-expired", new ChallengeExpiredEvent(c.challengeId(), "to"));
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private void broadcastMatchState(MultiplayerMatch m) {
        sendMatchState(m, m.ascendantConnId());
        sendMatchState(m, m.vanguardConnId());
    }

    private void sendMatchState(MultiplayerMatch m, String connId) {
        MatchStateEvent payload = new MatchStateEvent(
                m.matchId(),
                serializeBoard(m.board()),
                factionName(m.active()),
                factionName(m.winner()),
                m.isDraw(),
                toCellDtos(m.winningCells()));
        sendToUser(connId, "/queue/match-state", payload);
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

    private synchronized void onGraceExpired(String matchId, String disconnectedConnId, UUID leaseId) {
        MultiplayerMatch m = matches.forfeitDisconnected(matchId, disconnectedConnId, leaseId);
        if (m == null) return;
        broadcastMatchEnded(m, "forfeit");
        endMatchAndReturnPlayersToLobby(m);
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
