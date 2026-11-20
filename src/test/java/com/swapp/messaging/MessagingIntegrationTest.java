package com.swapp.messaging;

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

class MessagingIntegrationTest extends AbstractIntegrationTest {

    @Test
    void usersCanConverseAndTrackUnreadMessages() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        long bicycle = createItem(alice, "Bicycle", "EXCHANGE");

        JsonNode conversation = read(mvc.perform(auth(post("/api/conversations"), bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("recipientId", alice.id(), "itemId", bicycle,
                                "body", "Hi! Is the bicycle still available?"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.otherUser.id").value(alice.id()))
                .andReturn());
        long conversationId = conversation.get("id").asLong();

        // Starting again reuses the same conversation
        long again = read(mvc.perform(auth(post("/api/conversations"), bob).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("recipientId", alice.id(), "body", "Also, what size is it?"))))
                .andReturn()).get("id").asLong();
        assertThat(again).isEqualTo(conversationId);

        assertThat(getJson("/api/conversations/unread-count", alice).get("count").asInt()).isEqualTo(2);
        JsonNode list = getJson("/api/conversations", alice);
        assertThat(list.at("/content/0/unreadCount").asInt()).isEqualTo(2);
        assertThat(list.at("/content/0/lastMessage/body").asText()).isEqualTo("Also, what size is it?");

        mvc.perform(auth(post("/api/conversations/" + conversationId + "/messages"), alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Yes, it is! 54cm frame.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderId").value(alice.id()));

        JsonNode history = getJson("/api/conversations/" + conversationId + "/messages", alice);
        assertThat(history.size()).isEqualTo(3);
        assertThat(history.get(0).at("/item/id").asLong()).isEqualTo(bicycle);
        assertThat(history.get(2).get("body").asText()).isEqualTo("Yes, it is! 54cm frame.");
        assertThat(history.get(0).get("createdAt").asText()).isNotBlank();

        mvc.perform(auth(post("/api/conversations/" + conversationId + "/read"), alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(2));
        assertThat(getJson("/api/conversations/unread-count", alice).get("count").asInt()).isZero();
        assertThat(getJson("/api/conversations/unread-count", bob).get("count").asInt()).isEqualTo(1);

        // Paging back through history
        long newest = history.get(2).get("id").asLong();
        JsonNode older = getJson("/api/conversations/" + conversationId + "/messages?before=" + newest + "&size=1", bob);
        assertThat(older.size()).isEqualTo(1);
        assertThat(older.get(0).get("body").asText()).isEqualTo("Also, what size is it?");
    }

    @Test
    void onlyParticipantsCanAccessAConversation() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser mallory = register("Mallory");
        long conversationId = read(mvc.perform(auth(post("/api/conversations"), bob)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("recipientId", alice.id(), "body", "Private hello")))).andReturn())
                .get("id").asLong();

        mvc.perform(auth(get("/api/conversations/" + conversationId + "/messages"), mallory))
                .andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/conversations/" + conversationId + "/messages"), mallory)
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"intrusion\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/conversations/" + conversationId + "/read"), mallory))
                .andExpect(status().isNotFound());
        assertThat(getJson("/api/conversations", mallory).get("totalElements").asInt()).isZero();
    }

    @Test
    void invalidMessagesAreRejected() throws Exception {
        TestUser alice = register("Alice");
        mvc.perform(auth(post("/api/conversations"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("recipientId", alice.id(), "body", "talking to myself"))))
                .andExpect(status().isBadRequest());
        TestUser bob = register("Bob");
        mvc.perform(auth(post("/api/conversations"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("recipientId", bob.id(), "body", "   "))))
                .andExpect(status().isBadRequest());
    }
}
