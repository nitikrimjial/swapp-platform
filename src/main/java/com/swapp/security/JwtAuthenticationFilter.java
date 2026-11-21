package com.swapp.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Authenticates requests from either an {@code Authorization: Bearer} header (API clients) or the auth cookie. */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenAuthenticator authenticator;
    private final AuthCookies cookies;
    private final boolean requireVerification;

    /**
     * @param requireVerification when false every member receives {@link AuthUser#VERIFIED_AUTHORITY}, so the
     *                            verification rule in SecurityConfig is a no-op
     */
    public JwtAuthenticationFilter(TokenAuthenticator authenticator, AuthCookies cookies,
                                   boolean requireVerification) {
        this.authenticator = authenticator;
        this.cookies = cookies;
        this.requireVerification = requireVerification;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/") || path.equals("/ws"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticator.authenticate(token).ifPresent(user -> {
                var auth = new UsernamePasswordAuthenticationToken(user, null, authorities(user));
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }

    private List<GrantedAuthority> authorities(AuthUser user) {
        List<GrantedAuthority> authorities = new ArrayList<>(2);
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        if (user.emailVerified() || !requireVerification) {
            authorities.add(new SimpleGrantedAuthority(AuthUser.VERIFIED_AUTHORITY));
        }
        return authorities;
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        return cookies.read(request);
    }
}
