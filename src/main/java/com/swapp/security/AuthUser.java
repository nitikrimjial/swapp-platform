package com.swapp.security;

import java.time.Duration;
import java.time.Instant;

import com.swapp.user.Role;

/**
 * Authenticated principal placed in the security context by {@link JwtAuthenticationFilter}.
 *
 * @param sessionStartedAt when this session's token was issued (sign-in, sign-up or password change)
 */
public record AuthUser(Long id, String email, Role role, boolean emailVerified, Instant sessionStartedAt) {

    /** Granted when the member may post, offer, request, message and report (see SecurityConfig). */
    public static final String VERIFIED_AUTHORITY = "EMAIL_VERIFIED";

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** True if the member signed in within {@code window}: a stand-in for re-entering a password they don't have. */
    public boolean signedInWithin(Duration window) {
        return sessionStartedAt != null && sessionStartedAt.isAfter(Instant.now().minus(window));
    }
}
