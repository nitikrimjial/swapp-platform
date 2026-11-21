package com.swapp.config;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swapp.common.ApiError;
import com.swapp.security.AuthCookies;
import com.swapp.security.AuthUser;
import com.swapp.security.JwtAuthenticationFilter;
import com.swapp.security.RateLimitFilter;
import com.swapp.security.RequestRateLimiter;
import com.swapp.security.TokenAuthenticator;
import com.swapp.security.google.GoogleAuthorizationRequestResolver;
import com.swapp.security.google.GoogleLoginHandlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
@EnableScheduling
public class SecurityConfig {

    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
            "font-src 'self' https://fonts.gstatic.com",
            "img-src 'self' data: blob:",
            // 'self' covers the same-origin WebSocket (CSP Level 3). Place search / reverse geocoding for "near me"
            // uses OpenStreetMap Nominatim directly from the browser.
            "connect-src 'self' https://nominatim.openstreetmap.org",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    /** Creating content or contacting other members. Managing what already exists stays open. */
    private static final String[] VERIFIED_ONLY = {"/api/items", "/api/items/{id}/images", "/api/offers",
            "/api/donation-requests", "/api/conversations", "/api/conversations/{id}/messages", "/api/reports"};

    private static final String UNVERIFIED_MESSAGE = "Please confirm your email address first. Use the link we "
            + "emailed you, or send a new one from Settings.";

    /** Only geolocation ("near me") is used, and only by our own pages. */
    private static final String PERMISSIONS_POLICY =
            "geolocation=(self), camera=(), microphone=(), payment=(), usb=()";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, TokenAuthenticator authenticator,
                                                   AuthCookies cookies, ObjectMapper mapper,
                                                   RequestRateLimiter rateLimiter, AppProperties props,
                                                   MailSettings mail,
                                                   ObjectProvider<ClientRegistrationRepository> googleRegistration,
                                                   GoogleLoginHandlers googleHandlers) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(authenticator, cookies,
                mail.requireVerification());
        http
                // Stateless JWT in a SameSite=Strict HttpOnly cookie (or a Bearer header): the browser never sends the
                // credential on cross-site requests, so classic CSRF tokens are unnecessary.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {
                })
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        // Sent only on HTTPS requests (the app trusts X-Forwarded-Proto from the platform proxy).
                        .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000).includeSubDomains(true))
                        .crossOriginOpenerPolicy(coop -> coop
                                .policy(CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(mapper, req, res, 401, "Unauthorized",
                                "Please sign in to continue"))
                        .accessDeniedHandler((req, res, ex) -> writeError(mapper, req, res, 403, "Forbidden",
                                isUnverifiedMember() ? UNVERIFIED_MESSAGE
                                        : "You do not have permission to perform this action")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/logout",
                                "/api/auth/session", "/api/auth/forgot-password", "/api/auth/reset-password",
                                "/api/auth/verify-email", "/api/auth/google/pending", "/api/auth/google/complete")
                        .permitAll()
                        // Actions that reach other members need a verified email (when verification is required).
                        .requestMatchers(HttpMethod.POST, VERIFIED_ONLY).hasAuthority(AuthUser.VERIFIED_AUTHORITY)
                        .requestMatchers("/api/users/me", "/api/users/me/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/items", "/api/items/{id}", "/api/categories",
                                "/api/users/{id}", "/api/files/**", "/api/stats", "/api/site").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().permitAll())
                // Ahead of the OAuth filters too, so the rate limiter sees "Continue with Google" requests.
                .addFilterBefore(jwtFilter, OAuth2AuthorizationRequestRedirectFilter.class)
                .addFilterAfter(new RateLimitFilter(rateLimiter, props.limits(), mapper), JwtAuthenticationFilter.class);

        // "Sign in with Google", only when GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET are set. It starts at
        // /oauth2/authorization/google and Google sends the person back to /login/oauth2/code/google.
        ClientRegistrationRepository registrations = googleRegistration.getIfAvailable();
        if (registrations != null) {
            http.oauth2Login(oauth -> oauth
                    .loginPage("/login.html") // our own page; also stops Spring generating a default one
                    .authorizationEndpoint(a -> a.authorizationRequestResolver(
                            new GoogleAuthorizationRequestResolver(registrations, authenticator, cookies)))
                    .successHandler(googleHandlers)
                    .failureHandler(googleHandlers));
        }
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder(AppProperties props) {
        return new BCryptPasswordEncoder(props.security().bcryptStrength());
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private static boolean isUnverifiedMember() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthUser
                && auth.getAuthorities().stream().noneMatch(a -> AuthUser.VERIFIED_AUTHORITY.equals(a.getAuthority()));
    }

    private static void writeError(ObjectMapper mapper, HttpServletRequest req, HttpServletResponse res, int status,
                                   String error, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(),
                new ApiError(Instant.now(), status, error, message, req.getRequestURI(), null));
    }
}
