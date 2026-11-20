package com.swapp.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.swapp.support.AbstractIntegrationTest;

/** "Near me" radius search, distance sorting and the extra discovery filters, against real PostgreSQL. */
class NearbySearchIntegrationTest extends AbstractIntegrationTest {

    // Origin: Koramangala, Bengaluru
    static final String ORIGIN = "lat=12.9352&lng=77.6245";

    TestUser alice;
    TestUser bob;
    long hsr;
    long indiranagar;
    long whitefield;
    long mysuru;
    long noCoordinates;

    @BeforeEach
    void listings() throws Exception {
        alice = register("Alice");
        bob = register("Bob");
        hsr = createItemAt(alice, "Bicycle in HSR", "EXCHANGE", "bikes", "GOOD", "HSR Layout", 12.9116, 77.6389);           // ~3.1 km
        indiranagar = createItemAt(alice, "Lamp in Indiranagar", "GIVEAWAY", "home", "LIKE_NEW", "Indiranagar", 12.9719, 77.6412); // ~4.5 km
        whitefield = createItemAt(bob, "Desk in Whitefield", "EXCHANGE", "furniture", "FAIR", "Whitefield", 12.9698, 77.7500);     // ~14 km
        mysuru = createItemAt(bob, "Books in Mysuru", "DONATION", "books", "GOOD", "Mysuru", 12.2958, 76.6394);              // ~130 km
        noCoordinates = createItem(bob, "Guitar somewhere", "EXCHANGE", "music", "NEW", "Unknown");
    }

    @Test
    void radiusFilterReturnsOnlyListingsWithinDistance() throws Exception {
        assertThat(titles(search(ORIGIN + "&radiusKm=5"))).containsExactlyInAnyOrder("Bicycle in HSR", "Lamp in Indiranagar");
        assertThat(titles(search(ORIGIN + "&radiusKm=20"))).containsExactlyInAnyOrder("Bicycle in HSR",
                "Lamp in Indiranagar", "Desk in Whitefield");
        assertThat(titles(search(ORIGIN + "&radiusKm=1"))).isEmpty();
    }

    @Test
    void nearestFirstSortsByDistanceAndReportsDistance() throws Exception {
        JsonNode page = search(ORIGIN + "&sort=distance");
        assertThat(titles(page)).containsExactly("Bicycle in HSR", "Lamp in Indiranagar", "Desk in Whitefield",
                "Books in Mysuru");
        assertThat(page.at("/content/0/distanceKm").asDouble()).isCloseTo(3.1, within(0.3));
        assertThat(page.at("/content/1/distanceKm").asDouble()).isCloseTo(4.5, within(0.3));
        assertThat(page.at("/content/2/distanceKm").asDouble()).isCloseTo(14.1, within(0.5));
        assertThat(page.at("/content/3/distanceKm").asDouble()).isGreaterThan(100);
        assertThat(page.get("totalElements").asInt()).isEqualTo(4);
    }

    @Test
    void radiusCombinesWithOtherFiltersAndPagination() throws Exception {
        assertThat(titles(search(ORIGIN + "&radiusKm=20&type=EXCHANGE&sort=distance")))
                .containsExactly("Bicycle in HSR", "Desk in Whitefield");
        JsonNode first = search(ORIGIN + "&radiusKm=50&sort=distance&size=2&page=0");
        assertThat(first.get("totalElements").asInt()).isEqualTo(3);
        assertThat(titles(first)).containsExactly("Bicycle in HSR", "Lamp in Indiranagar");
        assertThat(titles(search(ORIGIN + "&radiusKm=50&sort=distance&size=2&page=1"))).containsExactly("Desk in Whitefield");
    }

    @Test
    void searchWithoutOriginHasNoDistance() throws Exception {
        JsonNode page = search("");
        assertThat(page.get("totalElements").asInt()).isEqualTo(5);
        assertThat(page.at("/content/0/distanceKm").isNull()).isTrue();
        // sort=distance without a location falls back to newest first
        assertThat(titles(search("sort=distance")).get(0)).isEqualTo("Guitar somewhere");
    }

    @Test
    void invalidLocationParametersAreRejected() throws Exception {
        mvc.perform(get("/api/items?lat=12.9")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/items?radiusKm=5")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/items?lat=120&lng=10")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/items?" + ORIGIN + "&radiusKm=500")).andExpect(status().isBadRequest());
    }

    @Test
    void coordinatesAreRoundedAndOnlyVisibleToTheOwner() throws Exception {
        Double storedLat = jdbc.queryForObject("select latitude from items where id = ?", Double.class, hsr);
        assertThat(storedLat).isEqualTo(12.912); // 12.9116 rounded to ~100 m precision
        mvc.perform(get("/api/items/" + hsr))
                .andExpect(jsonPath("$.hasCoordinates").value(true))
                .andExpect(jsonPath("$.latitude").doesNotExist());
        mvc.perform(auth(get("/api/items/" + hsr), alice))
                .andExpect(jsonPath("$.latitude").value(12.912));
        mvc.perform(get("/api/items/" + noCoordinates)).andExpect(jsonPath("$.hasCoordinates").value(false));
    }

    @Test
    void conditionPostedWithinAndPhotoFilters() throws Exception {
        assertThat(titles(search("condition=NEW&condition=LIKE_NEW"))).containsExactlyInAnyOrder("Guitar somewhere",
                "Lamp in Indiranagar");

        jdbc.update("update items set created_at = now() - interval '10 days' where id = ?", mysuru);
        assertThat(titles(search("postedWithinDays=7"))).doesNotContain("Books in Mysuru").hasSize(4);
        assertThat(titles(search("postedWithinDays=30"))).hasSize(5);

        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");
        mvc.perform(auth(multipart("/api/items/" + whitefield + "/images")
                .file(new MockMultipartFile("files", "a.png", "image/png", png)), bob)).andExpect(status().isOk());
        assertThat(titles(search("withPhotos=true"))).containsExactly("Desk in Whitefield");
    }

    @Test
    void excludeMineHidesOwnListings() throws Exception {
        JsonNode page = getJson("/api/items?excludeMine=true", alice);
        assertThat(titles(page)).containsExactlyInAnyOrder("Desk in Whitefield", "Books in Mysuru", "Guitar somewhere");
    }

    @Test
    void profileHomeLocationIsSavedRounded() throws Exception {
        mvc.perform(auth(put("/api/users/me"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("displayName", "Alice", "location", "Koramangala, Bengaluru",
                                "latitude", 12.93521, "longitude", 77.62449))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(12.935))
                .andExpect(jsonPath("$.longitude").value(77.624));
        mvc.perform(get("/api/users/" + alice.id())).andExpect(jsonPath("$.latitude").doesNotExist());
    }

    private JsonNode search(String query) throws Exception {
        return read(mvc.perform(get("/api/items?" + query)).andExpect(status().isOk()).andReturn());
    }

    private static List<String> titles(JsonNode page) {
        List<String> list = new ArrayList<>();
        page.get("content").forEach(n -> list.add(n.get("title").asText()));
        return list;
    }
}
