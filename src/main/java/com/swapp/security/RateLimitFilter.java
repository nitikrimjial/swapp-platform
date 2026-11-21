package com.swapp.security;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapp.common.ApiError;
import com.swapp.config.AppProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Throttles the endpoints an anonymous attacker can hammer (sign-up, sign-in, password change: each costs a bcrypt
 * hash, which is expensive on a small instance) per client address, and all writes per signed-in user. Runs after
 * {@link JwtAuthenticationFilter} so the user is known. Over-limit requests get 429 with {@code Retry-After}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);

    private final RequestRateLimiter limiter;
    private final AppProperties.Limits limits;
    private final ObjectMapper mapper;

    public RateLimitFilter(RequestRateLimiter limiter, AppProperties.Limits limits, ObjectMapper mapper) {
        this.limiter = limiter;
        this.limits = limits;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !limits.rateLimitEnabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Duration wait = check(request);
        if (wait.isZero()) {
            chain.doFilter(request, response);
            return;
        }
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String message = seconds > 90
                ? "Too many attempts. Please try again in about " + ((seconds + 59) / 60) + " minutes."
                : "You're going a bit fast. Please wait " + seconds + " seconds and try again.";
        mapper.writeValue(response.getOutputStream(), new ApiError(Instant.now(), 429, "Too Many Requests", message,
                request.getRequestURI(), null));
    }

    private Duration check(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        boolean post = "POST".equals(method);
        String client = request.getRemoteAddr();

        if (post && path.equals("/api/auth/register")) {
            return limiter.acquire("register:" + client, limits.registrationsPerHour(), HOUR);
        }
        if (post && (path.equals("/api/auth/login") || path.equals("/api/users/me/password"))) {
            return limiter.acquire("auth:" + client, limits.authAttemptsPerMinute(), MINUTE);
        }
        boolean write = !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (write && auth != null && auth.getPrincipal() instanceof AuthUser user) {
            return limiter.acquire("write:" + user.id(), limits.writesPerMinute(), MINUTE);
        }
        return Duration.ZERO;
    }
}
