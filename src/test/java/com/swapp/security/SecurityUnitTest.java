package com.swapp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.swapp.common.ApiException;
import com.swapp.config.AppProperties;
import com.swapp.user.User;

class SecurityUnitTest {

    private static AppProperties props(String secret, Duration ttl) {
        return new AppProperties(new AppProperties.Jwt(secret, ttl), new AppProperties.Cookie("swapp_token", true),
                new AppProperties.Cors(List.of("http://localhost")), new AppProperties.Security(4, 3, Duration.ofMinutes(5)),
                new AppProperties.Storage("database", "x", 1024, 6));
    }

    private static User user(long id, int version) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        ReflectionTestUtils.setField(u, "tokenVersion", version);
        return u;
    }

    @Test
    void jwtRoundTripsSubjectAndVersion() {
        JwtService jwt = new JwtService(props("0123456789abcdef0123456789abcdef-extra", Duration.ofHours(1)));
        var token = jwt.issue(user(42L, 3));
        var claims = jwt.verify(token.token()).orElseThrow();
        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.tokenVersion()).isEqualTo(3);
    }

    @Test
    void jwtSignedWithAnotherKeyIsRejected() {
        JwtService a = new JwtService(props("0123456789abcdef0123456789abcdef-aaaa", Duration.ofHours(1)));
        JwtService b = new JwtService(props("0123456789abcdef0123456789abcdef-bbbb", Duration.ofHours(1)));
        assertThat(b.verify(a.issue(user(1L, 0)).token())).isEmpty();
        assertThat(b.verify("not.a.jwt")).isEmpty();
        assertThat(b.verify(null)).isEmpty();
    }

    @Test
    void expiredJwtIsRejected() {
        JwtService jwt = new JwtService(props("0123456789abcdef0123456789abcdef-extra", Duration.ofSeconds(-5)));
        assertThat(jwt.verify(jwt.issue(user(1L, 0)).token())).isEmpty();
    }

    @Test
    void shortSecretsAreRefused() {
        assertThatThrownBy(() -> new JwtService(props("too-short", Duration.ofHours(1))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rateLimiterBlocksAfterMaxFailuresAndResetsAfterWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(3, Duration.ofMinutes(5), clock);
        for (int i = 0; i < 3; i++) {
            limiter.checkAllowed("ip|a@b.c");
            limiter.recordFailure("ip|a@b.c");
        }
        assertThatThrownBy(() -> limiter.checkAllowed("ip|a@b.c")).isInstanceOf(ApiException.class);
        limiter.checkAllowed("ip|other@b.c"); // other keys unaffected
        clock.now = clock.now.plus(Duration.ofMinutes(6));
        limiter.checkAllowed("ip|a@b.c");
    }

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
