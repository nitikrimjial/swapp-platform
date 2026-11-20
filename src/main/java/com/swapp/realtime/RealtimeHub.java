package com.swapp.realtime;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tracks authenticated WebSocket sessions per user and delivers {@link RealtimeEvent}s. Clients only receive; all
 * state changes go through the REST API. The browser falls back to polling if the socket is unavailable.
 */
@Component
public class RealtimeHub extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);
    static final String USER_ID_ATTR = "swapp.userId";

    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public RealtimeHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(USER_ID_ATTR);
        var safe = new ConcurrentWebSocketSessionDecorator(session, 5_000, 256 * 1024);
        sessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(safe);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get(USER_ID_ATTR);
        Set<WebSocketSession> set = sessions.get(userId);
        if (set != null) {
            set.removeIf(s -> s.getId().equals(session.getId()));
            if (set.isEmpty()) {
                sessions.remove(userId, set);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Client "ping" keep-alives are accepted and ignored.
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEvent(RealtimeEvent event) {
        Set<WebSocketSession> targets = sessions.get(event.userId());
        if (targets == null || targets.isEmpty()) {
            return;
        }
        TextMessage payload;
        try {
            payload = new TextMessage(mapper.writeValueAsString(Map.of("type", event.type(), "data", event.data())));
        } catch (JsonProcessingException e) {
            log.error("Could not serialise realtime event {}", event.type(), e);
            return;
        }
        for (WebSocketSession s : targets) {
            try {
                if (s.isOpen()) {
                    s.sendMessage(payload);
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping realtime message for session {}: {}", s.getId(), e.getMessage());
            }
        }
    }

    public int connectedUsers() {
        return sessions.size();
    }
}
