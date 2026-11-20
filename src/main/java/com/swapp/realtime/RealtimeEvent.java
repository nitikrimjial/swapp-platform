package com.swapp.realtime;

/** An event to push to a single user's open WebSocket connections once the current transaction commits. */
public record RealtimeEvent(Long userId, String type, Object data) {
}
