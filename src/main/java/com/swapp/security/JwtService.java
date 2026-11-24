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
import com.swapp.user.identity.ExternalProfile;
import com.swapp.user.identity.IdentityProvider;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and verifies the app's signed tokens. Each kind of token has its own audience, so a token minted for one
 * purpose (a pending Google sign-up, say) can never be accepted as another (a session).
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String ISSUER = "swapp";
    private static final String SESSION_AUDIENCE = "swapp-session";
    private static final String SIGNUP_AUDIENCE = "swapp-external-signup";
    private static final String CLAIM_VERSION = "ver";
    private static final String CLAIM_PROVIDER = "prv";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_NAME = "name";
    /** Time a new member has to finish signing up after coming back from Google. */
    public static final Duration SIGNUP_TTL = Duration.ofMinutes(15);

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
                .audience().add(SESSION_AUDIENCE).and()
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_VERSION, user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /** Returns the verified session claims, or empty if the token is malformed, tampered with, expired or not a session. */
    public Optional<TokenClaims> verify(String token) {
        return parse(token, SESSION_AUDIENCE).flatMap(claims -> {
            try {
                Long userId = Long.valueOf(claims.getSubject());
                Integer version = claims.get(CLAIM_VERSION, Integer.class);
                Date issuedAt = claims.getIssuedAt();
                return Optional.of(new TokenClaims(userId, version == null ? -1 : version,
                        issuedAt == null ? Instant.EPOCH : issuedAt.toInstant()));
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        });
    }

    /**
     * Short-lived proof that the provider vouched for this person, held in an HttpOnly cookie while a new member accepts
     * the Terms. It creates nothing by itself.
     */
    public String issueSignup(ExternalProfile profile) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .audience().add(SIGNUP_AUDIENCE).and()
                .subject(profile.subject())
                .claim(CLAIM_PROVIDER, profile.provider().name())
                .claim(CLAIM_EMAIL, profile.email())
                .claim(CLAIM_NAME, profile.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(SIGNUP_TTL)))
                .signWith(key)
                .compact();
    }

    /** Only a verified email is ever put in a sign-up token, so the profile comes back as verified. */
    public Optional<ExternalProfile> verifySignup(String token) {
        return parse(token, SIGNUP_AUDIENCE).flatMap(claims -> {
            try {
                IdentityProvider provider = IdentityProvider.valueOf(claims.get(CLAIM_PROVIDER, String.class));
                String email = claims.get(CLAIM_EMAIL, String.class);
                if (!StringUtils.hasText(claims.getSubject()) || !StringUtils.hasText(email)) {
                    return Optional.empty();
                }
                return Optional.of(new ExternalProfile(provider, claims.getSubject(), email, true,
                        claims.get(CLAIM_NAME, String.class)));
            } catch (IllegalArgumentException | NullPointerException ex) {
                return Optional.empty();
            }
        });
    }

    private Optional<Claims> parse(String token, String audience) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Jwts.parser().verifyWith(key).requireIssuer(ISSUER).requireAudience(audience).build()
                    .parseSignedClaims(token).getPayload());
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

    /** @param issuedAt when the session began (used to require a recent sign-in for sensitive actions) */
    public record TokenClaims(Long userId, int tokenVersion, Instant issuedAt) {
    }
}
