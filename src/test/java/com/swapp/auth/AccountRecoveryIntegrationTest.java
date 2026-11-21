package com.swapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.swapp.support.AbstractIntegrationTest;

/** Email verification, forgotten-password reset and security alerts, end to end through the HTTP API. */
class AccountRecoveryIntegrationTest extends AbstractIntegrationTest {

    // ---------------------------------------------------------------- email verification

    @Test
    void registrationSendsAVerificationLinkThatVerifiesOnce() throws Exception {
        TestUser asha = register("Asha Rao");
        assertThat(getJson("/api/users/me", asha).get("emailVerified").asBoolean()).isFalse();

        String token = mail.token(asha.email(), "verify-email");
        assertThat(mail.await(asha.email(), "verify-email").html()).contains("/verify-email.html#token=" + token);

        postJson("/api/auth/verify-email", Map.of("token", token)).andExpect(status().isNoContent());
        assertThat(getJson("/api/users/me", asha).get("emailVerified").asBoolean()).isTrue();

        // Single use.
        postJson("/api/auth/verify-email", Map.of("token", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("invalid or has expired")));
    }

    @Test
    void resendingVerificationReplacesTheEarlierLink() throws Exception {
        TestUser asha = register("Asha Rao");
        String first = mail.token(asha.email(), "verify-email");
        mail.clear();

        mvc.perform(auth(post("/api/users/me/verification-email"), asha)).andExpect(status().isAccepted());
        String second = mail.token(asha.email(), "verify-email");
        assertThat(second).isNotEqualTo(first);

        postJson("/api/auth/verify-email", Map.of("token", first)).andExpect(status().isBadRequest());
        postJson("/api/auth/verify-email", Map.of("token", second)).andExpect(status().isNoContent());
        // Nothing left to verify.
        mvc.perform(auth(post("/api/users/me/verification-email"), asha)).andExpect(status().isConflict());
    }

    @Test
    void tamperedAndMalformedVerificationTokensAreRejected() throws Exception {
        TestUser asha = register("Asha Rao");
        String token = mail.token(asha.email(), "verify-email");
        String tampered = (token.charAt(0) == 'A' ? 'B' : 'A') + token.substring(1);
        postJson("/api/auth/verify-email", Map.of("token", tampered)).andExpect(status().isBadRequest());
        postJson("/api/auth/verify-email", Map.of("token", "short")).andExpect(status().isBadRequest());
        postJson("/api/auth/verify-email", Map.of("token", "")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- password reset

    @Test
    void forgotPasswordAnswersTheSameForUnknownEmailsAndSendsNothing() throws Exception {
        register("Asha Rao");
        postJson("/api/auth/forgot-password", Map.of("email", "nobody@example.com"))
                .andExpect(status().isAccepted());
        mail.assertNone("nobody@example.com", "password-reset");
    }

    @Test
    void passwordResetChangesPasswordRevokesSessionsAndAlertsTheMember() throws Exception {
        TestUser asha = register("Asha Rao");
        // Case does not matter.
        postJson("/api/auth/forgot-password", Map.of("email", "ASHA.RAO@Example.com"))
                .andExpect(status().isAccepted());
        String token = mail.token(asha.email(), "password-reset");

        postJson("/api/auth/reset-password", Map.of("token", token, "newPassword", "N3wPassword"))
                .andExpect(status().isNoContent());

        // The existing session no longer works, the old password fails and the new one works.
        mvc.perform(auth(get("/api/users/me"), asha)).andExpect(status().isUnauthorized());
        login(asha.email(), "Passw0rd!").andExpect(status().isUnauthorized());
        login(asha.email(), "N3wPassword").andExpect(status().isOk())
                // Following the emailed link proved control of the address.
                .andExpect(jsonPath("$.user.emailVerified").value(true));

        assertThat(mail.await(asha.email(), "password-changed").text()).contains("was just changed");
        // The link is single use.
        postJson("/api/auth/reset-password", Map.of("token", token, "newPassword", "An0therOne"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestingAnotherResetInvalidatesTheEarlierLink() throws Exception {
        TestUser asha = register("Asha Rao");
        postJson("/api/auth/forgot-password", Map.of("email", asha.email())).andExpect(status().isAccepted());
        String first = mail.token(asha.email(), "password-reset");
        mail.clear();
        postJson("/api/auth/forgot-password", Map.of("email", asha.email())).andExpect(status().isAccepted());
        String second = mail.token(asha.email(), "password-reset");

        postJson("/api/auth/reset-password", Map.of("token", first, "newPassword", "N3wPassword"))
                .andExpect(status().isBadRequest());
        postJson("/api/auth/reset-password", Map.of("token", second, "newPassword", "N3wPassword"))
                .andExpect(status().isNoContent());
    }

    @Test
    void expiredResetLinksAreRejected() throws Exception {
        TestUser asha = register("Asha Rao");
        postJson("/api/auth/forgot-password", Map.of("email", asha.email())).andExpect(status().isAccepted());
        String token = mail.token(asha.email(), "password-reset");
        jdbc.update("update user_tokens set created_at = now() - interval '3 hours', "
                + "expires_at = now() - interval '1 minute' where purpose = 'PASSWORD_RESET'");

        postJson("/api/auth/reset-password", Map.of("token", token, "newPassword", "N3wPassword"))
                .andExpect(status().isBadRequest());
        login(asha.email(), "Passw0rd!").andExpect(status().isOk());
    }

    @Test
    void aVerificationTokenCannotBeUsedToResetThePassword() throws Exception {
        TestUser asha = register("Asha Rao");
        String verifyToken = mail.token(asha.email(), "verify-email");
        postJson("/api/auth/reset-password", Map.of("token", verifyToken, "newPassword", "N3wPassword"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetEnforcesThePasswordPolicy() throws Exception {
        TestUser asha = register("Asha Rao");
        postJson("/api/auth/forgot-password", Map.of("email", asha.email())).andExpect(status().isAccepted());
        String token = mail.token(asha.email(), "password-reset");
        postJson("/api/auth/reset-password", Map.of("token", token, "newPassword", "lettersonly"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").exists());
        // A rejected request does not burn the link.
        postJson("/api/auth/reset-password", Map.of("token", token, "newPassword", "N3wPassword"))
                .andExpect(status().isNoContent());
    }

    @Test
    void tokensAreStoredOnlyAsHashes() throws Exception {
        TestUser asha = register("Asha Rao");
        String token = mail.token(asha.email(), "verify-email");
        String stored = jdbc.queryForObject("select token_hash from user_tokens where purpose = 'EMAIL_VERIFICATION'",
                String.class);
        assertThat(stored).hasSize(64).doesNotContain(token);
    }

    @Test
    void changingPasswordInSettingsSendsASecurityAlert() throws Exception {
        TestUser asha = register("Asha Rao");
        mvc.perform(auth(post("/api/users/me/password"), asha).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("currentPassword", "Passw0rd!", "newPassword", "N3wPassword"))))
                .andExpect(status().isNoContent());
        assertThat(mail.await(asha.email(), "password-changed").subject()).isEqualTo("Your Swapp password was changed");
    }

    @Test
    void sessionEndpointReportsWhetherVerificationIsRequired() throws Exception {
        mvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.emailVerificationRequired").value(false));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return postJson("/api/auth/login", Map.of("email", email, "password", password));
    }
}
