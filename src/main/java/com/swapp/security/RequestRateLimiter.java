package com.swapp.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fixed-window request counter keyed by an arbitrary string (client address or user id plus a rule name).
 * In-memory is right for a single instance; several instances would move the counters to Redis.
 */
@Component
public class RequestRateLimiter {

    /** Above this many tracked keys, expired windows are purged inline rather than waiting for the scheduler. */
    private static final int PURGE_THRESHOLD = 50_000;

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public RequestRateLimiter() {
        this(Clock.systemUTC());
    }

    RequestRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Counts one request against {@code key}.
     *
     * @return {@link Duration#ZERO} if the request is within {@code limit} for the current window, otherwise how long
     *         the caller should wait before retrying
     */
    public Duration acquire(String key, int limit, Duration window) {
        Instant now = clock.instant();
        if (windows.size() > PURGE_THRESHOLD) {
            purgeExpired();
        }
        Window w = windows.compute(key, (k, old) -> old == null || old.expired(now)
                ? new Window(now.plus(window), 1)
                : new Window(old.resetAt(), old.count() + 1));
        if (w.count() <= limit) {
            return Duration.ZERO;
        }
        Duration wait = Duration.between(now, w.resetAt());
        return wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait;
    }

    @Scheduled(fixedDelay = 60_000)
    void purgeExpired() {
        Instant now = clock.instant();
        windows.entrySet().removeIf(e -> e.getValue().expired(now));
    }

    private record Window(Instant resetAt, int count) {
        boolean expired(Instant now) {
            return !now.isBefore(resetAt);
        }
    }
}
