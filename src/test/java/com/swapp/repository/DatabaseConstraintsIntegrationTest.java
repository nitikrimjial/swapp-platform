package com.swapp.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

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
 * and exercises repository queries directly.
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
    void duplicatePendingOffersAreRejectedByUniqueIndex() {
        insertOffer("PENDING");
        assertThatThrownBy(() -> insertOffer("PENDING")).isInstanceOf(DataIntegrityViolationException.class);
        // non-pending duplicates are fine (history)
        insertOffer("CANCELLED");
        insertOffer("REJECTED");
    }

    @Test
    void offerCannotTargetTheSameItemOrUser() {
        assertThatThrownBy(() -> jdbc.update("insert into offers (requested_item_id, offered_item_id, offerer_id, "
                + "recipient_id, status, created_at, updated_at) values (?, ?, ?, ?, 'PENDING', ?, ?)",
                bike, bike, bob, alice, now(), now())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anItemCannotBeInTwoActiveExchanges() {
        long offer1 = insertOffer("ACCEPTED");
        long other = insertItem(bob, "Speaker");
        long offer2 = jdbcInsertOffer(bike, other, "ACCEPTED");
        insertExchange(offer1, bike, monitor, "ACTIVE");
        assertThatThrownBy(() -> insertExchange(offer2, bike, other, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emailsAreUnique() {
        assertThatThrownBy(() -> insertUser("alice@example.com")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidEnumValuesAreRejectedByCheckConstraints() {
        assertThatThrownBy(() -> jdbc.update("update items set status = 'BOGUS' where id = ?", bike))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void specificationQueryOnlyReturnsAvailableItems() {
        jdbc.update("update items set status = 'RESERVED' where id = ?", monitor);
        var page = items.findAll(ItemSpecifications.matching(
                new ItemSearchCriteria(null, null, null, java.util.List.of(ListingType.EXCHANGE), null, null, null)),
                PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(i -> i.getTitle()).containsExactly("Bike");
    }

    private long insertUser(String email) {
        jdbc.update("insert into users (email, password_hash, display_name, role, token_version, created_at, updated_at)"
                + " values (?, '$2a$04$hash', 'User', 'USER', 0, ?, ?)", email, now(), now());
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private long insertItem(long owner, String title) {
        jdbc.update("insert into items (owner_id, category_id, title, description, item_condition, listing_type, "
                + "status, location, created_at, updated_at) values (?, ?, ?, 'desc', 'GOOD', 'EXCHANGE', 'AVAILABLE', "
                + "'Town', ?, ?)", owner, categoryId("other"), title, now(), now());
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private long insertOffer(String status) {
        return jdbcInsertOffer(bike, monitor, status);
    }

    private long jdbcInsertOffer(long requested, long offered, String status) {
        long recipient = jdbc.queryForObject("select owner_id from items where id = ?", Long.class, requested);
        long offerer = jdbc.queryForObject("select owner_id from items where id = ?", Long.class, offered);
        jdbc.update("insert into offers (requested_item_id, offered_item_id, offerer_id, recipient_id, status, "
                + "created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?)", requested, offered, offerer, recipient,
                status, now(), now());
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private void insertExchange(long offerId, long ownerItem, long requesterItem, String status) {
        jdbc.update("insert into exchanges (offer_id, owner_id, requester_id, owner_item_id, requester_item_id, status, "
                + "created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)", offerId, alice, bob, ownerItem,
                requesterItem, status, now(), now());
    }

    private static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
