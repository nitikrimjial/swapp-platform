package com.swapp.user.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.swapp.common.BaseEntity;
import com.swapp.user.User;

/**
 * Issues and redeems single-use account tokens (OWASP Forgot Password guidance): 256 bits from a CSPRNG, URL-safe,
 * stored only as a SHA-256 hash, short-lived, invalidated on use, and replaced when a new one is issued.
 */
@Service
public class UserTokenService {

    private static final Logger log = LoggerFactory.getLogger(UserTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Base64url of 32 bytes without padding is exactly 43 characters. */
    private static final int TOKEN_LENGTH = 43;

    private final UserTokenRepository tokens;

    public UserTokenService(UserTokenRepository tokens) {
        this.tokens = tokens;
    }

    /** Creates a new token, invalidating earlier unused ones of the same purpose. Returns the raw secret. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String issue(User user, TokenPurpose purpose, Duration ttl) {
        Instant now = BaseEntity.now();
        tokens.invalidateOutstanding(user.getId(), purpose, now);
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);

        UserToken token = new UserToken();
        token.setUser(user);
        token.setPurpose(purpose);
        token.setTokenHash(hash(raw));
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(ttl));
        tokens.save(token);
        return raw;
    }

    /**
     * Redeems a token: it must exist, match the purpose, be unused and unexpired. On success it is marked used, as are
     * any other outstanding tokens of the same purpose, and the owning user is returned.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<User> consume(String raw, TokenPurpose purpose) {
        if (raw == null || raw.length() != TOKEN_LENGTH) {
            return Optional.empty();
        }
        Instant now = BaseEntity.now();
        return tokens.lockByHash(hash(raw))
                .filter(t -> t.getPurpose() == purpose && t.getUsedAt() == null && t.getExpiresAt().isAfter(now))
                .map(t -> {
                    User user = t.getUser();
                    tokens.invalidateOutstanding(user.getId(), purpose, now);
                    return user;
                });
    }

    /** Expired tokens are useless; keep the table small. Used tokens are kept for a day as an audit trail. */
    @Scheduled(cron = "0 17 3 * * *")
    @Transactional
    public void purgeExpired() {
        int removed = tokens.deleteExpiredBefore(Instant.now().minus(1, ChronoUnit.DAYS));
        if (removed > 0) {
            log.info("Purged {} expired account tokens", removed);
        }
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
