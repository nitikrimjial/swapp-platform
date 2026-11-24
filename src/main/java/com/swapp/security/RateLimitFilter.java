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
 * Throttles the endpoints an anonymous attacker can hammer (sign-up, sign-in, password change and reset: each costs a
 * bcrypt hash, which is expensive on a small instance; recovery emails, which could be used to spam an inbox) per client
 * address, and all writes per signed-in user. Runs after {@link JwtAuthenticationFilter} so the user is known, and
 * before Spring's OAuth filters, because each "Continue with Google" creates a short-lived server session.
 * Over-limit requests get 429 with {@code Retry-After}.
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
        String path = request.getRequestURI();
        return !limits.rateLimitEnabled() || !(path.startsWith("/api/") || isOAuth(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Duration wait = check(request);
        if (wait.isZero()) {
            chain.doFilter(request, response);
            return;
        }
        if (isOAuth(request.getRequestURI())) { // a page navigation, not an API call: explain on the sign-in page
            response.sendRedirect("/login.html?google=busy");
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

    /** Start of "Sign in with Google" and Google's redirect back. */
    private static boolean isOAuth(String path) {
        return path.startsWith("/oauth2/authorization/") || path.startsWith("/login/oauth2/code/");
    }

    private Duration check(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        boolean post = "POST".equals(method);
        String client = request.getRemoteAddr();

        if (isOAuth(path)) {
            return limiter.acquire("oauth:" + client, limits.authAttemptsPerMinute(), MINUTE);
        }
        if (post && (path.equals("/api/auth/register") || path.equals("/api/auth/google/complete"))) {
            return limiter.acquire("register:" + client, limits.registrationsPerHour(), HOUR);
        }
        if (post && path.equals("/api/auth/forgot-password")) {
            return limiter.acquire("email:" + client, limits.emailRequestsPerHour(), HOUR);
        }
        boolean deleteAccount = "DELETE".equals(method) && path.equals("/api/users/me"); // checks a password
        if (deleteAccount || (post && (path.equals("/api/auth/login") || path.equals("/api/users/me/password")
                || path.equals("/api/auth/reset-password") || path.equals("/api/auth/verify-email")))) {
            return limiter.acquire("auth:" + client, limits.authAttemptsPerMinute(), MINUTE);
        }
        boolean write = !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if ("GET".equals(method) && path.equals("/api/users/me/export") && auth != null
                && auth.getPrincipal() instanceof AuthUser user) {
            return limiter.acquire("export:" + user.id(), limits.exportsPerHour(), HOUR);
        }
        if (post && path.equals("/api/users/me/verification-email") && auth != null
                && auth.getPrincipal() instanceof AuthUser user) {
            return limiter.acquire("email-user:" + user.id(), limits.emailRequestsPerHour(), HOUR);
        }
        if (write && auth != null && auth.getPrincipal() instanceof AuthUser user) {
            return limiter.acquire("write:" + user.id(), limits.writesPerMinute(), MINUTE);
        }
        return Duration.ZERO;
    }
}
