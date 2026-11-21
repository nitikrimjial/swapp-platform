package com.swapp.security;

import com.swapp.user.Role;

/** Authenticated principal placed in the security context by {@link JwtAuthenticationFilter}. */
public record AuthUser(Long id, String email, Role role, boolean emailVerified) {

    /** Granted when the member may post, offer, request, message and report (see SecurityConfig). */
    public static final String VERIFIED_AUTHORITY = "EMAIL_VERIFIED";

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
