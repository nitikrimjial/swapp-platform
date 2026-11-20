package com.swapp.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.swapp.support.AbstractIntegrationTest;

class ItemIntegrationTest extends AbstractIntegrationTest {

    /** Smallest valid PNG (1x1 transparent pixel). */
    static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");

    @Test
    void ownerCanCreateReadUpdateAndDeleteListing() throws Exception {
        TestUser alice = register("Alice");
        long id = createItem(alice, "Road bicycle", "EXCHANGE");

        mvc.perform(get("/api/items/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Road bicycle"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.owner.id").value(alice.id()))
                .andExpect(jsonPath("$.owner.email").doesNotExist());

        var update = Map.of("title", "Road bicycle (54cm)", "description", "Freshly serviced, new brake pads.",
                "categoryId", categoryId("bikes"), "condition", "LIKE_NEW", "listingType", "EXCHANGE",
                "location", "Shelbyville");
        mvc.perform(auth(put("/api/items/" + id), alice).contentType(MediaType.APPLICATION_JSON).content(toJson(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Road bicycle (54cm)"))
                .andExpect(jsonPath("$.category.slug").value("bikes"))
                .andExpect(jsonPath("$.condition").value("LIKE_NEW"));

        mvc.perform(auth(delete("/api/items/" + id), alice)).andExpect(status().isNoContent());
        mvc.perform(get("/api/items/" + id)).andExpect(status().isNotFound());
        assertThat(itemStatus(id)).isEqualTo("REMOVED");
    }

    @Test
    void onlyOwnerCanModifyListing() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        long id = createItem(alice, "Desk lamp", "EXCHANGE");
        var update = Map.of("title", "Hacked", "description", "Not my listing at all!", "categoryId",
                categoryId("home"), "condition", "GOOD", "listingType", "EXCHANGE", "location", "X");

        mvc.perform(auth(put("/api/items/" + id), bob).contentType(MediaType.APPLICATION_JSON).content(toJson(update)))
                .andExpect(status().isForbidden());
        mvc.perform(auth(delete("/api/items/" + id), bob)).andExpect(status().isForbidden());
        mvc.perform(auth(patch("/api/items/" + id + "/status"), bob).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"UNAVAILABLE\"}")).andExpect(status().isForbidden());
        mvc.perform(auth(multipart("/api/items/" + id + "/images")
                .file(new MockMultipartFile("files", "a.png", "image/png", PNG)), bob))
                .andExpect(status().isForbidden());
        assertThat(itemStatus(id)).isEqualTo("AVAILABLE");
    }

    @Test
    void anonymousUsersCanBrowseButNotCreate() throws Exception {
        TestUser alice = register("Alice");
        createItem(alice, "Guitar", "EXCHANGE");
        mvc.perform(get("/api/items")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validationErrorsAreReported() throws Exception {
        TestUser alice = register("Alice");
        mvc.perform(auth(post("/api/items"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "x", "description", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").exists())
                .andExpect(jsonPath("$.fieldErrors.description").exists())
                .andExpect(jsonPath("$.fieldErrors.categoryId").exists())
                .andExpect(jsonPath("$.fieldErrors.listingType").exists());
    }

    @Test
    void searchFiltersAreAppliedByTheDatabase() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        createItem(alice, "Gaming monitor", "EXCHANGE", "electronics", "GOOD", "Springfield");
        createItem(alice, "Oak bookshelf", "DONATION", "furniture", "FAIR", "Shelbyville");
        createItem(bob, "Kids bicycle", "GIVEAWAY", "bikes", "LIKE_NEW", "Springfield");
        createItem(bob, "Looking for a tent", "REQUEST", "sports", "GOOD", "Capital City");

        assertThat(search("").get("totalElements").asInt()).isEqualTo(4);
        assertThat(titles(search("q=monitor"))).containsExactly("Gaming monitor");
        assertThat(titles(search("q=BICYCLE"))).containsExactly("Kids bicycle");
        assertThat(titles(search("categoryId=" + categoryId("furniture")))).containsExactly("Oak bookshelf");
        assertThat(titles(search("condition=LIKE_NEW"))).containsExactly("Kids bicycle");
        assertThat(titles(search("type=DONATION&type=GIVEAWAY"))).containsExactlyInAnyOrder("Oak bookshelf",
                "Kids bicycle");
        assertThat(titles(search("type=REQUEST"))).containsExactly("Looking for a tent");
        assertThat(titles(search("location=springfield"))).containsExactlyInAnyOrder("Gaming monitor", "Kids bicycle");
        assertThat(titles(search("ownerId=" + bob.id() + "&type=GIVEAWAY"))).containsExactly("Kids bicycle");
        // newest first by default, oldest first on request
        assertThat(titles(search("sort=newest")).get(0)).isEqualTo("Looking for a tent");
        assertThat(titles(search("sort=oldest")).get(0)).isEqualTo("Gaming monitor");
        // LIKE wildcards in user input are treated literally
        assertThat(search("q=%25").get("totalElements").asInt()).isZero();
    }

    @Test
    void paginationIsBounded() throws Exception {
        TestUser alice = register("Alice");
        for (int i = 0; i < 5; i++) {
            createItem(alice, "Item number " + i, "EXCHANGE");
        }
        JsonNode page = search("size=2&page=1");
        assertThat(page.get("content").size()).isEqualTo(2);
        assertThat(page.get("totalPages").asInt()).isEqualTo(3);
        assertThat(search("size=1000").get("size").asInt()).isEqualTo(50);
    }

    @Test
    void unavailableListingsAreHiddenFromDiscovery() throws Exception {
        TestUser alice = register("Alice");
        long id = createItem(alice, "Camera", "EXCHANGE");
        mvc.perform(auth(patch("/api/items/" + id + "/status"), alice).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"UNAVAILABLE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNAVAILABLE"));
        assertThat(search("").get("totalElements").asInt()).isZero();
        // ... but still visible in the owner's own listings
        mvc.perform(auth(get("/api/users/me/items"), alice))
                .andExpect(jsonPath("$.content", hasSize(1)));
        // owners cannot force arbitrary statuses
        mvc.perform(auth(patch("/api/items/" + id + "/status"), alice).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"COMPLETED\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void imagesCanBeUploadedServedReorderedAndDeleted() throws Exception {
        TestUser alice = register("Alice");
        long id = createItem(alice, "Vintage radio", "EXCHANGE");
        JsonNode item = read(mvc.perform(auth(multipart("/api/items/" + id + "/images")
                        .file(new MockMultipartFile("files", "one.png", "image/png", PNG))
                        .file(new MockMultipartFile("files", "two.png", "image/png", PNG)), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images", hasSize(2)))
                .andReturn());

        String url = item.at("/images/0/url").asText();
        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG));

        long first = item.at("/images/0/id").asLong();
        long second = item.at("/images/1/id").asLong();
        mvc.perform(auth(put("/api/items/" + id + "/images/order"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("imageIds", new long[] {second, first}))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images[0].id").value(second));

        mvc.perform(auth(delete("/api/items/" + id + "/images/" + second), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images", hasSize(1)));
    }

    @Test
    void nonImageUploadsAreRejectedRegardlessOfDeclaredType() throws Exception {
        TestUser alice = register("Alice");
        long id = createItem(alice, "Speaker", "EXCHANGE");
        byte[] script = "<script>alert(1)</script>".getBytes();
        mvc.perform(auth(multipart("/api/items/" + id + "/images")
                        .file(new MockMultipartFile("files", "evil.png", "image/png", script)), alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only JPEG, PNG, GIF and WebP images are supported"));
        assertThat(jdbc.queryForObject("select count(*) from stored_files", Integer.class)).isZero();
    }

    @Test
    void imageCountIsLimited() throws Exception {
        TestUser alice = register("Alice");
        long id = createItem(alice, "Sofa", "EXCHANGE");
        var request = multipart("/api/items/" + id + "/images");
        for (int i = 0; i < 7; i++) {
            request.file(new MockMultipartFile("files", i + ".png", "image/png", PNG));
        }
        mvc.perform(auth(request, alice)).andExpect(status().isBadRequest());
    }

    private JsonNode search(String query) throws Exception {
        return read(mvc.perform(get("/api/items?" + query)).andExpect(status().isOk()).andReturn());
    }

    private static java.util.List<String> titles(JsonNode page) {
        var list = new java.util.ArrayList<String>();
        page.get("content").forEach(n -> list.add(n.get("title").asText()));
        return list;
    }
}
