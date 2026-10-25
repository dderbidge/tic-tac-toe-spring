package com.derbysquad.tictactoe.multiplayer;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket setup. Replaces the SignalR registration in C#
 * {@code Program.cs} ({@code AddSignalR()} + {@code MapHub<GameHub>("/gamehub")}).
 *
 * <ul>
 *   <li>Endpoint {@code /gamehub} keeps the legacy URL.</li>
 *   <li>{@code /app} = client→server ({@code @MessageMapping}); {@code /topic} = broadcast;
 *       {@code /user} = per-session queues (identity = STOMP session id).</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Process one session's moves/reset commands in the order they arrived.
        registry.setPreserveReceiveOrder(true);
        registry.addEndpoint("/gamehub").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        // Keep a recipient's final board snapshot ahead of its match-ended event.
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ClientDestinationGuard());
    }
}
