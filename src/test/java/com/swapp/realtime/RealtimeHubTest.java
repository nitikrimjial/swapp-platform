package com.swapp.realtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapp.config.AppProperties;

class RealtimeHubTest {

    private final RealtimeHub hub = new RealtimeHub(new ObjectMapper(), props(2));

    @Test
    void oldestSocketIsClosedWhenAUserExceedsTheConnectionLimit() throws Exception {
        WebSocketSession first = session("a", 7L);
        WebSocketSession second = session("b", 7L);
        WebSocketSession third = session("c", 7L);
        hub.afterConnectionEstablished(first);
        hub.afterConnectionEstablished(second);
        hub.afterConnectionEstablished(third);
        verify(first).close(any(CloseStatus.class));
        verify(second, never()).close(any(CloseStatus.class));
        verify(third, never()).close(any(CloseStatus.class));
    }

    @Test
    void revokingSessionsClosesOnlyThatUsersSockets() throws Exception {
        WebSocketSession mine = session("a", 7L);
        WebSocketSession theirs = session("b", 8L);
        hub.afterConnectionEstablished(mine);
        hub.afterConnectionEstablished(theirs);
        hub.onSessionsRevoked(new SessionsRevokedEvent(7L));
        verify(mine).close(any(CloseStatus.class));
        verify(theirs, never()).close(any(CloseStatus.class));
    }

    private static WebSocketSession session(String id, long userId) {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>(Map.of(RealtimeHub.USER_ID_ATTR, userId));
        when(s.getId()).thenReturn(id);
        when(s.getAttributes()).thenReturn(attributes);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    private static AppProperties props(int maxPerUser) {
        return new AppProperties(new AppProperties.Jwt("", Duration.ofHours(1)),
                new AppProperties.Cookie("swapp_token", false), new AppProperties.Cors(List.of()),
                new AppProperties.Security(4, 10, Duration.ofMinutes(15)),
                new AppProperties.Storage("database", "x", 1024, 6),
                new AppProperties.Limits(true, 20, 15, 120, 10, 5, 100, DataSize.ofMegabytes(30), DataSize.ofMegabytes(400),
                        maxPerUser));
    }
}
