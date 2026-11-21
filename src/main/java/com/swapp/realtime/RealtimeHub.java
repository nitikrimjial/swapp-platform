package com.swapp.realtime;

import java.io.IOException;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

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
import com.swapp.config.AppProperties;

/**
 * Tracks authenticated WebSocket sessions per user and delivers {@link RealtimeEvent}s. Clients only receive; all
 * state changes go through the REST API. The browser falls back to polling if the socket is unavailable.
 * Each user may hold a bounded number of sockets (oldest closed first), and revoking a user's tokens closes them all.
 */
@Component
public class RealtimeHub extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);
    static final String USER_ID_ATTR = "swapp.userId";
    private static final CloseStatus TOO_MANY = CloseStatus.POLICY_VIOLATION.withReason("Too many connections");
    private static final CloseStatus REVOKED = CloseStatus.POLICY_VIOLATION.withReason("Session revoked");

    private final Map<Long, Deque<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final int maxPerUser;

    public RealtimeHub(ObjectMapper mapper, AppProperties props) {
        this.mapper = mapper;
        this.maxPerUser = Math.max(1, props.limits().realtimeConnectionsPerUser());
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(USER_ID_ATTR);
        var safe = new ConcurrentWebSocketSessionDecorator(session, 5_000, 256 * 1024);
        Deque<WebSocketSession> userSessions = sessions.computeIfAbsent(userId, k -> new ConcurrentLinkedDeque<>());
        userSessions.addLast(safe);
        while (userSessions.size() > maxPerUser) {
            WebSocketSession oldest = userSessions.pollFirst();
            if (oldest != null) {
                closeQuietly(oldest, TOO_MANY);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get(USER_ID_ATTR);
        Deque<WebSocketSession> userSessions = sessions.get(userId);
        if (userSessions != null) {
            userSessions.removeIf(s -> s.getId().equals(session.getId()));
            if (userSessions.isEmpty()) {
                sessions.remove(userId, userSessions);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Client "ping" keep-alives are accepted and ignored.
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEvent(RealtimeEvent event) {
        Deque<WebSocketSession> targets = sessions.get(event.userId());
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

    /** Password change / "sign out everywhere": sockets authenticated with the old tokens must not keep listening. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSessionsRevoked(SessionsRevokedEvent event) {
        Deque<WebSocketSession> userSessions = sessions.remove(event.userId());
        if (userSessions != null) {
            userSessions.forEach(s -> closeQuietly(s, REVOKED));
        }
    }

    public int connectedUsers() {
        return sessions.size();
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException | IllegalStateException e) {
            log.debug("Could not close session {}: {}", session.getId(), e.getMessage());
        }
    }
}
