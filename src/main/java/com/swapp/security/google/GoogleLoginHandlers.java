package com.swapp.security.google;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.swapp.security.AuthCookies;
import com.swapp.security.JwtService;
import com.swapp.user.ExternalSignInService;
import com.swapp.user.ExternalSignInService.Connected;
import com.swapp.user.ExternalSignInService.Outcome;
import com.swapp.user.ExternalSignInService.Refused;
import com.swapp.user.ExternalSignInService.SignedIn;
import com.swapp.user.ExternalSignInService.SignupRequired;
import com.swapp.user.identity.ExternalProfile;
import com.swapp.user.identity.IdentityProvider;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Runs when Google sends the person back ({@code /login/oauth2/code/google}) after Spring Security has exchanged the
 * code and validated the ID token. Turns the outcome into our own session cookie (never a server session) and a
 * redirect to a page that explains what happened.
 */
@Component
public class GoogleLoginHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(GoogleLoginHandlers.class);
    private static final String DEFAULT_TARGET = "/home.html";

    private final ExternalSignInService signIn;
    private final AuthCookies cookies;
    private final JwtService jwtService;

    public GoogleLoginHandlers(ExternalSignInService signIn, AuthCookies cookies, JwtService jwtService) {
        this.signIn = signIn;
        this.cookies = cookies;
        this.jwtService = jwtService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        String next = null;
        Long connectUser = null;
        HttpSession session = request.getSession(false);
        if (session != null) {
            next = (String) session.getAttribute(GoogleAuthorizationRequestResolver.NEXT_ATTRIBUTE);
            connectUser = (Long) session.getAttribute(GoogleAuthorizationRequestResolver.CONNECT_USER_ATTRIBUTE);
            session.invalidate(); // the OAuth handshake is over; authentication continues with our JWT cookie
        }
        SecurityContextHolder.clearContext();

        String target;
        try {
            ExternalProfile profile = profile((OidcUser) authentication.getPrincipal());
            target = connectUser != null ? afterConnect(signIn.connect(connectUser, profile))
                    : afterSignIn(signIn.signIn(profile), next, response);
        } catch (RuntimeException ex) {
            log.warn("Google sign-in could not be completed: {}", ex.toString());
            target = "/login.html?google=error";
        }
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect(target);
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        boolean cancelled = exception instanceof OAuth2AuthenticationException oauth
                && "access_denied".equals(oauth.getError().getErrorCode());
        if (!cancelled) {
            log.info("Google sign-in failed: {}", exception.getMessage());
        }
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect("/login.html?google=" + (cancelled ? "cancelled" : "error"));
    }

    private String afterSignIn(Outcome outcome, String next, HttpServletResponse response) {
        return switch (outcome) {
            case SignedIn s -> {
                cookies.write(response, s.token().token(), jwtService.expiration());
                yield next != null ? next : DEFAULT_TARGET;
            }
            case SignupRequired s -> {
                cookies.writeSignup(response, s.signupToken(), JwtService.SIGNUP_TTL);
                yield "/complete-signup.html" + (next == null ? "" : "?next=" + encode(next));
            }
            case Refused r -> "/login.html?google=" + r.refusal().code();
            case Connected c -> DEFAULT_TARGET;
        };
    }

    private static String afterConnect(Outcome outcome) {
        String result = outcome instanceof Refused r ? r.refusal().code() : "connected";
        return "/settings.html?google=" + result + "#security";
    }

    private static ExternalProfile profile(OidcUser user) {
        return new ExternalProfile(IdentityProvider.GOOGLE, user.getSubject(), user.getEmail(),
                Boolean.TRUE.equals(user.getEmailVerified()), user.getFullName());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
