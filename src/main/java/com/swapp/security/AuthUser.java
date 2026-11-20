package com.swapp.security;

import com.swapp.user.Role;

/** Authenticated principal placed in the security context by {@link JwtAuthenticationFilter}. */
public record AuthUser(Long id, String email, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
