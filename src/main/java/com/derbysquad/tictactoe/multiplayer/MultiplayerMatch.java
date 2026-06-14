package com.derbysquad.tictactoe.multiplayer;

import com.derbysquad.tictactoe.domain.Board;
import com.derbysquad.tictactoe.domain.Move;
import com.derbysquad.tictactoe.domain.Player;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * Server-side state for one networked match. Port of the C# {@code MultiplayerMatch}
 * (a mutable class). {@code DisconnectTimer} ({@code System.Threading.Timer}) becomes a
 * {@link ScheduledFuture} cancelled on reconnect.
 *
 * <p>Connection ids are mutable so {@code rejoin} can swap a stale id for the new one.
 */
public final class MultiplayerMatch {

    private final String matchId;
    private final String ascendantName;
    private final String vanguardName;
    private final String ascendantToken;
    private final String vanguardToken;
    private final Board board = new Board();

    private String ascendantConnId;
    private String vanguardConnId;
    private Player active = Player.ASCENDANT;
    private Player winner; // null until decided
    private boolean draw;
    private List<Move> winningCells;
    private Instant lastActivityAt = Instant.now();
    private String disconnectedConnId;
    private ScheduledFuture<?> disconnectTask;

    public MultiplayerMatch(String matchId, String ascendantConnId, String ascendantName,
                            String vanguardConnId, String vanguardName,
                            String ascendantToken, String vanguardToken) {
        this.matchId = matchId;
        this.ascendantConnId = ascendantConnId;
        this.ascendantName = ascendantName;
        this.vanguardConnId = vanguardConnId;
        this.vanguardName = vanguardName;
        this.ascendantToken = ascendantToken;
        this.vanguardToken = vanguardToken;
    }

    public String matchId() { return matchId; }
    public String ascendantName() { return ascendantName; }
    public String vanguardName() { return vanguardName; }
    public Board board() { return board; }

    /**
     * The faction that owns {@code token}, or {@code NONE} if it matches neither. Used to
     * authenticate a rejoin so a disconnected slot can't be hijacked by another client.
     */
    public Player factionForToken(String token) {
        if (token == null) return Player.NONE;
        if (token.equals(ascendantToken)) return Player.ASCENDANT;
        if (token.equals(vanguardToken)) return Player.VANGUARD;
        return Player.NONE;
    }

    public String tokenFor(Player faction) {
        return faction == Player.ASCENDANT ? ascendantToken
                : faction == Player.VANGUARD ? vanguardToken : null;
    }

    public String ascendantConnId() { return ascendantConnId; }
    public void setAscendantConnId(String v) { this.ascendantConnId = v; }

    public String vanguardConnId() { return vanguardConnId; }
    public void setVanguardConnId(String v) { this.vanguardConnId = v; }

    public Player active() { return active; }
    public void setActive(Player v) { this.active = v; }

    public Player winner() { return winner; }
    public void setWinner(Player v) { this.winner = v; }

    public boolean isDraw() { return draw; }
    public void setDraw(boolean v) { this.draw = v; }

    public List<Move> winningCells() { return winningCells; }
    public void setWinningCells(List<Move> v) { this.winningCells = v; }

    public Instant lastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(Instant v) { this.lastActivityAt = v; }

    public String disconnectedConnId() { return disconnectedConnId; }
    public void setDisconnectedConnId(String v) { this.disconnectedConnId = v; }

    public ScheduledFuture<?> disconnectTask() { return disconnectTask; }
    public void setDisconnectTask(ScheduledFuture<?> v) { this.disconnectTask = v; }
}
