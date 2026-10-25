package com.derbysquad.tictactoe.multiplayer;

import java.util.Set;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

/**
 * Keeps anonymous clients on the application API. The simple broker also accepts
 * client messages unless guarded: a raw /queue subscription can bypass session-local
 * /user resolution, and a SEND to /topic can impersonate a server event.
 */
final class ClientDestinationGuard implements ChannelInterceptor {
    private static final Set<String> COMMANDS = Set.of(
            "/app/lobby/enter", "/app/lobby/leave",
            "/app/challenge/send", "/app/challenge/respond", "/app/challenge/cancel",
            "/app/match/move", "/app/match/forfeit", "/app/match/rematch", "/app/match/rejoin",
            "/app/local/state", "/app/local/hotseat", "/app/local/setup", "/app/local/vs-computer",
            "/app/local/move", "/app/local/play-again", "/app/local/main-menu", "/app/local/reset");

    private static final Set<String> SUBSCRIPTIONS = Set.of(
            "/topic/lobby/joined", "/topic/lobby/left",
            "/user/queue/local-state", "/user/queue/lobby-state",
            "/user/queue/challenge-incoming", "/user/queue/challenge-sent",
            "/user/queue/challenge-accepted", "/user/queue/challenge-declined",
            "/user/queue/challenge-expired", "/user/queue/match-state", "/user/queue/match-ended",
            "/user/queue/opponent-disconnected", "/user/queue/opponent-reconnected");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(message);
        StompCommand command = headers.getCommand();
        if (command == null && headers.getMessageType() == SimpMessageType.HEARTBEAT) return message;

        String destination = headers.getDestination();
        boolean allowed = command != null && switch (command) {
            case SEND -> destination != null && COMMANDS.contains(destination);
            case SUBSCRIBE -> destination != null && SUBSCRIPTIONS.contains(destination);
            case CONNECT, STOMP, DISCONNECT, UNSUBSCRIBE -> true;
            default -> false;
        };
        if (!allowed) throw new IllegalArgumentException("STOMP command or destination is not permitted");
        return message;
    }
}
