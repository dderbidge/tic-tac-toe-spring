package com.derbysquad.tictactoe.multiplayer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Tracks who is connected and who is in a match. Port of the C# {@code PresenceTracker}
 * (a singleton over lock-guarded dictionaries) to a singleton {@code @Service} over
 * {@link ConcurrentHashMap}.
 *
 * <p>The C# {@code Changed} event (which drove Blazor re-renders) is dropped — there is
 * no server-side UI to notify. Name-uniqueness is a compound read-modify-write, so
 * {@link #add} is synchronized; the rest rely on the map's atomicity.
 */
@Service
public class PresenceTracker {

    private final Map<String, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> matchByConnection = new ConcurrentHashMap<>();
    private final Object addLock = new Object();

    public PlayerSession add(String connectionId) {
        synchronized (addLock) {
            String baseName = NameGenerator.forConnection(connectionId);
            String name = baseName;
            int suffix = 2;
            while (nameTaken(name)) {
                name = baseName + " " + suffix++;
            }
            PlayerSession session = new PlayerSession(connectionId, name, Instant.now());
            sessions.put(connectionId, session);
            return session;
        }
    }

    public PlayerSession remove(String connectionId) {
        matchByConnection.remove(connectionId);
        return sessions.remove(connectionId);
    }

    public PlayerSession get(String connectionId) {
        return connectionId == null ? null : sessions.get(connectionId);
    }

    public List<PlayerSession> snapshotLobby() {
        List<PlayerSession> list = new ArrayList<>(sessions.size());
        for (PlayerSession s : sessions.values()) {
            if (!matchByConnection.containsKey(s.connectionId())) {
                list.add(s);
            }
        }
        return list;
    }

    public void enterMatch(String connectionId, String matchId) {
        matchByConnection.put(connectionId, matchId);
    }

    public void leaveMatch(String connectionId) {
        matchByConnection.remove(connectionId);
    }

    public String currentMatchOf(String connectionId) {
        return connectionId == null ? null : matchByConnection.get(connectionId);
    }

    public void restoreMatch(String connectionId, String name, String matchId) {
        sessions.put(connectionId, new PlayerSession(connectionId, name, Instant.now()));
        matchByConnection.put(connectionId, matchId);
    }

    private boolean nameTaken(String name) {
        for (PlayerSession s : sessions.values()) {
            if (s.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
