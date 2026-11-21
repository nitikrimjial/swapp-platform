package com.swapp.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Boots the full application against the real PostgreSQL test database. Each test starts from empty domain tables
 * (categories and Flyway history are preserved).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RecordingEmailSender.Config.class)
public abstract class AbstractIntegrationTest {

    private static final String[] TABLES = {"reports", "user_blocks", "messages", "conversations", "notifications", "donation_requests",
            "exchanges", "offers", "item_images", "stored_files", "items", "user_tokens", "users"};

    @Autowired
    protected RecordingEmailSender mail;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE " + String.join(", ", TABLES) + " RESTART IDENTITY CASCADE");
        mail.clear();
    }

    // ---------------------------------------------------------------- helpers

    protected record TestUser(long id, String token, String email) {
    }

    protected TestUser register(String name) throws Exception {
        String email = name.toLowerCase().replace(' ', '.') + "@example.com";
        MvcResult result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("acceptTerms", true, "email", email, "password", "Passw0rd!", "displayName", name,
                                "location", "Springfield"))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(result);
        return new TestUser(body.at("/user/id").asLong(), body.get("accessToken").asText(), email);
    }

    protected long categoryId(String slug) {
        return jdbc.queryForObject("select id from categories where slug = ?", Long.class, slug);
    }

    protected long createItem(TestUser owner, String title, String listingType) throws Exception {
        return createItem(owner, title, listingType, "electronics", "GOOD", "Springfield");
    }

    protected long createItem(TestUser owner, String title, String listingType, String categorySlug, String condition,
                              String location) throws Exception {
        return createItemAt(owner, title, listingType, categorySlug, condition, location, null, null);
    }

    protected long createItemAt(TestUser owner, String title, String listingType, String categorySlug,
                                String condition, String location, Double lat, Double lng) throws Exception {
        Map<String, Object> payload = new HashMap<>(Map.of("title", title,
                "description", "A well looked-after " + title + " in working order.",
                "categoryId", categoryId(categorySlug), "condition", condition, "listingType", listingType,
                "location", location));
        if (lat != null) {
            payload.put("latitude", lat);
            payload.put("longitude", lng);
        }
        MvcResult result = mvc.perform(auth(post("/api/items"), owner).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(payload)))
                .andExpect(status().isCreated()).andReturn();
        return read(result).get("id").asLong();
    }

    protected long makeOffer(TestUser offerer, long requestedItemId, long offeredItemId) throws Exception {
        MvcResult result = mvc.perform(auth(post("/api/offers"), offerer).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("requestedItemId", requestedItemId, "offeredItemId", offeredItemId,
                                "message", "Would you swap?"))))
                .andExpect(status().isCreated()).andReturn();
        return read(result).get("id").asLong();
    }

    protected String itemStatus(long itemId) {
        return jdbc.queryForObject("select status from items where id = ?", String.class, itemId);
    }

    protected String offerStatus(long offerId) {
        return jdbc.queryForObject("select status from offers where id = ?", String.class, offerId);
    }

    protected JsonNode getJson(String url, TestUser user) throws Exception {
        return read(mvc.perform(auth(get(url), user)).andExpect(status().isOk()).andReturn());
    }

    protected MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder, TestUser user) {
        return user == null ? builder : builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token());
    }

    protected String toJson(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    protected JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
