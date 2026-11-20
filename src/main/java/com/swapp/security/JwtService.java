package com.swapp.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.swapp.config.AppProperties;
import com.swapp.user.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String ISSUER = "swapp";
    private static final String CLAIM_VERSION = "ver";

    private final SecretKey key;
    private final Duration expiration;

    public JwtService(AppProperties props) {
        this.expiration = props.jwt().expiration();
        String secret = props.jwt().secret();
        if (!StringUtils.hasText(secret)) {
            byte[] random = new byte[64];
            new SecureRandom().nextBytes(random);
            this.key = Keys.hmacShaKeyFor(random);
            log.warn("JWT_SECRET is not set: using an ephemeral signing key. Sessions will not survive a restart. "
                    + "Never run like this in production.");
        } else {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                throw new IllegalStateException("JWT_SECRET must be at least 32 bytes long");
            }
            this.key = Keys.hmacShaKeyFor(bytes);
        }
    }

    public IssuedToken issue(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expiration);
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_VERSION, user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /** Returns the verified claims, or empty if the token is malformed, tampered with or expired. */
    public Optional<TokenClaims> verify(String token) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).requireIssuer(ISSUER).build()
                    .parseSignedClaims(token).getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            Integer version = claims.get(CLAIM_VERSION, Integer.class);
            return Optional.of(new TokenClaims(userId, version == null ? -1 : version));
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected JWT: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public Duration expiration() {
        return expiration;
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public record TokenClaims(Long userId, int tokenVersion) {
    }
}
