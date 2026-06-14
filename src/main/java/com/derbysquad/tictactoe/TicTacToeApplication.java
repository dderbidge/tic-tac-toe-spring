package com.derbysquad.tictactoe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Multiplayer server entry point. Replaces the Blazor Server host in
 * {@code ../legacy-csharp/web-multiplayer/Program.cs}.
 *
 * <p>{@code @EnableScheduling} powers the {@code @Scheduled} challenge sweep that
 * replaces the C# {@code ChallengeBroker} background {@code Timer}.
 */
@SpringBootApplication
@EnableScheduling
public class TicTacToeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicTacToeApplication.class, args);
    }
}
