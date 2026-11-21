package com.swapp.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.swapp.support.AbstractIntegrationTest;

/** Blocking, reporting and the moderation workflow, end to end through the HTTP API. */
class TrustAndSafetyIntegrationTest extends AbstractIntegrationTest {

    // ---------------------------------------------------------------- blocking

    @Test
    void aBlockStopsContactInBothDirectionsUntilLifted() throws Exception {
        TestUser asha = register("Asha Rao");
        TestUser ben = register("Ben Ito");
        long ashaLamp = createItem(asha, "Desk lamp", "EXCHANGE");
        long ashaChair = createItem(asha, "Free chair", "GIVEAWAY");
        long benBook = createItem(ben, "Cookbook", "EXCHANGE");
        long conversation = startConversation(ben, asha, "Hi Asha");

        mvc.perform(auth(post("/api/users/" + ben.id() + "/block"), asha)).andExpect(status().isNoContent());
        mvc.perform(auth(post("/api/users/" + ben.id() + "/block"), asha)).andExpect(status().isNoContent());

        // Ben (blocked) cannot reach Asha in any way, and is not told explicitly that he was blocked.
        mvc.perform(auth(post("/api/conversations/" + conversation + "/messages"), ben)
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("body", "Hello?"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You can't contact this member."));
        offer(ben, ashaLamp, benBook).andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/donation-requests"), ben).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("itemId", ashaChair)))).andExpect(status().isForbidden());
        // Asha (blocker) is told how to undo it.
        mvc.perform(auth(post("/api/conversations"), asha).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("recipientId", ben.id(), "body", "Hi"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Unblock them")));

        assertThat(getJson("/api/users/" + ben.id(), asha).get("blockedByMe").asBoolean()).isTrue();
        assertThat(getJson("/api/users/me/blocks", asha).at("/0/id").asLong()).isEqualTo(ben.id());
        assertThat(getJson("/api/users/" + asha.id(), ben).get("blockedByMe").asBoolean()).isFalse();
        mvc.perform(get("/api/users/" + ben.id())).andExpect(jsonPath("$.blockedByMe").doesNotExist());

        mvc.perform(auth(delete("/api/users/" + ben.id() + "/block"), asha)).andExpect(status().isNoContent());
        offer(ben, ashaLamp, benBook).andExpect(status().isCreated());
    }

    @Test
    void membersCannotBlockThemselvesOrUnknownMembers() throws Exception {
        TestUser asha = register("Asha Rao");
        mvc.perform(auth(post("/api/users/" + asha.id() + "/block"), asha)).andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/users/999999/block"), asha)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- reporting

    @Test
    void reportsCaptureEvidenceAndCannotBeDuplicated() throws Exception {
        TestUser asha = register("Asha Rao");
        TestUser ben = register("Ben Ito");
        long benBook = createItem(ben, "Cookbook", "EXCHANGE");

        long reportId = read(report(asha, "ITEM", benBook, "FAKE_LISTING", "Photos are from a shop website")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN")).andReturn()).get("id").asLong();
        report(asha, "ITEM", benBook, "SCAM", null).andExpect(status().isConflict());

        // Editing the listing afterwards does not change the evidence the moderator sees.
        mvc.perform(auth(put("/api/items/" + benBook), ben).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(itemPayload("Totally innocent title")))).andExpect(status().isOk());
        String evidence = jdbc.queryForObject("select evidence from reports where id = ?", String.class, reportId);
        assertThat(evidence).contains("Title: Cookbook");

        report(asha, "USER", ben.id(), "HARASSMENT", null).andExpect(status().isCreated());
        report(asha, "USER", asha.id(), "SPAM", null).andExpect(status().isBadRequest());
        long ashaItem = createItem(asha, "Lamp", "EXCHANGE");
        report(asha, "ITEM", ashaItem, "SPAM", null).andExpect(status().isBadRequest());
        report(asha, "ITEM", 999999, "SPAM", null).andExpect(status().isNotFound());
    }

    @Test
    void onlyParticipantsCanReportAMessageAndOnlyMessagesFromOthers() throws Exception {
        TestUser asha = register("Asha Rao");
        TestUser ben = register("Ben Ito");
        TestUser carl = register("Carl Diaz");
        startConversation(ben, asha, "Send me money first or no deal");
        long messageId = jdbc.queryForObject("select id from messages order by id desc limit 1", Long.class);

        report(carl, "MESSAGE", messageId, "SCAM", null).andExpect(status().isNotFound()); // not his conversation
        report(ben, "MESSAGE", messageId, "SCAM", null).andExpect(status().isBadRequest()); // his own message
        report(asha, "MESSAGE", messageId, "SCAM", "Asking for payment").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select reported_user_id from reports", Long.class)).isEqualTo(ben.id());
    }

    @Test
    void theDatabaseRequiresTheRightReferenceForEachTargetType() throws Exception {
        TestUser asha = register("Asha Rao");
        TestUser ben = register("Ben Ito");
        assertThatThrownBy(() -> jdbc.update("insert into reports (reporter_id, target_type, reported_user_id, reason, "
                + "evidence, status, created_at, updated_at) values (?, 'ITEM', ?, 'SPAM', 'x', 'OPEN', now(), now())",
                asha.id(), ben.id())).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- moderation

    @Test
    void moderationEndpointsAreForAdminsOnly() throws Exception {
        TestUser asha = register("Asha Rao");
        mvc.perform(auth(get("/api/admin/reports"), asha)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/reports")).andExpect(status().isUnauthorized());
        mvc.perform(auth(get("/api/admin/reports"), admin())).andExpect(status().isOk());
    }

    @Test
    void dismissingAReportTellsTheReporterWithoutRevealingDetails() throws Exception {
        TestUser asha = register("Asha Rao");
        TestUser ben = register("Ben Ito");
        TestUser mod = admin();
        long reportId = reportId(report(asha, "USER", ben.id(), "SPAM", null));

        JsonNode queue = getJson("/api/admin/reports?status=OPEN", mod);
        assertThat(queue.at("/content/0/reportedUser/openReports").asLong()).isEqualTo(1);
        assertThat(queue.at("/content/0/evidence").asText()).contains("Ben Ito");

        resolve(mod, reportId, "DISMISS", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("DISMISSED"))
                .andExpect(jsonPath("$.resolvedBy").value("Moderator"));
        resolve(mod, reportId, "DISMISS", null).andExpect(status().isConflict());

        JsonNode note = getJson("/api/notifications", asha).at("/content/0");
        assertThat(note.get("type").asText()).isEqualTo("REPORT_RESOLVED");
        assertThat(note.get("body").asText()).contains("didn't find a breach");
        assertThat(getJson("/api/admin/reports?status=RESOLVED", mod).at("/content")).hasSize(1);
    }

    @Test
    void removingAListingResolvesEveryOpenReportAboutIt() throws Exception {
        TestUser asha = register("Asha Rao");
        TestUser carl = register("Carl Diaz");
        TestUser ben = register("Ben Ito");
        long benBook = createItem(ben, "Counterfeit watch", "EXCHANGE");
        long ashaLamp = createItem(asha, "Lamp", "EXCHANGE");
        long pendingOffer = makeOffer(asha, benBook, ashaLamp);
        long first = reportId(report(asha, "ITEM", benBook, "PROHIBITED_ITEM", null));
        long second = reportId(report(carl, "ITEM", benBook, "SCAM", null));

        resolve(admin(), first, "REMOVE_ITEM", "Counterfeit goods are not allowed").andExpect(status().isOk());

        assertThat(itemStatus(benBook)).isEqualTo("REMOVED");
        assertThat(offerStatus(pendingOffer)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select status from reports where id = ?", String.class, second))
                .isEqualTo("RESOLVED");
        assertThat(mail.await(ben.email(), "listing-removed").text()).contains("Counterfeit goods are not allowed");
        assertThat(getJson("/api/notifications", ben).at("/content/0/type").asText()).isEqualTo("LISTING_REMOVED");
        // A report about a member cannot remove a listing.
        long userReport = reportId(report(asha, "USER", ben.id(), "SCAM", null));
        resolve(admin2(), userReport, "REMOVE_ITEM", null).andExpect(status().isBadRequest());
    }

    @Test
    void suspendingAMemberCancelsTheirActivityAndLocksThemOutUntilReinstated() throws Exception {
        TestUser asha = register("Asha Rao");
        TestUser scammer = register("Sam Scam");
        long ashaLamp = createItem(asha, "Lamp", "EXCHANGE");
        long samPhone = createItem(scammer, "Cheap phone", "EXCHANGE");
        long samKettle = createItem(scammer, "Kettle", "EXCHANGE");
        long offer = makeOffer(asha, samPhone, ashaLamp);
        long exchangeId = read(mvc.perform(auth(post("/api/offers/" + offer + "/accept"), scammer))
                .andExpect(status().isOk()).andReturn()).get("exchangeId").asLong();
        long reportId = reportId(report(asha, "USER", scammer.id(), "SCAM", "Asked for a deposit"));
        TestUser mod = admin();

        resolve(mod, reportId, "SUSPEND_USER", null).andExpect(status().isBadRequest()); // a reason is required
        resolve(mod, reportId, "SUSPEND_USER", "Asking members for deposits").andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("USER_SUSPENDED"))
                .andExpect(jsonPath("$.reportedUser.suspended").value(true));

        // The exchange in progress is cancelled and Asha's lamp is available again; Sam's listings are hidden.
        assertThat(jdbc.queryForObject("select status from exchanges where id = ?", String.class, exchangeId))
                .isEqualTo("CANCELLED");
        assertThat(itemStatus(ashaLamp)).isEqualTo("AVAILABLE");
        assertThat(itemStatus(samPhone)).isEqualTo("UNAVAILABLE");
        assertThat(itemStatus(samKettle)).isEqualTo("UNAVAILABLE");

        // Sam is locked out; the reason is shown only after a correct password.
        mvc.perform(auth(get("/api/users/me"), scammer)).andExpect(status().isUnauthorized());
        login(scammer.email(), "WrongPassw0rd").andExpect(status().isUnauthorized());
        login(scammer.email(), "Passw0rd!").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("suspended")));
        mvc.perform(get("/api/users/" + scammer.id())).andExpect(status().isNotFound());
        assertThat(mail.await(scammer.email(), "account-suspended").text()).contains("Asking members for deposits");
        assertThat(getJson("/api/admin/users/suspended", mod).at("/content/0/id").asLong()).isEqualTo(scammer.id());
        assertThat(getJson("/api/admin/summary", mod).get("suspendedMembers").asLong()).isEqualTo(1);

        mvc.perform(auth(post("/api/admin/users/" + scammer.id() + "/reinstate"), mod)).andExpect(status().isNoContent());
        login(scammer.email(), "Passw0rd!").andExpect(status().isOk());
        mvc.perform(auth(post("/api/admin/users/" + scammer.id() + "/reinstate"), mod)).andExpect(status().isConflict());
    }

    @Test
    void moderatorsCanSuspendDirectlyButNotOtherModeratorsOrThemselves() throws Exception {
        TestUser mod = admin();
        TestUser other = admin2();
        TestUser ben = register("Ben Ito");
        suspend(mod, ben.id(), "").andExpect(status().isBadRequest());
        suspend(mod, mod.id(), "Testing").andExpect(status().isBadRequest());
        suspend(mod, other.id(), "Testing").andExpect(status().isBadRequest());
        suspend(mod, ben.id(), "Spam accounts").andExpect(status().isOk());
        suspend(mod, ben.id(), "Again").andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- helpers

    private TestUser admin() throws Exception {
        TestUser mod = register("Moderator");
        jdbc.update("update users set role = 'ADMIN' where id = ?", mod.id());
        return mod;
    }

    private TestUser admin2() throws Exception {
        TestUser mod = register("Second Moderator");
        jdbc.update("update users set role = 'ADMIN' where id = ?", mod.id());
        return mod;
    }

    private ResultActions report(TestUser by, String type, long targetId, String reason, String details)
            throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("targetType", type, "targetId", targetId, "reason", reason));
        if (details != null) {
            body.put("details", details);
        }
        return mvc.perform(auth(post("/api/reports"), by).contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    private long reportId(ResultActions result) throws Exception {
        return read(result.andExpect(status().isCreated()).andReturn()).get("id").asLong();
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

    private ResultActions offer(TestUser by, long requestedItemId, long offeredItemId) throws Exception {
        return mvc.perform(auth(post("/api/offers"), by).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("requestedItemId", requestedItemId, "offeredItemId", offeredItemId))));
    }

    private long startConversation(TestUser from, TestUser to, String body) throws Exception {
        return read(mvc.perform(auth(post("/api/conversations"), from).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("recipientId", to.id(), "body", body))))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", email, "password", password))));
    }

    private Map<String, Object> itemPayload(String title) {
        return Map.of("title", title, "description", "A well looked-after item in working order.",
                "categoryId", categoryId("electronics"), "condition", "GOOD", "listingType", "EXCHANGE",
                "location", "Springfield");
    }
}
