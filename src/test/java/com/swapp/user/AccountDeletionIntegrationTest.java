package com.swapp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.swapp.support.AbstractIntegrationTest;

/** Self-service account deletion (erasure) and personal data export. */
class AccountDeletionIntegrationTest extends AbstractIntegrationTest {

    @Test
    void deletionErasesPersonalDataClosesOpenActivityAndKeepsTheOtherSidesHistory() throws Exception {
        TestUser leaver = register("Leaving Member");
        TestUser other = register("Other Member");
        long leaverBike = createItem(leaver, "Old bike", "EXCHANGE");
        long leaverLamp = createItem(leaver, "Desk lamp", "EXCHANGE");
        long otherBook = createItem(other, "Cookbook", "EXCHANGE");
        long otherSofa = createItem(other, "Free sofa", "GIVEAWAY");
        long leaverChair = createItem(leaver, "Free chair", "GIVEAWAY");

        long offerMade = makeOffer(leaver, otherBook, leaverBike);       // leaver -> other
        long offerReceived = makeOffer(other, leaverLamp, otherBook);    // other -> leaver
        long requestMade = donationRequest(leaver, otherSofa);
        long requestReceived = donationRequest(other, leaverChair);
        long conversation = startConversation(other, leaver, "Is the bike still available?");
        mvc.perform(auth(post("/api/conversations/" + conversation + "/messages"), leaver)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("body", "Yes it is!"))))
                .andExpect(status().isCreated());
        jdbc.update("update users set bio = 'Hello', latitude = 12.9, longitude = 77.6 where id = ?", leaver.id());

        deleteAccount(leaver, "Passw0rd!").andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", Matchers.containsString("Max-Age=0")));

        // Personal data is gone; the row stays for history.
        Map<String, Object> row = jdbc.queryForMap("select * from users where id = ?", leaver.id());
        assertThat(row.get("email")).isEqualTo("deleted-" + leaver.id() + "@deleted.invalid");
        assertThat(row.get("display_name")).isEqualTo("Deleted member");
        assertThat(row.get("bio")).isNull();
        assertThat(row.get("latitude")).isNull();
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from notifications where recipient_id = ?", Long.class,
                leaver.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_tokens where user_id = ?", Long.class,
                leaver.id())).isZero();

        // Listings removed, open activity closed.
        assertThat(itemStatus(leaverBike)).isEqualTo("REMOVED");
        assertThat(itemStatus(leaverLamp)).isEqualTo("REMOVED");
        assertThat(itemStatus(leaverChair)).isEqualTo("REMOVED");
        assertThat(offerStatus(offerMade)).isEqualTo("CANCELLED");
        assertThat(offerStatus(offerReceived)).isEqualTo("REJECTED");
        assertThat(requestStatus(requestMade)).isEqualTo("CANCELLED");
        assertThat(requestStatus(requestReceived)).isEqualTo("DECLINED");
        assertThat(itemStatus(otherBook)).isEqualTo("AVAILABLE");

        // The session is dead, the old credentials are useless, and the public profile is gone.
        mvc.perform(auth(get("/api/users/me"), leaver)).andExpect(status().isUnauthorized());
        login(leaver.email(), "Passw0rd!").andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/" + leaver.id())).andExpect(status().isNotFound());

        // The other member keeps the conversation, shown as from "Deleted member", but cannot write into it.
        JsonNode chats = getJson("/api/conversations", other);
        assertThat(chats.at("/content/0/otherUser/displayName").asText()).isEqualTo("Deleted member");
        assertThat(getJson("/api/conversations/" + conversation + "/messages", other)).hasSize(2);
        mvc.perform(auth(post("/api/conversations/" + conversation + "/messages"), other)
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("body", "Hello?"))))
                .andExpect(status().isConflict());
        mvc.perform(auth(post("/api/conversations"), other).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("recipientId", leaver.id(), "body", "Hi"))))
                .andExpect(status().isNotFound());

        // They were told what happened, and the leaver got a confirmation at their real address.
        assertThat(jdbc.queryForObject("select count(*) from notifications where recipient_id = ?", Long.class,
                other.id())).isGreaterThanOrEqualTo(3);
        assertThat(mail.await(leaver.email(), "account-deleted").text()).contains("has been deleted");

        // Members are counted without deleted accounts, and the email address is free to use again.
        mvc.perform(get("/api/stats")).andExpect(jsonPath("$.members").value(1));
        register("Leaving Member");
    }

    @Test
    void aWrongPasswordChangesNothing() throws Exception {
        TestUser member = register("Careful Member");
        long item = createItem(member, "Kettle", "EXCHANGE");
        deleteAccount(member, "WrongPassw0rd").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Your password is incorrect"));
        deleteAccount(member, "").andExpect(status().isBadRequest());
        assertThat(itemStatus(item)).isEqualTo("AVAILABLE");
        mvc.perform(auth(get("/api/users/me"), member)).andExpect(status().isOk());
    }

    @Test
    void deletionWaitsUntilActiveExchangesAreFinishedOrCancelled() throws Exception {
        TestUser leaver = register("Leaving Member");
        TestUser other = register("Other Member");
        long bike = createItem(leaver, "Old bike", "EXCHANGE");
        long book = createItem(other, "Cookbook", "EXCHANGE");
        long offer = makeOffer(other, bike, book);
        JsonNode accepted = read(mvc.perform(auth(post("/api/offers/" + offer + "/accept"), leaver))
                .andExpect(status().isOk()).andReturn());
        long exchangeId = accepted.get("exchangeId").asLong();

        deleteAccount(leaver, "Passw0rd!").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("in progress")));

        mvc.perform(auth(post("/api/exchanges/" + exchangeId + "/cancel"), leaver)).andExpect(status().isOk());
        deleteAccount(leaver, "Passw0rd!").andExpect(status().isNoContent());
        assertThat(itemStatus(book)).isEqualTo("AVAILABLE");
    }

    @Test
    void deletionWaitsForAnAcceptedHandOverTheMemberIsReceiving() throws Exception {
        TestUser leaver = register("Leaving Member");
        TestUser giver = register("Giver Member");
        long sofa = createItem(giver, "Free sofa", "GIVEAWAY");
        long request = donationRequest(leaver, sofa);
        mvc.perform(auth(post("/api/donation-requests/" + request + "/accept"), giver)).andExpect(status().isOk());

        deleteAccount(leaver, "Passw0rd!").andExpect(status().isConflict());
    }

    @Test
    void theDatabaseRejectsADeletedAccountThatStillHoldsPersonalData() {
        jdbc.update("insert into users (email, password_hash, display_name, created_at, updated_at) "
                + "values ('x@example.com', 'h', 'X', now(), now())");
        assertThatThrownBy(() -> jdbc.update("update users set deleted_at = now() where email = 'x@example.com'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void exportContainsTheMembersOwnDataAsADownload() throws Exception {
        TestUser asha = register("Asha Rao");
        TestUser ben = register("Ben Ito");
        long lamp = createItem(asha, "Desk lamp", "EXCHANGE");
        long book = createItem(ben, "Cookbook", "EXCHANGE");
        makeOffer(asha, book, lamp);
        startConversation(asha, ben, "Hi Ben, interested in the cookbook");

        var result = mvc.perform(auth(get("/api/users/me/export"), asha))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("attachment; filename=\"swapp-data-")))
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andReturn();
        JsonNode export = read(result);
        assertThat(export.at("/profile/email").asText()).isEqualTo(asha.email());
        assertThat(export.at("/listings/0/title").asText()).isEqualTo("Desk lamp");
        assertThat(export.at("/offersMade")).hasSize(1);
        assertThat(export.at("/conversations/0/with").asText()).isEqualTo("Ben Ito");
        assertThat(export.at("/conversations/0/messages/0/fromMe").asBoolean()).isTrue();
        // Other members' private details are never included.
        assertThat(result.getResponse().getContentAsString()).doesNotContain(ben.email());

        mvc.perform(get("/api/users/me/export")).andExpect(status().isUnauthorized());
    }

    private ResultActions deleteAccount(TestUser user, String password) throws Exception {
        return mvc.perform(auth(delete("/api/users/me"), user).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("password", password))));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", email, "password", password))));
    }

    private long donationRequest(TestUser requester, long itemId) throws Exception {
        return read(mvc.perform(auth(post("/api/donation-requests"), requester).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("itemId", itemId, "message", "I'd love this"))))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private long startConversation(TestUser from, TestUser to, String body) throws Exception {
        return read(mvc.perform(auth(post("/api/conversations"), from).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("recipientId", to.id(), "body", body))))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private String requestStatus(long id) {
        return jdbc.queryForObject("select status from donation_requests where id = ?", String.class, id);
    }
}
