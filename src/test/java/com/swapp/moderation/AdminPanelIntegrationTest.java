package com.swapp.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.swapp.support.AbstractIntegrationTest;

/** The admin panel: dashboard numbers, member and listing search, direct takedowns and the audit log. */
class AdminPanelIntegrationTest extends AbstractIntegrationTest {

    // ---------------------------------------------------------------- access

    @Test
    void everyAdminPanelEndpointIsForAdminsOnly() throws Exception {
        TestUser asha = register("Asha Rao");
        long lamp = createItem(asha, "Lamp", "EXCHANGE");
        for (String url : new String[] {"/api/admin/overview", "/api/admin/users", "/api/admin/users/" + asha.id(),
                "/api/admin/items", "/api/admin/audit"}) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(auth(get(url), asha)).andExpect(status().isForbidden());
        }
        removeListing(asha, lamp, "Not allowed").andExpect(status().isForbidden());
        assertThat(itemStatus(lamp)).isEqualTo("AVAILABLE");
    }

    // ---------------------------------------------------------------- dashboard

    @Test
    void theOverviewCountsEachDayInTheViewersTimeZone() throws Exception {
        TestUser mod = admin();
        TestUser asha = register("Asha Rao");
        TestUser ben = register("Ben Ito");
        createItem(asha, "Lamp", "EXCHANGE");
        createItem(ben, "Cookbook", "EXCHANGE");
        createItem(ben, "Old kettle", "GIVEAWAY");
        report(asha, "USER", ben.id(), "SPAM");
        // Someone who joined 40 days ago is outside a 30-day chart but still counts as a member.
        jdbc.update("update users set created_at = now() - interval '40 days' where id = ?", asha.id());

        JsonNode overview = getJson("/api/admin/overview?days=30&tz=Asia/Kolkata", mod);

        assertThat(overview.get("timeZone").asText()).isEqualTo("Asia/Kolkata");
        assertThat(overview.at("/totals/members").asLong()).isEqualTo(3);
        assertThat(overview.at("/totals/activeListings").asLong()).isEqualTo(3);
        assertThat(overview.at("/totals/openReports").asLong()).isEqualTo(1);
        assertThat(overview.at("/totals/suspendedMembers").asLong()).isZero();
        JsonNode days = overview.get("days");
        assertThat(days).hasSize(30);
        JsonNode today = days.get(29);
        assertThat(today.get("date").asText()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")).toString());
        assertThat(today.get("signUps").asLong()).isEqualTo(2); // the moderator and Ben
        assertThat(today.get("listings").asLong()).isEqualTo(3);
        assertThat(today.get("reports").asLong()).isEqualTo(1);
        assertThat(today.get("completedSwaps").asLong()).isZero();
        long signUpsOnChart = 0;
        for (JsonNode day : days) {
            signUpsOnChart += day.get("signUps").asLong();
        }
        assertThat(signUpsOnChart).isEqualTo(2);
    }

    @Test
    void theOverviewBoundsItsRangeAndFallsBackToUtc() throws Exception {
        TestUser mod = admin();
        assertThat(getJson("/api/admin/overview?days=1", mod).get("days")).hasSize(7);
        assertThat(getJson("/api/admin/overview?days=5000", mod).get("days")).hasSize(90);
        JsonNode fallback = getJson("/api/admin/overview?tz=Not/AZone", mod);
        assertThat(fallback.get("timeZone").asText()).isEqualTo("Z");
        assertThat(fallback.get("days")).hasSize(30);
    }

    @Test
    void completedExchangesAppearOnTheDayTheyWereCompleted() throws Exception {
        TestUser mod = admin();
        TestUser asha = register("Asha Rao");
        TestUser ben = register("Ben Ito");
        long lamp = createItem(asha, "Lamp", "EXCHANGE");
        long book = createItem(ben, "Cookbook", "EXCHANGE");
        long offer = makeOffer(ben, lamp, book);
        long exchange = read(mvc.perform(auth(post("/api/offers/" + offer + "/accept"), asha))
                .andExpect(status().isOk()).andReturn()).get("exchangeId").asLong();
        jdbc.update("update exchanges set status = 'COMPLETED', completed_at = now() where id = ?", exchange);

        JsonNode overview = getJson("/api/admin/overview?days=7", mod);
        assertThat(overview.at("/totals/completedSwaps").asLong()).isEqualTo(1);
        assertThat(overview.get("days").get(6).get("completedSwaps").asLong()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- members

    @Test
    void membersCanBeSearchedByNameOrEmailAndFiltered() throws Exception {
        TestUser mod = admin();
        TestUser asha = register("Asha Rao");
        TestUser ben = register("Ben Ito");
        register("Percy 100%");
        suspend(mod, ben.id(), "Spam accounts").andExpect(status().isOk());

        assertThat(getJson("/api/admin/users", mod).get("totalElements").asLong()).isEqualTo(4);
        JsonNode byName = getJson("/api/admin/users?q=asha", mod);
        assertThat(byName.get("content")).hasSize(1);
        assertThat(byName.at("/content/0/id").asLong()).isEqualTo(asha.id());
        assertThat(byName.at("/content/0/email").asText()).isEqualTo(asha.email());
        assertThat(getJson("/api/admin/users?q=BEN.ITO@EXAMPLE", mod).at("/content/0/id").asLong()).isEqualTo(ben.id());
        // Wildcard characters in the search text are matched literally, not as "anything".
        assertThat(read(mvc.perform(auth(get("/api/admin/users").param("q", "%"), mod)).andExpect(status().isOk())
                .andReturn()).get("content")).hasSize(1);
        assertThat(getJson("/api/admin/users?q=_", mod).get("content")).isEmpty();

        JsonNode suspended = getJson("/api/admin/users?filter=SUSPENDED", mod);
        assertThat(suspended.get("content")).hasSize(1);
        assertThat(suspended.at("/content/0/suspended").asBoolean()).isTrue();
        assertThat(getJson("/api/admin/users?filter=STAFF", mod).at("/content/0/id").asLong()).isEqualTo(mod.id());
        mvc.perform(auth(get("/api/admin/users?filter=NONSENSE"), mod)).andExpect(status().isBadRequest());
    }

    @Test
    void erasedAccountsAreNotSearchableAndShowNoPersonalData() throws Exception {
        TestUser mod = admin();
        TestUser ben = register("Ben Ito");
        mvc.perform(auth(delete("/api/users/me"), ben).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("password", "Passw0rd!")))).andExpect(status().isNoContent());

        assertThat(getJson("/api/admin/users?q=ben", mod).get("content")).isEmpty();
        assertThat(getJson("/api/admin/users?q=deleted", mod).get("content")).isEmpty();
        JsonNode detail = getJson("/api/admin/users/" + ben.id(), mod);
        assertThat(detail.get("deleted").asBoolean()).isTrue();
        assertThat(detail.get("email").isNull()).isTrue();
        assertThat(detail.toString()).doesNotContain("Ben Ito").doesNotContain("ben.ito@example.com");
    }

    @Test
    void aMembersRecordShowsTheirActivityListingsAndStaffActions() throws Exception {
        TestUser mod = admin();
        TestUser asha = register("Asha Rao");
        TestUser ben = register("Ben Ito");
        long book = createItem(ben, "Cookbook", "EXCHANGE");
        createItem(ben, "Kettle", "GIVEAWAY");
        report(asha, "USER", ben.id(), "SPAM");
        report(asha, "ITEM", book, "FAKE_LISTING");
        suspend(mod, ben.id(), "Selling fake goods").andExpect(status().isOk());

        JsonNode detail = getJson("/api/admin/users/" + ben.id(), mod);

        assertThat(detail.get("email").asText()).isEqualTo(ben.email());
        assertThat(detail.get("signInMethods")).hasSize(1);
        assertThat(detail.at("/signInMethods/0").asText()).isEqualTo("PASSWORD");
        assertThat(detail.get("suspensionReason").asText()).isEqualTo("Selling fake goods");
        assertThat(detail.at("/activity/liveListings").asLong()).isEqualTo(2);
        assertThat(detail.at("/activity/totalReportsAbout").asLong()).isEqualTo(2);
        assertThat(detail.at("/activity/openReportsAbout").asLong()).isEqualTo(2);
        assertThat(detail.at("/activity/reportsFiled").asLong()).isZero();
        assertThat(detail.get("recentListings")).hasSize(2);
        assertThat(detail.at("/recentListings/0/status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(detail.at("/recentActions/0/action").asText()).isEqualTo("MEMBER_SUSPENDED");
        assertThat(detail.at("/recentActions/0/actor/displayName").asText()).isEqualTo("Moderator");
        // Coordinates and password material never leave the server, even for staff.
        assertThat(detail.has("latitude")).isFalse();
        assertThat(detail.toString()).doesNotContain("passwordHash").doesNotContain("tokenVersion");
        mvc.perform(auth(get("/api/admin/users/999999"), mod)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- listings

    @Test
    void listingsCanBeSearchedIncludingRemovedOnes() throws Exception {
        TestUser mod = admin();
        TestUser asha = register("Asha Rao");
        TestUser ben = register("Ben Ito");
        long lamp = createItem(asha, "Desk lamp", "EXCHANGE");
        long book = createItem(ben, "Cookbook", "EXCHANGE");
        removeListing(mod, book, "Counterfeit").andExpect(status().isOk());

        assertThat(getJson("/api/admin/items", mod).get("totalElements").asLong()).isEqualTo(2);
        JsonNode lamps = getJson("/api/admin/items?q=LAMP", mod);
        assertThat(lamps.get("content")).hasSize(1);
        assertThat(lamps.at("/content/0/id").asLong()).isEqualTo(lamp);
        assertThat(lamps.at("/content/0/owner/displayName").asText()).isEqualTo("Asha Rao");
        assertThat(getJson("/api/admin/items?status=REMOVED", mod).at("/content/0/id").asLong()).isEqualTo(book);
        assertThat(getJson("/api/admin/items?ownerId=" + asha.id(), mod).get("content")).hasSize(1);
    }

    @Test
    void anAdminCanRemoveAnyListingWithAReasonTheOwnerSees() throws Exception {
        TestUser mod = admin();
        TestUser asha = register("Asha Rao");
        TestUser carl = register("Carl Diaz");
        TestUser ben = register("Ben Ito");
        long watch = createItem(ben, "Counterfeit watch", "EXCHANGE");
        long lamp = createItem(asha, "Lamp", "EXCHANGE");
        long pendingOffer = makeOffer(asha, watch, lamp);
        report(carl, "ITEM", watch, "SCAM");

        removeListing(mod, watch, " ").andExpect(status().isBadRequest());
        removeListing(mod, watch, "Counterfeit goods are not allowed").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"));

        assertThat(itemStatus(watch)).isEqualTo("REMOVED");
        assertThat(offerStatus(pendingOffer)).isEqualTo("REJECTED");
        mvc.perform(get("/api/items/" + watch)).andExpect(status().isNotFound());
        assertThat(mail.await(ben.email(), "listing-removed").text()).contains("Counterfeit goods are not allowed");
        assertThat(getJson("/api/notifications", ben).at("/content/0/type").asText()).isEqualTo("LISTING_REMOVED");
        // The open report about it is closed and its reporter is told.
        assertThat(jdbc.queryForObject("select resolution from reports", String.class)).isEqualTo("ITEM_REMOVED");
        assertThat(getJson("/api/notifications", carl).at("/content/0/type").asText()).isEqualTo("REPORT_RESOLVED");

        removeListing(mod, watch, "Again").andExpect(status().isConflict());
        removeListing(mod, 999999, "Missing").andExpect(status().isNotFound());
    }

    @Test
    void aListingInAnExchangeInProgressCannotBeRemovedDirectly() throws Exception {
        TestUser mod = admin();
        TestUser asha = register("Asha Rao");
        TestUser ben = register("Ben Ito");
        long lamp = createItem(asha, "Lamp", "EXCHANGE");
        long book = createItem(ben, "Cookbook", "EXCHANGE");
        long offer = makeOffer(ben, lamp, book);
        mvc.perform(auth(post("/api/offers/" + offer + "/accept"), asha)).andExpect(status().isOk());

        removeListing(mod, lamp, "Reported").andExpect(status().isConflict());
        assertThat(itemStatus(lamp)).isEqualTo("RESERVED");
        assertThat(auditCount()).isZero();
    }

    // ---------------------------------------------------------------- audit log

    @Test
    void everyStaffActionIsRecordedWithWhoWhatWhenAndWhy() throws Exception {
        TestUser mod = admin();
        TestUser asha = register("Asha Rao");
        TestUser ben = register("Ben Ito");
        long book = createItem(ben, "Cookbook", "EXCHANGE");
        long kettle = createItem(ben, "Kettle", "GIVEAWAY");
        long dismissed = report(asha, "USER", ben.id(), "SPAM");
        long aboutBook = report(asha, "ITEM", book, "FAKE_LISTING");

        resolve(mod, dismissed, "DISMISS", "Nothing wrong here").andExpect(status().isOk());
        resolve(mod, aboutBook, "REMOVE_ITEM", "Stock photos").andExpect(status().isOk());
        removeListing(mod, kettle, "Recalled product").andExpect(status().isOk());
        suspend(mod, ben.id(), "Repeated fake listings").andExpect(status().isOk());
        mvc.perform(auth(post("/api/admin/users/" + ben.id() + "/reinstate"), mod)).andExpect(status().isNoContent());

        JsonNode log = getJson("/api/admin/audit", mod);
        assertThat(log.get("totalElements").asLong()).isEqualTo(5);
        JsonNode entries = log.get("content"); // newest first
        assertThat(entries.at("/0/action").asText()).isEqualTo("MEMBER_REINSTATED");
        assertThat(entries.at("/1/action").asText()).isEqualTo("MEMBER_SUSPENDED");
        assertThat(entries.at("/1/note").asText()).isEqualTo("Repeated fake listings");
        assertThat(entries.at("/2/action").asText()).isEqualTo("LISTING_REMOVED");
        assertThat(entries.at("/2/itemTitle").asText()).isEqualTo("Kettle");
        assertThat(entries.at("/2/reportId").isNull()).isTrue();
        assertThat(entries.at("/3/action").asText()).isEqualTo("LISTING_REMOVED");
        assertThat(entries.at("/3/itemId").asLong()).isEqualTo(book);
        assertThat(entries.at("/3/reportId").asLong()).isEqualTo(aboutBook);
        assertThat(entries.at("/4/action").asText()).isEqualTo("REPORT_DISMISSED");
        assertThat(entries.at("/4/note").asText()).isEqualTo("Nothing wrong here");
        for (JsonNode entry : entries) {
            assertThat(entry.at("/actor/id").asLong()).isEqualTo(mod.id());
            assertThat(entry.at("/targetUser/id").asLong()).isEqualTo(ben.id());
            assertThat(entry.get("createdAt").asText()).isNotBlank();
        }
    }

    @Test
    void aRefusedActionLeavesNoAuditEntry() throws Exception {
        TestUser mod = admin();
        TestUser ben = register("Ben Ito");
        suspend(mod, mod.id(), "Testing").andExpect(status().isBadRequest());
        suspend(mod, ben.id(), "").andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/admin/users/" + ben.id() + "/reinstate"), mod)).andExpect(status().isConflict());
        assertThat(auditCount()).isZero();
    }

    @Test
    void theAuditLogCannotBeChangedOrDeleted() throws Exception {
        TestUser mod = admin();
        TestUser ben = register("Ben Ito");
        suspend(mod, ben.id(), "Spam accounts").andExpect(status().isOk());

        assertThatThrownBy(() -> jdbc.update("update admin_audit_log set note = 'tidied up'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from admin_audit_log"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("select note from admin_audit_log", String.class)).isEqualTo("Spam accounts");
    }

    @Test
    void theDatabaseRequiresAListingForATakedownEntry() throws Exception {
        TestUser mod = admin();
        TestUser ben = register("Ben Ito");
        assertThatThrownBy(() -> jdbc.update("insert into admin_audit_log (actor_id, action, target_user_id, "
                + "created_at) values (?, 'LISTING_REMOVED', ?, now())", mod.id(), ben.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("insert into admin_audit_log (actor_id, action, target_user_id, "
                + "created_at) values (?, 'MADE_UP', ?, now())", mod.id(), ben.id()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void erasingAnAccountAnonymisesItsAuditEntries() throws Exception {
        TestUser mod = admin();
        TestUser ben = register("Ben Ito");
        long book = createItem(ben, "Cookbook", "EXCHANGE");
        removeListing(mod, book, "Counterfeit").andExpect(status().isOk());
        mvc.perform(auth(delete("/api/users/me"), ben).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("password", "Passw0rd!")))).andExpect(status().isNoContent());

        JsonNode entry = getJson("/api/admin/audit", mod).at("/content/0");
        assertThat(entry.at("/targetUser/deleted").asBoolean()).isTrue();
        assertThat(entry.toString()).doesNotContain("Ben Ito").doesNotContain("ben.ito");
        assertThat(auditCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private TestUser admin() throws Exception {
        TestUser mod = register("Moderator");
        jdbc.update("update users set role = 'ADMIN' where id = ?", mod.id());
        return mod;
    }

    private long auditCount() {
        return jdbc.queryForObject("select count(*) from admin_audit_log", Long.class);
    }

    private long report(TestUser by, String type, long targetId, String reason) throws Exception {
        return read(mvc.perform(auth(post("/api/reports"), by).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("targetType", type, "targetId", targetId, "reason", reason))))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private ResultActions resolve(TestUser mod, long reportId, String action, String note) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("action", action));
        if (note != null) {
            body.put("note", note);
        }
        return mvc.perform(auth(post("/api/admin/reports/" + reportId + "/resolve"), mod)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    private ResultActions suspend(TestUser mod, long userId, String reason) throws Exception {
        return mvc.perform(auth(post("/api/admin/users/" + userId + "/suspend"), mod)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("reason", reason))));
    }

    private ResultActions removeListing(TestUser mod, long itemId, String reason) throws Exception {
        return mvc.perform(auth(post("/api/admin/items/" + itemId + "/remove"), mod)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("reason", reason))));
    }
}
