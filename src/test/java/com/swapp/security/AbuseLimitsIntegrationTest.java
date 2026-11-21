package com.swapp.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import com.swapp.support.AbstractIntegrationTest;

/** Rate limits, listing cap and storage quotas, switched on with deliberately small values. */
@TestPropertySource(properties = {
        "app.limits.rate-limit-enabled=true",
        "app.limits.registrations-per-hour=3",
        "app.limits.auth-attempts-per-minute=3",
        "app.limits.writes-per-minute=5",
        "app.limits.email-requests-per-hour=2",
        "app.limits.max-active-listings-per-user=2",
        "app.limits.storage-per-user=1KB",
        "app.limits.storage-total=1500B"})
class AbuseLimitsIntegrationTest extends AbstractIntegrationTest {

    /** A 600-byte file with a valid PNG signature (uploads are validated by magic bytes). */
    private static final byte[] PNG_600 = Arrays.copyOf(Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="), 600);

    @Autowired
    private RequestRateLimiter limiter;

    @BeforeEach
    void resetCounters() {
        ((Map<?, ?>) ReflectionTestUtils.getField(limiter, "windows")).clear();
    }

    @Test
    void recoveryEmailsAreLimitedPerClientAddressAndPerAccount() throws Exception {
        TestUser asha = register("Asha Rao");
        String body = toJson(Map.of("email", asha.email()));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted());
        }
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());

        // From another address the response is still 202, but the per-account cap means no third email is sent.
        mvc.perform(post("/api/auth/forgot-password").with(r -> { r.setRemoteAddr("203.0.113.7"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        mail.await(asha.email(), "password-reset");
        Thread.sleep(400);
        org.assertj.core.api.Assertions.assertThat(mail.count(asha.email(), "password-reset")).isEqualTo(2);
    }

    @Test
    void registrationsAreLimitedPerClientAddress() throws Exception {
        for (int i = 0; i < 3; i++) {
            register("User " + i);
        }
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("acceptTerms", true, "email", "fourth@example.com", "password", "Passw0rd!",
                                "displayName", "Fourth"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.status").value(429));
        // Another address is unaffected.
        mvc.perform(post("/api/auth/register").with(r -> { r.setRemoteAddr("203.0.113.9"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("acceptTerms", true, "email", "fifth@example.com", "password", "Passw0rd!",
                                "displayName", "Fifth"))))
                .andExpect(status().isCreated());
    }

    @Test
    void signInAttemptsAreLimitedPerClientAddress() throws Exception {
        register("Alice");
        String wrong = toJson(Map.of("email", "alice@example.com", "password", "WrongPass1"));
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrong))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrong))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void writesAreLimitedPerUserButReadsAreNot() throws Exception {
        TestUser alice = register("Alice");
        String profile = toJson(Map.of("displayName", "Alice"));
        for (int i = 0; i < 5; i++) {
            mvc.perform(auth(put("/api/users/me"), alice).contentType(MediaType.APPLICATION_JSON).content(profile))
                    .andExpect(status().isOk());
        }
        mvc.perform(auth(put("/api/users/me"), alice).contentType(MediaType.APPLICATION_JSON).content(profile))
                .andExpect(status().isTooManyRequests());
        mvc.perform(auth(get("/api/users/me"), alice)).andExpect(status().isOk());
    }

    @Test
    void activeListingsAreCappedPerUser() throws Exception {
        TestUser alice = register("Alice");
        createItem(alice, "Bike one", "EXCHANGE");
        createItem(alice, "Bike two", "EXCHANGE");
        mvc.perform(auth(post("/api/items"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "Bike three", "description", "Another bike in good order.",
                                "categoryId", categoryId("electronics"), "condition", "GOOD",
                                "listingType", "EXCHANGE", "location", "Springfield"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("at most 2 listings")));
    }

    @Test
    void photoStorageIsLimitedPerUserAndSiteWide() throws Exception {
        TestUser alice = register("Alice");
        long aliceItem = createItem(alice, "Lamp", "EXCHANGE");
        upload(alice, aliceItem).andExpect(status().isOk());
        upload(alice, aliceItem).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("storage limit")));

        TestUser bob = register("Bob");
        upload(bob, createItem(bob, "Chair", "EXCHANGE")).andExpect(status().isOk()); // 1200 of 1500 bytes used

        TestUser carol = register("Carol");
        upload(carol, createItem(carol, "Table", "EXCHANGE")).andExpect(status().isInsufficientStorage());
    }

    private org.springframework.test.web.servlet.ResultActions upload(TestUser user, long itemId) throws Exception {
        return mvc.perform(auth(multipart("/api/items/" + itemId + "/images")
                .file(new MockMultipartFile("files", "p.png", "image/png", PNG_600)), user));
    }
}
