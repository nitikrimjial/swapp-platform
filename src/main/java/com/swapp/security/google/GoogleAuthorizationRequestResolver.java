package com.swapp.security.google;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import com.swapp.security.AuthCookies;
import com.swapp.security.TokenAuthenticator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Builds the redirect to Google ({@code /oauth2/authorization/google}) with PKCE and an account chooser, and remembers
 * for the return trip where to send the person ({@code ?next=}) and whether a signed-in member is connecting Google
 * from Settings ({@code ?intent=connect}).
 *
 * <p>The state, nonce and PKCE verifier live in a short-lived server session (cookie {@code swapp_oauth}) that exists
 * only for the minute or so the person spends at Google, and is invalidated on return. Nothing else uses server
 * sessions.
 */
public class GoogleAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    public static final String NEXT_ATTRIBUTE = "swapp.oauth.next";
    public static final String CONNECT_USER_ATTRIBUTE = "swapp.oauth.connectUser";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final TokenAuthenticator authenticator;
    private final AuthCookies cookies;

    public GoogleAuthorizationRequestResolver(ClientRegistrationRepository registrations,
                                              TokenAuthenticator authenticator, AuthCookies cookies) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        this.delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
                .andThen(builder -> builder.additionalParameters(params -> params.put("prompt", "select_account"))));
        this.authenticator = authenticator;
        this.cookies = cookies;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return remember(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return remember(request, delegate.resolve(request, clientRegistrationId));
    }

    private OAuth2AuthorizationRequest remember(HttpServletRequest request, OAuth2AuthorizationRequest result) {
        if (result == null) {
            return null;
        }
        HttpSession session = request.getSession();
        session.removeAttribute(NEXT_ATTRIBUTE);
        session.removeAttribute(CONNECT_USER_ATTRIBUTE);
        String next = SafeRedirect.path(request.getParameter("next"));
        if (next != null) {
            session.setAttribute(NEXT_ATTRIBUTE, next);
        }
        // The SameSite=Strict session cookie arrives only when the member clicked "Connect" on our own page, so a
        // cross-site link cannot start a connect on someone else's behalf.
        if ("connect".equals(request.getParameter("intent"))) {
            authenticator.authenticate(cookies.read(request))
                    .ifPresent(user -> session.setAttribute(CONNECT_USER_ATTRIBUTE, user.id()));
        }
        return result;
    }
}
