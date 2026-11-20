package com.swapp.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.swapp.support.AbstractIntegrationTest;

/**
 * The end-to-end journey from the product brief, executed against the real database:
 * register two users, list, browse, offer, accept, verify exchange/items/notifications, block conflicts, complete.
 */
class ExchangeJourneyIntegrationTest extends AbstractIntegrationTest {

    @Test
    void fullExchangeJourney() throws Exception {
        // 1-2. Register two users
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");

        // 3. Alice lists a bicycle
        long bicycle = createItem(alice, "Bicycle", "EXCHANGE", "bikes", "GOOD", "Springfield");

        // 4-5. Bob browses and finds Alice's listing
        JsonNode results = getJson("/api/items?q=bicycle&excludeMine=true", bob);
        assertThat(results.get("totalElements").asInt()).isEqualTo(1);
        assertThat(results.at("/content/0/id").asLong()).isEqualTo(bicycle);

        // 6. Bob offers his gaming monitor; Carol offers her lamp (a competing offer)
        long monitor = createItem(bob, "Gaming monitor", "EXCHANGE");
        long lamp = createItem(carol, "Lamp", "EXCHANGE");
        long bobOffer = makeOffer(bob, bicycle, monitor);
        long carolOffer = makeOffer(carol, bicycle, lamp);

        // The viewer state reflects Bob's pending offer
        assertThat(getJson("/api/items/" + bicycle, bob).at("/viewer/pendingOfferId").asLong()).isEqualTo(bobOffer);

        // 7-8. Alice sees both incoming offers and a notification
        JsonNode incoming = getJson("/api/offers/incoming?status=PENDING", alice);
        assertThat(incoming.get("totalElements").asInt()).isEqualTo(2);
        assertThat(notificationTypes(alice)).contains("OFFER_RECEIVED");
        assertThat(getJson("/api/users/me/summary", alice).get("pendingIncomingOffers").asInt()).isEqualTo(2);

        // 9. Alice accepts Bob's offer
        JsonNode accepted = read(mvc.perform(auth(post("/api/offers/" + bobOffer + "/accept"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn());
        long exchangeId = accepted.get("exchangeId").asLong();

        // 10. Exchange record exists and is active for both participants
        JsonNode exchange = getJson("/api/exchanges/" + exchangeId, bob);
        assertThat(exchange.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(exchange.at("/owner/id").asLong()).isEqualTo(alice.id());
        assertThat(exchange.at("/requester/id").asLong()).isEqualTo(bob.id());
        assertThat(exchange.at("/ownerItem/id").asLong()).isEqualTo(bicycle);
        assertThat(exchange.at("/requesterItem/id").asLong()).isEqualTo(monitor);

        // 11. Both items are reserved and leave discovery
        assertThat(itemStatus(bicycle)).isEqualTo("RESERVED");
        assertThat(itemStatus(monitor)).isEqualTo("RESERVED");
        assertThat(getJson("/api/items?q=bicycle", null).get("totalElements").asInt()).isZero();

        // 12. Notifications: Bob was told about acceptance, Carol that her offer was closed
        assertThat(notificationTypes(bob)).contains("OFFER_ACCEPTED");
        assertThat(notificationTypes(carol)).contains("OFFER_REJECTED");

        // 13. The conflicting offer was closed and cannot be accepted
        assertThat(offerStatus(carolOffer)).isEqualTo("REJECTED");
        mvc.perform(auth(post("/api/offers/" + carolOffer + "/accept"), alice))
                .andExpect(status().isConflict());
        assertThat(itemStatus(lamp)).isEqualTo("AVAILABLE");

        // A third party cannot see or act on the exchange
        mvc.perform(auth(get("/api/exchanges/" + exchangeId), carol)).andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/exchanges/" + exchangeId + "/confirm"), carol)).andExpect(status().isNotFound());

        // 14. Completion requires both parties: one confirmation is not enough
        mvc.perform(auth(post("/api/exchanges/" + exchangeId + "/confirm"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ownerConfirmedAt").isNotEmpty());
        mvc.perform(auth(post("/api/exchanges/" + exchangeId + "/confirm"), alice))
                .andExpect(status().isConflict());
        assertThat(notificationTypes(bob)).contains("EXCHANGE_CONFIRMED");

        mvc.perform(auth(post("/api/exchanges/" + exchangeId + "/confirm"), bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
        assertThat(itemStatus(bicycle)).isEqualTo("COMPLETED");
        assertThat(itemStatus(monitor)).isEqualTo("COMPLETED");
        assertThat(notificationTypes(alice)).contains("EXCHANGE_COMPLETED");

        // History is kept and the public profile counts the completed exchange
        assertThat(getJson("/api/exchanges?status=COMPLETED", alice).get("totalElements").asInt()).isEqualTo(1);
        assertThat(getJson("/api/users/" + bob.id(), null).get("completedExchanges").asInt()).isEqualTo(1);
    }

    @Test
    void offerBusinessRulesAreEnforced() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        long bicycle = createItem(alice, "Bicycle", "EXCHANGE");
        long aliceOther = createItem(alice, "Helmet", "EXCHANGE");
        long monitor = createItem(bob, "Monitor", "EXCHANGE");
        long donation = createItem(alice, "Free sofa", "DONATION");

        // cannot offer on own listing
        offer(alice, bicycle, aliceOther).andExpect(status().isBadRequest());
        // cannot offer someone else's item
        offer(bob, bicycle, aliceOther).andExpect(status().isForbidden());
        // donation listings use the request workflow, not offers
        offer(bob, donation, monitor).andExpect(status().isBadRequest());
        // duplicate pending offers are rejected
        makeOffer(bob, bicycle, monitor);
        offer(bob, bicycle, monitor).andExpect(status().isConflict());
        // only the owner of the requested item can accept/reject
        long offerId = jdbc.queryForObject("select id from offers", Long.class);
        mvc.perform(auth(post("/api/offers/" + offerId + "/accept"), bob)).andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/offers/" + offerId + "/reject"), bob)).andExpect(status().isForbidden());
        // only the offerer can cancel
        mvc.perform(auth(post("/api/offers/" + offerId + "/cancel"), alice)).andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/offers/" + offerId + "/cancel"), bob))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(notificationTypes(alice)).contains("OFFER_CANCELLED");
        // a cancelled offer cannot be accepted; a new offer for the same pair is now allowed
        mvc.perform(auth(post("/api/offers/" + offerId + "/accept"), alice)).andExpect(status().isConflict());
        makeOffer(bob, bicycle, monitor);
    }

    @Test
    void unavailableItemsCannotBeOfferedOrRequested() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        long bicycle = createItem(alice, "Bicycle", "EXCHANGE");
        long monitor = createItem(bob, "Monitor", "EXCHANGE");
        mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/items/" + monitor + "/status"), bob)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"UNAVAILABLE\"}"))
                .andExpect(status().isOk());
        offer(bob, bicycle, monitor).andExpect(status().isConflict());
    }

    @Test
    void rejectingAnOfferNotifiesOffererAndKeepsItemsAvailable() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        long bicycle = createItem(alice, "Bicycle", "EXCHANGE");
        long monitor = createItem(bob, "Monitor", "EXCHANGE");
        long offerId = makeOffer(bob, bicycle, monitor);
        mvc.perform(auth(post("/api/offers/" + offerId + "/reject"), alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(itemStatus(bicycle)).isEqualTo("AVAILABLE");
        assertThat(itemStatus(monitor)).isEqualTo("AVAILABLE");
        assertThat(notificationTypes(bob)).contains("OFFER_REJECTED");
        assertThat(getJson("/api/offers/outgoing?status=REJECTED", bob).get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void acceptingClosesOffersThatUseTheSameOfferedItemElsewhere() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser dave = register("Dave");
        long bicycle = createItem(alice, "Bicycle", "EXCHANGE");
        long guitar = createItem(dave, "Guitar", "EXCHANGE");
        long monitor = createItem(bob, "Monitor", "EXCHANGE");
        long toAlice = makeOffer(bob, bicycle, monitor);
        long toDave = makeOffer(bob, guitar, monitor);   // same monitor offered to two people

        mvc.perform(auth(post("/api/offers/" + toAlice + "/accept"), alice)).andExpect(status().isOk());
        assertThat(offerStatus(toDave)).isEqualTo("CANCELLED");
        assertThat(notificationTypes(dave)).contains("OFFER_CANCELLED");
        mvc.perform(auth(post("/api/offers/" + toDave + "/accept"), dave)).andExpect(status().isConflict());
        assertThat(itemStatus(guitar)).isEqualTo("AVAILABLE");
    }

    @Test
    void cancellingAnExchangeReleasesBothItems() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        long bicycle = createItem(alice, "Bicycle", "EXCHANGE");
        long monitor = createItem(bob, "Monitor", "EXCHANGE");
        long offerId = makeOffer(bob, bicycle, monitor);
        long exchangeId = read(mvc.perform(auth(post("/api/offers/" + offerId + "/accept"), alice))
                .andReturn()).get("exchangeId").asLong();

        // reserved items cannot be deleted or edited out from under the exchange
        mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/items/" + bicycle), alice)).andExpect(status().isConflict());

        mvc.perform(auth(post("/api/exchanges/" + exchangeId + "/cancel"), bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy.id").value(bob.id()));
        assertThat(itemStatus(bicycle)).isEqualTo("AVAILABLE");
        assertThat(itemStatus(monitor)).isEqualTo("AVAILABLE");
        assertThat(notificationTypes(alice)).contains("EXCHANGE_CANCELLED");
        mvc.perform(auth(post("/api/exchanges/" + exchangeId + "/confirm"), alice)).andExpect(status().isConflict());
    }

    @Test
    void deletingAListingClosesItsPendingOffers() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        long bicycle = createItem(alice, "Bicycle", "EXCHANGE");
        long monitor = createItem(bob, "Monitor", "EXCHANGE");
        long offerId = makeOffer(bob, bicycle, monitor);
        mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/items/" + bicycle), alice)).andExpect(status().isNoContent());
        assertThat(offerStatus(offerId)).isEqualTo("REJECTED");
        assertThat(notificationTypes(bob)).contains("OFFER_REJECTED");
    }

    @Test
    void notificationsCanBeMarkedRead() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        long bicycle = createItem(alice, "Bicycle", "EXCHANGE");
        long monitor = createItem(bob, "Monitor", "EXCHANGE");
        makeOffer(bob, bicycle, monitor);

        assertThat(getJson("/api/notifications/unread-count", alice).get("count").asInt()).isEqualTo(1);
        long id = getJson("/api/notifications", alice).at("/content/0/id").asLong();
        // other users cannot touch someone else's notification
        mvc.perform(auth(post("/api/notifications/" + id + "/read"), bob)).andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/notifications/" + id + "/read"), alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.read").value(true));
        assertThat(getJson("/api/notifications/unread-count", alice).get("count").asInt()).isZero();
        mvc.perform(auth(post("/api/notifications/read-all"), alice)).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions offer(TestUser user, long requested, long offered)
            throws Exception {
        return mvc.perform(auth(post("/api/offers"), user).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("requestedItemId", requested, "offeredItemId", offered))));
    }

    private java.util.List<String> notificationTypes(TestUser user) throws Exception {
        var types = new java.util.ArrayList<String>();
        getJson("/api/notifications?size=50", user).get("content").forEach(n -> types.add(n.get("type").asText()));
        return types;
    }
}
