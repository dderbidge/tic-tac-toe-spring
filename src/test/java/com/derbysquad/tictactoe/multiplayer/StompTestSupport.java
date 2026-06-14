package com.derbysquad.tictactoe.multiplayer;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** Shared STOMP client helpers for the multiplayer integration tests. */
final class StompTestSupport {

    static final long TIMEOUT_SECONDS = 5;

    private StompTestSupport() {
    }

    static StompSession connect(int port) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client.connectAsync("ws://localhost:" + port + "/gamehub", new StompSessionHandlerAdapter() {})
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    static <T> BlockingQueue<T> sub(StompSession session, String destination, Class<T> type) {
        BlockingQueue<T> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return type;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((T) payload);
            }
        });
        return queue;
    }

    static <T> T poll(BlockingQueue<T> queue) throws InterruptedException {
        T value = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(value, "timed out waiting for a server push");
        return value;
    }

    static <T> T pollUntil(BlockingQueue<T> queue, Predicate<T> predicate) throws InterruptedException {
        for (int i = 0; i < 12; i++) {
            T value = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (value == null) break;
            if (predicate.test(value)) return value;
        }
        throw new AssertionError("no message matched the predicate in time");
    }

    /** @return true if NOTHING arrived within {@code ms} (used for negative / no-cross-talk assertions). */
    static boolean nothingArrives(BlockingQueue<?> queue, long ms) throws InterruptedException {
        return queue.poll(ms, TimeUnit.MILLISECONDS) == null;
    }
}
