package com.swapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

import com.swapp.security.JwtService;
import com.swapp.security.google.GoogleAuthorizationRequestResolver;
import com.swapp.security.google.GoogleLoginHandlers;
import com.swapp.support.AbstractIntegrationTest;
import com.swapp.user.ExternalSignInService;
import com.swapp.user.ExternalSignInService.Refusal;
import com.swapp.user.ExternalSignInService.Refused;
import com.swapp.user.ExternalSignInService.SignedIn;
import com.swapp.user.ExternalSignInService.SignupRequired;
import com.swapp.user.identity.ExternalProfile;
import com.swapp.user.identity.IdentityProvider;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;

/**
 * "Sign in with Google". Google itself is not called: the tests start where Spring Security hands over a validated ID
 * token (the success handler) or a provider profile (the service), and check the account rules from there.
 */
@TestPropertySource(properties = {
        "app.google.client-id=test-client-id.apps.googleusercontent.com",
        "app.google.client-secret=test-client-secret"})
class GoogleSignInIntegrationTest extends AbstractIntegrationTest {

    private static final String TEST_JWT_SECRET = "test-only-signing-key-not-used-anywhere-else-0123456789";

    @Autowired
    private ExternalSignInService signIn;

    @Autowired
    private GoogleLoginHandlers handlers;

    @Autowired
    private JwtService jwtService;

    // ---------------------------------------------------------------- the redirect to Google

