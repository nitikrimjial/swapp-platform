package com.swapp.security;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.swapp.config.AppProperties;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The JWT is delivered as an HttpOnly, SameSite=Strict cookie so that page scripts can never read it (XSS-resistant)
 * and browsers never attach it to cross-site requests (CSRF-resistant).
 */
@Component
public class AuthCookies {

    /** Holds the pending sign-up token after Google, only for the API calls that finish signing up. */
    public static final String SIGNUP_COOKIE = "swapp_signup";
    private static final String SIGNUP_PATH = "/api/auth/google";

    private final AppProperties props;

    public AuthCookies(AppProperties props) {
        this.props = props;
    }

    public void write(HttpServletResponse response, String token, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(props.cookie().name(), token, "/", maxAge).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(props.cookie().name(), "", "/", Duration.ZERO).toString());
    }

    public String read(HttpServletRequest request) {
        return read(request, props.cookie().name());
    }

    public void writeSignup(HttpServletResponse response, String token, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(SIGNUP_COOKIE, token, SIGNUP_PATH, maxAge).toString());
    }

    public void clearSignup(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(SIGNUP_COOKIE, "", SIGNUP_PATH, Duration.ZERO).toString());
    }

    public String readSignup(HttpServletRequest request) {
        return read(request, SIGNUP_COOKIE);
    }

    private static String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie build(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(props.cookie().secure())
                .sameSite("Strict")
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}
