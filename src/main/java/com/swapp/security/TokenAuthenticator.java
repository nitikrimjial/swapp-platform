package com.swapp.security;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.swapp.user.UserRepository;

/**
 * Resolves a raw JWT into an {@link AuthUser}. Besides signature/expiry checks it verifies the user still exists and
 * that the token was issued for the user's current token version, which enables server-side revocation.
 */
@Component
public class TokenAuthenticator {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public TokenAuthenticator(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Optional<AuthUser> authenticate(String token) {
        return jwtService.verify(token)
                .flatMap(claims -> userRepository.findById(claims.userId())
                        .filter(user -> user.getTokenVersion() == claims.tokenVersion() && user.isActive())
                        .map(user -> new AuthUser(user.getId(), user.getEmail(), user.getRole(), user.isEmailVerified(),
                                claims.issuedAt())));
    }
}
