package com.swapp.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.swapp.support.AbstractIntegrationTest;

class DonationIntegrationTest extends AbstractIntegrationTest {

    @Test
    void donationRequestWorkflow() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");
        long sofa = createItem(alice, "Comfy sofa", "DONATION", "furniture", "GOOD", "Springfield");

        long bobRequest = idOf(request(bob, sofa, "I just moved and have no sofa").andExpect(status().isCreated()));
        long carolRequest = idOf(request(carol, sofa, null).andExpect(status().isCreated()));

        // duplicate and self requests are rejected
        request(bob, sofa, "again").andExpect(status().isConflict());
        request(alice, sofa, "mine").andExpect(status().isBadRequest());

        // owner sees requests and was notified
        assertThat(getJson("/api/donation-requests/incoming?status=PENDING", alice).get("totalElements").asInt())
                .isEqualTo(2);
        assertThat(getJson("/api/notifications", alice).at("/content/0/type").asText()).isEqualTo("DONATION_REQUESTED");
        assertThat(getJson("/api/items/" + sofa, bob).at("/viewer/donationRequestStatus").asText()).isEqualTo("PENDING");

        // requesters cannot accept; owner accepts Bob
        mvc.perform(auth(post("/api/donation-requests/" + bobRequest + "/accept"), bob))
                .andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/donation-requests/" + bobRequest + "/accept"), alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat(itemStatus(sofa)).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("select status from donation_requests where id = ?", String.class, carolRequest))
                .isEqualTo("DECLINED");
        assertThat(getJson("/api/notifications", carol).at("/content/0/type").asText()).isEqualTo("DONATION_DECLINED");
        assertThat(getJson("/api/notifications", bob).at("/content/0/type").asText()).isEqualTo("DONATION_ACCEPTED");

        // owner marks handed over
        mvc.perform(auth(post("/api/donation-requests/" + bobRequest + "/complete"), alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(itemStatus(sofa)).isEqualTo("COMPLETED");
        assertThat(getJson("/api/users/" + alice.id(), null).get("itemsGivenAway").asInt()).isEqualTo(1);
    }

    @Test
    void cancellingAnAcceptedRequestMakesTheItemAvailableAgain() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        long books = createItem(alice, "Box of novels", "GIVEAWAY", "books", "GOOD", "Springfield");
        long requestId = idOf(request(bob, books, "Please!").andExpect(status().isCreated()));
        mvc.perform(auth(post("/api/donation-requests/" + requestId + "/accept"), alice)).andExpect(status().isOk());

        mvc.perform(auth(post("/api/donation-requests/" + requestId + "/cancel"), bob))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(itemStatus(books)).isEqualTo("AVAILABLE");
        assertThat(getJson("/api/notifications", alice).at("/content/0/type").asText())
                .isEqualTo("DONATION_CANCELLED");
        // Bob can request again after withdrawing
        request(bob, books, "Changed my mind").andExpect(status().isCreated());
    }

    @Test
    void exchangeListingsCannotBeRequestedAsDonations() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        long bike = createItem(alice, "Bike", "EXCHANGE");
        request(bob, bike, "free?").andExpect(status().isBadRequest());
    }

    @Test
    void ownerCanDeclinePendingRequest() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        long chair = createItem(alice, "Chair", "DONATION", "furniture", "FAIR", "Springfield");
        long requestId = idOf(request(bob, chair, null));
        mvc.perform(auth(post("/api/donation-requests/" + requestId + "/decline"), alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));
        assertThat(itemStatus(chair)).isEqualTo("AVAILABLE");
        assertThat(getJson("/api/donation-requests/outgoing?status=DECLINED", bob).get("totalElements").asInt())
                .isEqualTo(1);
    }

    private ResultActions request(TestUser user, long itemId, String message) throws Exception {
        var body = message == null ? Map.of("itemId", itemId) : Map.of("itemId", itemId, "message", message);
        return mvc.perform(auth(post("/api/donation-requests"), user).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(body)));
    }

    private long idOf(ResultActions actions) throws Exception {
        return read(actions.andReturn()).get("id").asLong();
    }
}
