package com.derbysquad.tictactoe.multiplayer;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class ClientDestinationGuardTest {
    private final ClientDestinationGuard guard = new ClientDestinationGuard();

    @ParameterizedTest
    @ValueSource(strings = {"/topic/lobby/joined", "/queue/match-state-userVictim",
            "/user/victim/queue/match-ended", "/app/unknown", "/app/match/move/extra"})
    void rejectsForgedServerEventsAndUnmappedCommands(String destination) {
        assertThrows(IllegalArgumentException.class,
                () -> guard.preSend(frame(StompCommand.SEND, destination), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/queue/local-state-userVictim", "/user/victim/queue/match-state",
            "/user/queue/**", "/topic/**", "/app/match/move"})
    void rejectsRawPrivateQueuesAndWildcardSubscriptions(String destination) {
        assertThrows(IllegalArgumentException.class,
                () -> guard.preSend(frame(StompCommand.SUBSCRIBE, destination), null));
    }

    @Test
    void permitsNormalGameCommandAndOwnSessionSubscription() {
        Message<byte[]> move = frame(StompCommand.SEND, "/app/match/move");
        Message<byte[]> ownQueue = frame(StompCommand.SUBSCRIBE, "/user/queue/match-state");
        assertSame(move, guard.preSend(move, null));
        assertSame(ownQueue, guard.preSend(ownQueue, null));
    }

    @Test
    void permitsConnectionAndHeartbeatFrames() {
        Message<byte[]> connect = frame(StompCommand.CONNECT, null);
        Message<byte[]> heartbeat = MessageBuilder.createMessage(new byte[0],
                StompHeaderAccessor.createForHeartbeat().getMessageHeaders());
        assertSame(connect, guard.preSend(connect, null));
        assertSame(heartbeat, guard.preSend(heartbeat, null));
    }

    private static Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
