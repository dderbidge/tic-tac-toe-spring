package com.derbysquad.tictactoe.multiplayer;

import static com.derbysquad.tictactoe.multiplayer.StompTestSupport.connect;
import static com.derbysquad.tictactoe.multiplayer.StompTestSupport.poll;
import static com.derbysquad.tictactoe.multiplayer.StompTestSupport.sub;

import com.derbysquad.tictactoe.dto.ChallengeAcceptedEvent;
import com.derbysquad.tictactoe.dto.ChallengeExpiredEvent;
import com.derbysquad.tictactoe.dto.IncomingChallengeEvent;
import com.derbysquad.tictactoe.dto.LobbyStateEvent;
import com.derbysquad.tictactoe.dto.LocalStateEvent;
import com.derbysquad.tictactoe.dto.MatchEndedEvent;
import com.derbysquad.tictactoe.dto.MatchStateEvent;
import com.derbysquad.tictactoe.dto.OpponentDisconnectedEvent;
import com.derbysquad.tictactoe.dto.OpponentReconnectedEvent;
import com.derbysquad.tictactoe.dto.PlayerJoinedEvent;
import java.util.concurrent.BlockingQueue;
import org.springframework.messaging.simp.stomp.StompSession;

/** A test client subscribed to every server destination, for the multiplayer e2e tests. */
final class LobbyClient {

    final StompSession session;
    String connId; // session id (== server simpSessionId); set from lobby-state once in lobby

    final BlockingQueue<LobbyStateEvent> lobbyState;
    final BlockingQueue<PlayerJoinedEvent> joined;
    final BlockingQueue<IncomingChallengeEvent> incoming;
    final BlockingQueue<ChallengeAcceptedEvent> accepted;
    final BlockingQueue<ChallengeExpiredEvent> expired;
    final BlockingQueue<MatchStateEvent> matchState;
    final BlockingQueue<MatchEndedEvent> matchEnded;
    final BlockingQueue<OpponentDisconnectedEvent> oppDisconnected;
    final BlockingQueue<OpponentReconnectedEvent> oppReconnected;
    final BlockingQueue<LocalStateEvent> localState;

    private LobbyClient(StompSession session) {
        this.session = session;
        this.connId = session.getSessionId();
        this.lobbyState = sub(session, "/user/queue/lobby-state", LobbyStateEvent.class);
        this.joined = sub(session, "/topic/lobby/joined", PlayerJoinedEvent.class);
        this.incoming = sub(session, "/user/queue/challenge-incoming", IncomingChallengeEvent.class);
        this.accepted = sub(session, "/user/queue/challenge-accepted", ChallengeAcceptedEvent.class);
        this.expired = sub(session, "/user/queue/challenge-expired", ChallengeExpiredEvent.class);
        this.matchState = sub(session, "/user/queue/match-state", MatchStateEvent.class);
        this.matchEnded = sub(session, "/user/queue/match-ended", MatchEndedEvent.class);
        this.oppDisconnected = sub(session, "/user/queue/opponent-disconnected", OpponentDisconnectedEvent.class);
        this.oppReconnected = sub(session, "/user/queue/opponent-reconnected", OpponentReconnectedEvent.class);
        this.localState = sub(session, "/user/queue/local-state", LocalStateEvent.class);
    }

    static LobbyClient connectOnly(int port) throws Exception {
        return new LobbyClient(connect(port));
    }

    static LobbyClient enterLobby(int port) throws Exception {
        LobbyClient c = new LobbyClient(connect(port));
        c.session.send("/app/lobby/enter", null);
        LobbyStateEvent ls = poll(c.lobbyState);
        c.connId = ls.yourConnId();
        return c;
    }

    void send(String destination, Object payload) {
        session.send(destination, payload);
    }

    void disconnect() {
        session.disconnect();
    }
}
