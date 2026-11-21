package com.swapp.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import com.swapp.support.AbstractIntegrationTest;

/**
 * With verification required, unverified members can browse and manage their account but cannot reach other members.
 * SMTP is "configured" so the app starts; the recording sender captures the emails instead.
 */
@TestPropertySource(properties = {
        "app.mail.require-verification=true",
        "app.mail.provider=smtp",
        "spring.mail.host=localhost"})
class EmailVerificationRequiredIntegrationTest extends AbstractIntegrationTest {

    @Test
    void unverifiedMembersCannotPostOfferRequestOrMessageUntilVerified() throws Exception {
        TestUser owner = verified(register("Owner One"));
        long itemId = createItem(owner, "Desk lamp", "EXCHANGE");
        TestUser fresh = register("Fresh Member");

        // Reading and account management still work.
        mvc.perform(auth(get("/api/items"), fresh)).andExpect(status().isOk());
        mvc.perform(auth(put("/api/users/me"), fresh).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("displayName", "Fresh M")))).andExpect(status().isOk());

        // Anything that reaches other members is refused with a clear explanation.
        mvc.perform(auth(post("/api/items"), fresh).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("confirm your email")));
        mvc.perform(auth(post("/api/conversations"), fresh).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("recipientId", owner.id(), "body", "Hi", "itemId", itemId))))
                .andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/donation-requests"), fresh).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("itemId", itemId)))).andExpect(status().isForbidden());

        // After verifying, the same session can post straight away (state is re-read on every request).
        verified(fresh);
        createItem(fresh, "Bookshelf", "EXCHANGE");
    }

    @Test
    void sessionEndpointTellsThePageVerificationIsRequired() throws Exception {
        TestUser fresh = register("Fresh Member");
        mvc.perform(auth(get("/api/auth/session"), fresh))
                .andExpect(jsonPath("$.emailVerificationRequired").value(true))
                .andExpect(jsonPath("$.user.emailVerified").value(false));
    }

    @Test
    void visitorsStillGet401NotTheVerificationMessage() throws Exception {
        mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private TestUser verified(TestUser user) throws Exception {
        String token = mail.token(user.email(), "verify-email");
        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("token", token)))).andExpect(status().isNoContent());
        return user;
    }
}
