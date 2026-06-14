package com.derbysquad.tictactoe.multiplayer;

import com.derbysquad.tictactoe.domain.Ai;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Session-keyed local game state (checklist Task 7). The C# {@code LocalGameSession} was a
 * {@code Scoped} (per-Blazor-circuit) bean; per the migration decision we do <em>not</em>
 * use {@code @Scope("websocket")} but instead keep an explicit, predictable
 * {@link ConcurrentHashMap} keyed by STOMP session id inside this singleton {@code @Service}.
 *
 * <p><b>Flag (no clean SignalR analog):</b> in the legacy app local play was driven by
 * in-process Blazor method calls, not the hub — there is no SignalR contract for it. The
 * game logic is ported here and entries are cleaned up on disconnect; wiring a STOMP (or
 * REST) transport for local play is a deferred decision, so no {@code @MessageMapping}
 * handlers are invented for it.
 */
@Service
public class LocalGameService {

    private final Ai ai;
    private final Map<String, LocalGame> games = new ConcurrentHashMap<>();

    public LocalGameService(Ai ai) {
        this.ai = ai;
    }

    public LocalGame getOrCreate(String sessionId) {
        return games.computeIfAbsent(sessionId, k -> new LocalGame(ai));
    }

    public LocalGame get(String sessionId) {
        return games.get(sessionId);
    }

    public void remove(String sessionId) {
        games.remove(sessionId);
    }

    public int activeSessionCount() {
        return games.size();
    }
}
