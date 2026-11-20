package com.swapp.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.swapp.common.ApiException;
import com.swapp.config.AppProperties;

/**
 * Simple fixed-window limiter for failed logins, keyed by client address + email. Protects against online password
 * guessing. In-memory is appropriate for a single instance; a multi-instance deployment would move this to Redis.
 */
@Component
public class LoginRateLimiter {

    private final int maxAttempts;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Window> failures = new ConcurrentHashMap<>();

    @Autowired
    public LoginRateLimiter(AppProperties props) {
        this(props.security().loginMaxAttempts(), props.security().loginWindow(), Clock.systemUTC());
    }

    LoginRateLimiter(int maxAttempts, Duration window, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.clock = clock;
    }

    public void checkAllowed(String key) {
        Window w = failures.get(key);
        if (w != null && !w.expired(clock.instant()) && w.count >= maxAttempts) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed sign-in attempts. Please wait a few minutes and try again.");
        }
    }

    public void recordFailure(String key) {
        Instant now = clock.instant();
        failures.compute(key, (k, w) -> (w == null || w.expired(now)) ? new Window(now.plus(window), 1) : w.increment());
    }

    public void reset(String key) {
        failures.remove(key);
    }

    @Scheduled(fixedDelay = 600_000)
    void evictExpired() {
        Instant now = clock.instant();
        failures.entrySet().removeIf(e -> e.getValue().expired(now));
    }

    private record Window(Instant resetAt, int count) {
        boolean expired(Instant now) {
            return now.isAfter(resetAt);
        }

        Window increment() {
            return new Window(resetAt, count + 1);
        }
    }
}
