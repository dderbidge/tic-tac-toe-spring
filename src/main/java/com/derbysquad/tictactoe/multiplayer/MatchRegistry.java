package com.derbysquad.tictactoe.multiplayer;

import com.derbysquad.tictactoe.domain.Board;
import com.derbysquad.tictactoe.domain.Player;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/**
 * Registry of active networked matches. Port of the C# {@code MatchRegistry}.
 *
 * <p>The C# version guarded everything with one global lock; here storage is a
 * {@link ConcurrentMap} and the compound read-validate-mutate in {@link #tryPlace} /
 * {@link #forfeit} is guarded per-match ({@code synchronized(match)}) — finer-grained but
 * equivalent. The triplicated {@code ComputeWinningCells} is gone: it now lives once in
 * the domain as {@link Board#winningLine()}.
 */
@Service
public class MatchRegistry {

    private final ConcurrentMap<String, MultiplayerMatch> matches = new ConcurrentHashMap<>();

    public MultiplayerMatch create(String ascConnId, String ascName, String vanConnId, String vanName) {
        String matchId = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String ascToken = UUID.randomUUID().toString().replace("-", "");
        String vanToken = UUID.randomUUID().toString().replace("-", "");
        MultiplayerMatch match = new MultiplayerMatch(matchId, ascConnId, ascName, vanConnId, vanName, ascToken, vanToken);
        matches.put(matchId, match);
        return match;
    }

    public MultiplayerMatch get(String matchId) {
        return matches.get(matchId);
    }

    public PlaceResult tryPlace(String matchId, String fromConnId, int row, int col) {
        MultiplayerMatch m = matches.get(matchId);
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
            return new PlaceResult(true, m, null);
        }
    }

    public MultiplayerMatch forfeit(String matchId, String forfeiterConnId) {
        MultiplayerMatch m = matches.get(matchId);
        if (m == null) {
            return null;
        }
        synchronized (m) {
            if (m.winner() != null || m.isDraw()) {
                return m;
            }
            m.setWinner(m.ascendantConnId().equals(forfeiterConnId) ? Player.VANGUARD : Player.ASCENDANT);
            return m;
        }
    }

    public boolean remove(String matchId) {
        return matches.remove(matchId) != null;
    }
}
