package com.swapp.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import com.swapp.item.ItemRepository;
import com.swapp.item.ItemSearchCriteria;
import com.swapp.item.ItemSpecifications;
import com.swapp.item.ListingType;
import com.swapp.support.AbstractIntegrationTest;

/**
 * Verifies that integrity rules hold at the database level too (defence in depth), independent of service code,
 * and exercises repository queries directly against PostgreSQL.
 */
class DatabaseConstraintsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ItemRepository items;

    long alice;
    long bob;
    long bike;
    long monitor;

    @BeforeEach
    void seed() {
        alice = insertUser("alice@example.com");
        bob = insertUser("bob@example.com");
        bike = insertItem(alice, "Bike");
        monitor = insertItem(bob, "Monitor");
    }

    @Test
    void duplicatePendingOffersAreRejectedByPartialUniqueIndex() {
        insertOffer(bike, monitor, "PENDING");
        assertThatThrownBy(() -> insertOffer(bike, monitor, "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // non-pending duplicates are fine (history)
        insertOffer(bike, monitor, "CANCELLED");
        insertOffer(bike, monitor, "REJECTED");
    }

    @Test
    void offerCannotTargetTheSameItemOrUser() {
        assertThatThrownBy(() -> jdbc.update("insert into offers (requested_item_id, offered_item_id, offerer_id, "
                + "recipient_id, status, created_at, updated_at) values (?, ?, ?, ?, 'PENDING', ?, ?)",
                bike, bike, bob, alice, now(), now())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anItemCannotBeInTwoActiveExchanges() {
        long offer1 = insertOffer(bike, monitor, "ACCEPTED");
        long other = insertItem(bob, "Speaker");
        long offer2 = insertOffer(bike, other, "ACCEPTED");
        insertExchange(offer1, bike, monitor, "ACTIVE");
        assertThatThrownBy(() -> insertExchange(offer2, bike, other, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // ...but a completed exchange may coexist with history
        insertExchange(offer2, bike, other, "COMPLETED");
    }

    @Test
    void emailsAreUniqueIgnoringCase() {
        assertThatThrownBy(() -> insertUser("ALICE@example.com")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidEnumValuesAreRejectedByCheckConstraints() {
        assertThatThrownBy(() -> jdbc.update("update items set status = 'BOGUS' where id = ?", bike))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void coordinatesMustBeCompleteAndInRange() {
        assertThatThrownBy(() -> jdbc.update("update items set latitude = 12.9 where id = ?", bike))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update items set latitude = 95, longitude = 10 where id = ?", bike))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update items set latitude = 12.935, longitude = 77.624 where id = ?", bike);
    }

    @Test
    void specificationQueryOnlyReturnsAvailableItems() {
        jdbc.update("update items set status = 'RESERVED' where id = ?", monitor);
        var criteria = new ItemSearchCriteria(null, null, null, java.util.List.of(ListingType.EXCHANGE), null, null,
                null, null, false, null, null, false);
        var page = items.findAll(ItemSpecifications.matching(criteria), PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(i -> i.getTitle()).containsExactly("Bike");
    }

    @Test
    void trigramIndexesExistForSearch() {
        Integer count = jdbc.queryForObject("select count(*) from pg_indexes where tablename = 'items' "
                + "and indexname like '%trgm'", Integer.class);
        assertThat(count).isEqualTo(3);
    }

    private long insertUser(String email) {
        return jdbc.queryForObject("insert into users (email, password_hash, display_name, role, token_version, "
                + "created_at, updated_at) values (?, '$2a$04$hash', 'User', 'USER', 0, ?, ?) returning id",
                Long.class, email, now(), now());
    }

    private long insertItem(long owner, String title) {
        return jdbc.queryForObject("insert into items (owner_id, category_id, title, description, item_condition, "
                + "listing_type, status, location, created_at, updated_at) values (?, ?, ?, 'desc', 'GOOD', "
                + "'EXCHANGE', 'AVAILABLE', 'Town', ?, ?) returning id",
                Long.class, owner, categoryId("other"), title, now(), now());
    }

    private long insertOffer(long requested, long offered, String status) {
        long recipient = jdbc.queryForObject("select owner_id from items where id = ?", Long.class, requested);
        long offerer = jdbc.queryForObject("select owner_id from items where id = ?", Long.class, offered);
        return jdbc.queryForObject("insert into offers (requested_item_id, offered_item_id, offerer_id, recipient_id, "
                + "status, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?) returning id",
                Long.class, requested, offered, offerer, recipient, status, now(), now());
    }

    private void insertExchange(long offerId, long ownerItem, long requesterItem, String status) {
        jdbc.update("insert into exchanges (offer_id, owner_id, requester_id, owner_item_id, requester_item_id, status, "
                + "created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)", offerId, alice, bob, ownerItem,
                requesterItem, status, now(), now());
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
