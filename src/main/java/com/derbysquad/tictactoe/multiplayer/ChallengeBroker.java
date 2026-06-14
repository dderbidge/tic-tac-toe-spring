package com.derbysquad.tictactoe.multiplayer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Holds pending challenges and expires them. Port of the C# {@code ChallengeBroker}.
 *
 * <p>Two pieces of Task 8 live here: the background {@code Timer} that swept every 5s
 * becomes {@link #sweepExpired()} annotated {@code @Scheduled(fixedRate = 5000)}, and the
 * C# {@code Expired} event becomes an {@link ApplicationEventPublisher} publishing
 * {@link ChallengeExpiredAppEvent}.
 */
@Service
public class ChallengeBroker {

    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher publisher;
    private final Duration lifetime;

    public ChallengeBroker(ApplicationEventPublisher publisher,
                           @Value("${tictactoe.challenge.lifetime-ms:30000}") long lifetimeMs) {
        this.publisher = publisher;
        this.lifetime = Duration.ofMillis(lifetimeMs);
    }

    public Challenge create(String fromConn, String fromName, String toConn, String toName) {
        Challenge c = new Challenge(
                UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                fromConn, fromName, toConn, toName,
                Instant.now().plus(lifetime));
        challenges.put(c.challengeId(), c);
        return c;
    }

    public Challenge take(String challengeId) {
        return challenges.remove(challengeId);
    }

    /** Removes the challenge only if it exists and is owned by {@code fromConnId}. */
    public boolean cancel(String challengeId, String fromConnId) {
        return challenges.entrySet().removeIf(e ->
                e.getKey().equals(challengeId) && e.getValue().fromConnId().equals(fromConnId));
    }

    public void removeAllInvolving(String connectionId) {
        challenges.values().removeIf(c ->
                c.fromConnId().equals(connectionId) || c.toConnId().equals(connectionId));
    }

    @Scheduled(fixedRateString = "${tictactoe.challenge.sweep-ms:5000}")
    void sweepExpired() {
        Instant now = Instant.now();
        List<Challenge> expired = new ArrayList<>();
        challenges.values().removeIf(c -> {
            if (!c.expiresAt().isAfter(now)) {
                expired.add(c);
                return true;
            }
            return false;
        });
        for (Challenge c : expired) {
            publisher.publishEvent(new ChallengeExpiredAppEvent(c));
        }
    }
}
