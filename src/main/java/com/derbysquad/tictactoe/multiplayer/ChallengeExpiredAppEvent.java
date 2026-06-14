package com.derbysquad.tictactoe.multiplayer;

/**
 * Internal Spring {@code ApplicationEvent} signalling that a challenge timed out.
 *
 * <p>Replaces the C# {@code ChallengeBroker.Expired} C# event, which {@code Program.cs}
 * bridged manually to the hub. Here {@link ChallengeBroker} publishes this and
 * {@code GameMessageController} listens and pushes the STOMP {@code ChallengeExpiredEvent}.
 */
public record ChallengeExpiredAppEvent(Challenge challenge) {
}
