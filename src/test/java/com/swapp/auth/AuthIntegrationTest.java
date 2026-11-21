package com.swapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.swapp.support.AbstractIntegrationTest;

import jakarta.servlet.http.Cookie;

class AuthIntegrationTest extends AbstractIntegrationTest {

    @Test
    void registerIssuesHttpOnlyStrictCookieAndHashesPassword() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", "Alice@Example.com", "password", "Passw0rd!",
                                "displayName", "Alice"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("alice@example.com"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
                .andReturn();
        assertThat(result.getResponse().getCookie("swapp_token")).isNotNull();

        String hash = jdbc.queryForObject("select password_hash from users where email = 'alice@example.com'",
                String.class);
        assertThat(hash).startsWith("$2").isNotEqualTo("Passw0rd!");
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        register("Alice");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", "ALICE@example.com", "password", "Passw0rd!",
                                "displayName", "Other"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this email already exists"));
    }

    @Test
    void invalidRegistrationReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", "not-an-email", "password", "short", "displayName", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists())
                .andExpect(jsonPath("$.fieldErrors.displayName").exists());
    }

    @Test
    void loginSucceedsWithCorrectPasswordAndFailsOtherwise() throws Exception {
        register("Alice");
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", "alice@example.com", "password", "WrongPass1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Incorrect email or password"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", "nobody@example.com", "password", "WrongPass1"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", "alice@example.com", "password", "Passw0rd!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.displayName").value("Alice"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void protectedEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/api/offers/incoming")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedOrGarbageTokensAreRejected() throws Exception {
        TestUser alice = register("Alice");
        String tampered = alice.token().substring(0, alice.token().length() - 4) + "abcd";
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(new Cookie("swapp_token", "garbage")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cookieAuthenticatesBrowserRequests() throws Exception {
        TestUser alice = register("Alice");
        mvc.perform(get("/api/auth/me").cookie(new Cookie("swapp_token", alice.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"));
    }

    @Test
    void sessionEndpointReportsAnonymousAndAuthenticatedState() throws Exception {
        mvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").doesNotExist());
        TestUser alice = register("Alice");
        mvc.perform(get("/api/auth/session").cookie(new Cookie("swapp_token", alice.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.displayName").value("Alice"));
    }

    @Test
    void logoutClearsCookie() throws Exception {
        mvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    @Test
    void changingPasswordRevokesPreviouslyIssuedTokens() throws Exception {
        TestUser alice = register("Alice");
        mvc.perform(auth(post("/api/users/me/password"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("currentPassword", "Passw0rd!", "newPassword", "NewPassw0rd"))))
                .andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/auth/me"), alice)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", "alice@example.com", "password", "NewPassw0rd"))))
                .andExpect(status().isOk());
    }

    @Test
    void wrongCurrentPasswordIsRejected() throws Exception {
        TestUser alice = register("Alice");
        mvc.perform(auth(post("/api/users/me/password"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("currentPassword", "nope12345", "newPassword", "NewPassw0rd"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void profileCanBeUpdatedAndPublicProfileHidesEmail() throws Exception {
        TestUser alice = register("Alice");
        mvc.perform(auth(put("/api/users/me"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("displayName", "Alice Smith", "bio", "Loves bikes",
                                "location", "Shelbyville"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alice Smith"));
        mvc.perform(get("/api/users/" + alice.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alice Smith"))
                .andExpect(jsonPath("$.location").value("Shelbyville"))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Permissions-Policy", containsString("geolocation=(self)")))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"));
    }

    @Test
    void hstsIsSentOnHttpsRequests() throws Exception {
        mvc.perform(get("/api/categories").secure(true))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")));
    }

    @Test
    void passwordsOverBcryptByteLimitAreRejectedNotCrashed() throws Exception {
        // 32 characters, but 92 bytes in UTF-8: passes @Size(max = 72) yet exceeds bcrypt's 72-byte input limit.
        String hindi = "अ".repeat(30) + "a1";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", "hindi@example.com", "password", hindi,
                                "displayName", "Asha"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", "hindi@example.com", "password", hindi))))
                .andExpect(status().isBadRequest());
    }
}