    @Test
    void startingSignInRedirectsToGoogleWithPkceStateNonceAndAccountChooser() throws Exception {
        var result = mvc.perform(get("/oauth2/authorization/google").param("next", "/item.html?id=7"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        // State, nonce and PKCE verifier wait in the short-lived handshake session (cookie checked in a live run).
        assertThat(result.getRequest().getSession(false)).isNotNull();
        URI location = URI.create(result.getResponse().getRedirectedUrl());
        assertThat(location.getHost()).isEqualTo("accounts.google.com");
        Map<String, List<String>> query = UriComponentsBuilder.fromUri(location).build().getQueryParams();
        assertThat(query.get("client_id")).containsExactly("test-client-id.apps.googleusercontent.com");
        assertThat(query.get("response_type")).containsExactly("code");
        assertThat(query.get("code_challenge_method")).containsExactly("S256");
        assertThat(query).containsKeys("state", "nonce", "code_challenge");
        assertThat(query.get("prompt")).containsExactly("select_account");
        assertThat(query.get("scope").get(0)).contains("openid").contains("email").contains("profile");
        assertThat(result.getRequest().getSession().getAttribute(GoogleAuthorizationRequestResolver.NEXT_ATTRIBUTE))
                .isEqualTo("/item.html?id=7");
    }

    @Test
    void anUnsafeNextTargetIsIgnored() throws Exception {
        var result = mvc.perform(get("/oauth2/authorization/google").param("next", "//evil.example/phish"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(result.getRequest().getSession().getAttribute(GoogleAuthorizationRequestResolver.NEXT_ATTRIBUTE))
                .isNull();
    }

    @Test
    void siteInfoAdvertisesGoogleSignIn() throws Exception {
        mvc.perform(get("/api/site")).andExpect(jsonPath("$.googleSignIn").value(true));
    }

    // ---------------------------------------------------------------- new members

    @Test
    void aNewPersonMustAcceptTheTermsBeforeAnAccountExists() throws Exception {
        MockHttpServletResponse back = googleReturns("g-100", "meera@gmail.com", true, "Meera Iyer", null);
        assertThat(back.getRedirectedUrl()).isEqualTo("/complete-signup.html");
        Cookie signup = cookieFrom(back, "swapp_signup");
        assertThat(signup).isNotNull();
        assertThat(back.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(c -> c.startsWith("swapp_token="));
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();

        mvc.perform(get("/api/auth/google/pending").cookie(signup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("meera@gmail.com"))
                .andExpect(jsonPath("$.suggestedName").value("Meera Iyer"));

        String withoutConsent = toJson(Map.of("displayName", "Meera", "acceptTerms", false));
        mvc.perform(post("/api/auth/google/complete").cookie(signup).contentType(MediaType.APPLICATION_JSON)
                .content(withoutConsent)).andExpect(status().isBadRequest());

        String body = toJson(Map.of("displayName", "Meera", "location", "Chennai", "acceptTerms", true));
        mvc.perform(post("/api/auth/google/complete").cookie(signup).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(cookie().exists("swapp_token"))
                .andExpect(cookie().maxAge("swapp_signup", 0))
                .andExpect(jsonPath("$.user.email").value("meera@gmail.com"))
                .andExpect(jsonPath("$.user.emailVerified").value(true))
                .andExpect(jsonPath("$.user.hasPassword").value(false));

        var row = jdbc.queryForMap("select password_hash, terms_version, terms_accepted_at from users");
        assertThat(row.get("password_hash")).isNull();
        assertThat(row.get("terms_version")).isNotNull();
        assertThat(row.get("terms_accepted_at")).isNotNull();
        assertThat(jdbc.queryForObject("select subject from user_identities", String.class)).isEqualTo("g-100");

        // Signing in again goes straight in.
        assertThat(googleReturns("g-100", "meera@gmail.com", true, "Meera Iyer", null).getRedirectedUrl())
                .isEqualTo("/home.html");
    }

    @Test
    void withoutAValidPendingCookieThereIsNothingToFinish() throws Exception {
        mvc.perform(get("/api/auth/google/pending")).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/google/complete").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("displayName", "Nobody", "acceptTerms", true))))
                .andExpect(status().isNotFound());
        TestUser asha = register("Asha Rao");
        // A session token is not a sign-up token (different audience), and vice versa.
        mvc.perform(get("/api/auth/google/pending").cookie(new Cookie("swapp_signup", asha.token())))
                .andExpect(status().isNoContent());
        var outcome = (SignupRequired) signIn.signIn(google("g-1", "x@gmail.com", true));
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + outcome.signupToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnverifiedGoogleEmailIsRefused() throws Exception {
        MockHttpServletResponse back = googleReturns("g-2", "new@gmail.com", false, "New", null);
        assertThat(back.getRedirectedUrl()).isEqualTo("/login.html?google=unverified");
        assertThat(jdbc.queryForObject("select count(*) from user_identities", Long.class)).isZero();
    }

    // ---------------------------------------------------------------- existing members

    @Test
    void aReturningMemberIsRecognisedByGoogleIdEvenAfterChangingTheirGoogleEmail() throws Exception {
        complete(google("g-3", "old@gmail.com", true));
        var outcome = signIn.signIn(google("g-3", "renamed@gmail.com", true));
        assertThat(outcome).isInstanceOf(SignedIn.class);
        assertThat(((SignedIn) outcome).user().getEmail()).isEqualTo("old@gmail.com");
        assertThat(jdbc.queryForObject("select email from user_identities", String.class))
                .isEqualTo("renamed@gmail.com");
    }

    @Test
    void googleIsLinkedToAVerifiedAccountWithTheSameEmailAndTheMemberIsAlerted() throws Exception {
        TestUser asha = register("Asha Rao");
        jdbc.update("update users set email_verified_at = now() where id = ?", asha.id());

        MockHttpServletResponse back = googleReturns("g-4", "ASHA.RAO@example.com", true, "Asha", "/offers.html");
        assertThat(back.getRedirectedUrl()).isEqualTo("/offers.html");
        assertThat(cookieFrom(back, "swapp_token")).isNotNull();
        assertThat(jdbc.queryForObject("select password_hash from users where id = ?", String.class, asha.id()))
                .isNotNull();
        assertThat(mail.await(asha.email(), "sign-in-added").text()).contains("asha.rao@example.com");
        // The member's existing session is untouched.
        mvc.perform(auth(get("/api/auth/me"), asha)).andExpect(status().isOk());
    }

    @Test
    void linkingToAnUnverifiedAccountRemovesThePasswordSomeoneElseMayHaveSet() throws Exception {
        // Someone registered this address without owning it (it was never verified).
        TestUser squatter = register("Victim Name");
        jdbc.update("update users set email_verified_at = null where id = ?", squatter.id());

        var outcome = signIn.signIn(google("g-5", squatter.email(), true));
        assertThat(outcome).isInstanceOf(SignedIn.class);
        var row = jdbc.queryForMap("select password_hash, email_verified_at from users where id = ?", squatter.id());
        assertThat(row.get("password_hash")).isNull();
        assertThat(row.get("email_verified_at")).isNotNull();
        // The squatter's session and password no longer work.
        mvc.perform(auth(get("/api/auth/me"), squatter)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", squatter.email(), "password", "Passw0rd!"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSuspendedMemberCannotSignInWithGoogle() throws Exception {
        TestUser asha = register("Asha Rao");
        jdbc.update("update users set suspended_at = now(), suspension_reason = 'Scam' where id = ?", asha.id());
        assertThat(googleReturns("g-6", asha.email(), true, "Asha", null).getRedirectedUrl())
                .isEqualTo("/login.html?google=suspended");

        jdbc.update("update users set suspended_at = null, suspension_reason = null where id = ?", asha.id());
        signIn.signIn(google("g-6", asha.email(), true));
        jdbc.update("update users set suspended_at = now(), suspension_reason = 'Scam' where id = ?", asha.id());
        assertThat(signIn.signIn(google("g-6", asha.email(), true)))
                .isEqualTo(new Refused(Refusal.SUSPENDED));
    }

    @Test
    void aSecondGoogleAccountCannotBeLinkedToTheSameMember() throws Exception {
        TestUser asha = register("Asha Rao");
        jdbc.update("update users set email_verified_at = now() where id = ?", asha.id());
        signIn.signIn(google("g-7", asha.email(), true));
        assertThat(signIn.connect(asha.id(), google("g-8", "other@gmail.com", true)))
                .isEqualTo(new Refused(Refusal.ALREADY_CONNECTED));
        TestUser ravi = register("Ravi Kumar");
        assertThat(signIn.connect(ravi.id(), google("g-7", "asha@gmail.com", true)))
                .isEqualTo(new Refused(Refusal.IN_USE));
    }

    // ---------------------------------------------------------------- connect and disconnect from Settings

    @Test
    void aSignedInMemberCanConnectAGoogleAccountWithADifferentEmailThenDisconnectIt() throws Exception {
        TestUser ravi = register("Ravi Kumar");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(GoogleAuthorizationRequestResolver.CONNECT_USER_ATTRIBUTE, ravi.id());
        MockHttpServletResponse response = new MockHttpServletResponse();
        handlers.onAuthenticationSuccess(request, response, token("g-9", "ravi.personal@gmail.com", true, "Ravi"));
        assertThat(response.getRedirectedUrl()).isEqualTo("/settings.html?google=connected#security");

        mvc.perform(auth(get("/api/users/me/identities"), ravi))
                .andExpect(jsonPath("$[0].provider").value("GOOGLE"))
                .andExpect(jsonPath("$[0].email").value("ravi.personal@gmail.com"));

        mvc.perform(auth(delete("/api/users/me/identities/google"), ravi)).andExpect(status().isNoContent());
        assertThat(mail.await(ravi.email(), "sign-in-removed")).isNotNull();
        mvc.perform(auth(delete("/api/users/me/identities/google"), ravi)).andExpect(status().isNotFound());
        mvc.perform(auth(delete("/api/users/me/identities/facebook"), ravi)).andExpect(status().isNotFound());
    }

    @Test
    void aGoogleOnlyMemberCannotDisconnectGoogleOrUseAPassword() throws Exception {
        TestUser meera = complete(google("g-10", "meera@gmail.com", true));
        mvc.perform(auth(delete("/api/users/me/identities/google"), meera)).andExpect(status().isConflict());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", "meera@gmail.com", "password", "Anything1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Incorrect email or password"));
        mvc.perform(auth(post("/api/users/me/password"), meera).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("currentPassword", "x", "newPassword", "NewPassw0rd"))))
                .andExpect(status().isBadRequest());

        // Setting a first password goes through the emailed link.
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", "meera@gmail.com")))).andExpect(status().isAccepted());
        String link = mail.token("meera@gmail.com", "password-reset");
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("token", link, "newPassword", "NewPassw0rd"))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", "meera@gmail.com", "password", "NewPassw0rd"))))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- deletion

    @Test
    void aGoogleOnlyMemberCanDeleteTheirAccountOnlyShortlyAfterSigningIn() throws Exception {
        TestUser meera = complete(google("g-11", "meera@gmail.com", true));
        String staleSession = sessionIssuedAt(meera.id(), Instant.now().minus(30, ChronoUnit.MINUTES));
        mvc.perform(delete("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + staleSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("sign in with Google")));

        mvc.perform(auth(delete("/api/users/me"), meera).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from user_identities", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select password_hash from users where id = ?", String.class, meera.id()))
                .isNull();

        // The same Google account can join again, as a brand-new member.
        assertThat(signIn.signIn(google("g-11", "meera@gmail.com", true))).isInstanceOf(SignupRequired.class);
    }

    // ---------------------------------------------------------------- failures

    @Test
    void cancellingAtGoogleOrAFailedHandshakeLandsOnTheSignInPage() throws Exception {
        var response = new MockHttpServletResponse();
        handlers.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied")));
        assertThat(response.getRedirectedUrl()).isEqualTo("/login.html?google=cancelled");

        mvc.perform(get("/login/oauth2/code/google").param("code", "forged").param("state", "forged"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .isEqualTo("/login.html?google=error"));
    }

    // ---------------------------------------------------------------- helpers

    private static ExternalProfile google(String sub, String email, boolean verified) {
        return new ExternalProfile(IdentityProvider.GOOGLE, sub, email, verified, "Google User");
    }

    /** Signs up a new member through Google and returns them with a fresh session token. */
    private TestUser complete(ExternalProfile profile) {
        var outcome = (SignupRequired) signIn.signIn(profile);
        var created = signIn.completeSignup(jwtService.verifySignup(outcome.signupToken()).orElseThrow(),
                new com.swapp.user.dto.UserDtos.CompleteSignupRequest("Google Member", null, true));
        return new TestUser(created.user().getId(), created.token().token(), created.user().getEmail());
    }

    /** What happens when Google sends the person back with a validated ID token. */
    private MockHttpServletResponse googleReturns(String sub, String email, boolean verified, String name,
                                                  String next) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (next != null) {
            request.getSession().setAttribute(GoogleAuthorizationRequestResolver.NEXT_ATTRIBUTE, next);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        handlers.onAuthenticationSuccess(request, response, token(sub, email, verified, name));
        return response;
    }

    private static OAuth2AuthenticationToken token(String sub, String email, boolean verified, String name) {
        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token").subject(sub).claim("email", email)
                .claim("email_verified", verified).claim("name", name).issuedAt(now).expiresAt(now.plusSeconds(300))
                .build();
        return new OAuth2AuthenticationToken(new DefaultOidcUser(List.of(), idToken), List.of(), "google");
    }

    private static Cookie cookieFrom(MockHttpServletResponse response, String name) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith(name + "=") && !h.startsWith(name + "=;"))
                .map(h -> new Cookie(name, h.substring(name.length() + 1, h.indexOf(';'))))
                .findFirst().orElse(null);
    }

    private String sessionIssuedAt(long userId, Instant issuedAt) {
        int version = jdbc.queryForObject("select token_version from users where id = ?", Integer.class, userId);
        return Jwts.builder().issuer("swapp").audience().add("swapp-session").and().subject(String.valueOf(userId))
                .claim("ver", version).issuedAt(Date.from(issuedAt))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
