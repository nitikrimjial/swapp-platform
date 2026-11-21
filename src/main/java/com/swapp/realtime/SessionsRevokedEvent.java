package com.swapp.realtime;

/** Published when a user's tokens are revoked; open WebSockets for that user are closed after commit. */
public record SessionsRevokedEvent(Long userId) {
}
