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
 * Fixed-window limiter for failed sign-ins, protecting against online password guessing. Failures are counted twice:
 * per client address + email (blocks one guesser quickly) and per email alone with a higher ceiling (blocks a guesser
 * spreading attempts over many addresses). In-memory suits a single instance; several instances would use Redis.
 */
@Component
public class LoginRateLimiter {

    /** The per-account ceiling is this many times the per-address one. */
    static final int ACCOUNT_FACTOR = 3;

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

    public void checkAllowed(String clientAddress, String email) {
        if (blocked(clientKey(clientAddress, email), maxAttempts)
                || blocked(accountKey(email), maxAttempts * ACCOUNT_FACTOR)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed sign-in attempts. Please wait a few minutes and try again.");
        }
    }

    public void recordFailure(String clientAddress, String email) {
        increment(clientKey(clientAddress, email));
        increment(accountKey(email));
    }

    public void reset(String clientAddress, String email) {
        failures.remove(clientKey(clientAddress, email));
        failures.remove(accountKey(email));
    }

    @Scheduled(fixedDelay = 600_000)
    void evictExpired() {
        Instant now = clock.instant();
        failures.entrySet().removeIf(e -> e.getValue().expired(now));
    }

    private boolean blocked(String key, int limit) {
        Window w = failures.get(key);
        return w != null && !w.expired(clock.instant()) && w.count >= limit;
    }

    private void increment(String key) {
        Instant now = clock.instant();
        failures.compute(key, (k, w) -> (w == null || w.expired(now)) ? new Window(now.plus(window), 1) : w.increment());
    }

    private static String clientKey(String clientAddress, String email) {
        return "client|" + clientAddress + "|" + email;
    }

    private static String accountKey(String email) {
        return "account|" + email;
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
