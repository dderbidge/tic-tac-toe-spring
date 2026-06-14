package com.derbysquad.tictactoe.config;

import com.derbysquad.tictactoe.domain.Ai;
import java.util.Random;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Object-graph wiring. Brings forward the seeded-{@link Random} bean (checklist Task 2)
 * because the session-keyed game service (Task 7) needs an {@link Ai}.
 *
 * <p>{@code tictactoe.seed} (relaxed-bound from {@code TICTACTOE_SEED}) preserves the
 * legacy determinism hook without static global state.
 */
@Configuration
public class GameConfig {

    @Bean
    public Random aiRandom(@Value("${tictactoe.seed:#{null}}") Integer seed) {
        return seed != null ? new Random(seed) : new Random();
    }

    @Bean
    public Ai ai(Random aiRandom) {
        return new Ai(aiRandom);
    }

    /** Backs both the {@code @Scheduled} sweep and the per-match disconnect-grace timers. */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ttt-sched-");
        scheduler.initialize();
        return scheduler;
    }
}
