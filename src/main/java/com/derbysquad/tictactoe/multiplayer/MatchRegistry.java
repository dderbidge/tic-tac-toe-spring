package com.derbysquad.tictactoe.multiplayer;

import com.derbysquad.tictactoe.domain.Board;
import com.derbysquad.tictactoe.domain.Player;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.TaskScheduler;

/**
 * Registry of active networked matches. Port of the C# {@code MatchRegistry}.
 *
 * <p>Compound identity, move, and disconnect transitions share the registry monitor,
 * including checks across matches. This follows the C# PR1 {@code MatchRegistry}
 * locking model and prevents a connection occupying two matches. Winning cells live in
 * the domain as {@link Board#winningLine()}.
 */
@Service
public class MatchRegistry {

    private final ConcurrentMap<String, MultiplayerMatch> matches = new ConcurrentHashMap<>();
    private final Clock clock;

    public MatchRegistry() { this(Clock.systemUTC()); }
    MatchRegistry(Clock clock) { this.clock = clock; }

    public synchronized MultiplayerMatch create(String ascConnId, String ascName, String vanConnId, String vanName) {
        String matchId = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String ascToken = UUID.randomUUID().toString().replace("-", "");
        String vanToken = UUID.randomUUID().toString().replace("-", "");
        MultiplayerMatch match = new MultiplayerMatch(matchId, ascConnId, ascName, vanConnId, vanName, ascToken, vanToken);
        matches.put(matchId, match);
        return match;
    }

    public MultiplayerMatch get(String matchId) {
        return matchId == null ? null : matches.get(matchId);
    }

    public synchronized PlaceResult tryPlace(String matchId, String fromConnId, int row, int col) {
        MultiplayerMatch m = get(matchId);
        if (m == null) {
            return new PlaceResult(false, null, "no such match");
        }
        synchronized (m) {
            if (m.winner() != null || m.isDraw()) {
                return new PlaceResult(false, m, "match already ended");
            }
            Player fromFaction =
                    m.ascendantConnId().equals(fromConnId) ? Player.ASCENDANT
                            : m.vanguardConnId().equals(fromConnId) ? Player.VANGUARD
                            : Player.NONE;
            if (fromFaction == Player.NONE) {
                return new PlaceResult(false, m, "not a participant");
            }
            if (m.disconnects.containsKey(fromConnId)) {
                return new PlaceResult(false, m, "player disconnected");
            }
            if (m.active() != fromFaction) {
                return new PlaceResult(false, m, "not your turn");
            }
            if (!m.board().tryPlace(row, col, fromFaction)) {
                return new PlaceResult(false, m, "invalid cell");
            }

            m.setLastActivityAt(Instant.now());

            Player winner = m.board().winner();
            if (winner != null) {
                m.setWinner(winner);
                m.setWinningCells(m.board().winningLine());
            } else if (m.board().isDraw()) {
                m.setDraw(true);
            } else {
                m.setActive(Board.other(m.active()));
            }
            if (m.winner() != null || m.isDraw()) clearDisconnects(m);
            return new PlaceResult(true, m, null);
        }
    }

    public synchronized MultiplayerMatch forfeit(String matchId, String forfeiterConnId) {
        MultiplayerMatch m = get(matchId);
        if (m == null) {
            return null;
        }
        synchronized (m) {
            if (!isParticipant(m, forfeiterConnId)) return null;
            if (m.winner() != null || m.isDraw()) {
                return null;
            }
            m.setWinner(m.ascendantConnId().equals(forfeiterConnId) ? Player.VANGUARD : Player.ASCENDANT);
            clearDisconnects(m);
            return m;
        }
    }

    public synchronized boolean remove(String matchId) {
        if (matchId == null) return false;
        MultiplayerMatch m = matches.remove(matchId);
        if (m == null) return false;
        clearDisconnects(m);
        return true;
    }

    @FunctionalInterface
    public interface GraceExpired { void accept(String matchId, String connectionId, UUID leaseId); }

    public synchronized boolean markDisconnected(String matchId, String connectionId,
            Duration grace, TaskScheduler scheduler, GraceExpired onExpired) {
        MultiplayerMatch m = get(matchId);
        if (m == null || m.winner() != null || m.isDraw() || !isParticipant(m, connectionId)
                || m.disconnects.containsKey(connectionId)) return false;
        UUID id = UUID.randomUUID();
        Instant deadline = clock.instant().plus(grace);
        var task = scheduler.schedule(() -> onExpired.accept(matchId, connectionId, id), deadline);
        m.disconnects.put(connectionId, new MultiplayerMatch.DisconnectLease(id, deadline, task));
        return true;
    }

    public record RejoinResult(MultiplayerMatch match, Player faction, boolean restored) {}

    /** Mirrors C# PR1 TryRejoin: a token can reclaim only its own disconnected seat. */
    public synchronized RejoinResult tryRejoin(String matchId, String connectionId, String token) {
        MultiplayerMatch m = get(matchId);
        if (m == null || connectionId == null || m.winner() != null || m.isDraw()) return null;
        Player faction = m.factionForToken(token);
        if (faction == Player.NONE) return null;
        String oldId = faction == Player.ASCENDANT ? m.ascendantConnId() : m.vanguardConnId();
        String partner = faction == Player.ASCENDANT ? m.vanguardConnId() : m.ascendantConnId();
        if (partner.equals(connectionId) || matches.values().stream().anyMatch(other ->
                other != m && isParticipant(other, connectionId))) return null;
        if (oldId.equals(connectionId) && !m.disconnects.containsKey(oldId)) {
            return new RejoinResult(m, faction, false);
        }
        var lease = m.disconnects.get(oldId);
        if (lease == null || !clock.instant().isBefore(lease.expiresAt())) return null;
        m.disconnects.remove(oldId);
        if (lease.task() != null) lease.task().cancel(false);
        if (faction == Player.ASCENDANT) m.setAscendantConnId(connectionId);
        else m.setVanguardConnId(connectionId);
        return new RejoinResult(m, faction, true);
    }

    public synchronized MultiplayerMatch forfeitDisconnected(String matchId, String connectionId, UUID leaseId) {
        MultiplayerMatch m = get(matchId);
        if (m == null || m.winner() != null || m.isDraw() || !isParticipant(m, connectionId)) return null;
        var lease = m.disconnects.get(connectionId);
        if (lease == null || !lease.id().equals(leaseId) || clock.instant().isBefore(lease.expiresAt())) return null;
        return forfeit(matchId, connectionId);
    }

    public synchronized int remainingGraceSeconds(MultiplayerMatch m, String connectionId) {
        var lease = m.disconnects.get(connectionId);
        if (lease == null) return 0;
        return (int) Math.max(1, (Duration.between(clock.instant(), lease.expiresAt()).toMillis() + 999) / 1000);
    }

    public static boolean isParticipant(MultiplayerMatch m, String connectionId) {
        return m.ascendantConnId().equals(connectionId) || m.vanguardConnId().equals(connectionId);
    }

    private void clearDisconnects(MultiplayerMatch m) {
        for (var lease : m.disconnects.values()) if (lease.task() != null) lease.task().cancel(false);
        m.disconnects.clear();
    }
}
