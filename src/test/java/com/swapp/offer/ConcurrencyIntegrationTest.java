package com.swapp.offer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.swapp.common.ApiException;
import com.swapp.support.AbstractIntegrationTest;

/**
 * Fires conflicting operations at the same instant on separate threads/transactions and verifies that the database
 * row locks let exactly one of them win.
 */
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    OfferService offerService;

    @Test
    void twoOffersForTheSameItemCannotBothBeAccepted() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");
        long bicycle = createItem(alice, "Bicycle", "EXCHANGE");
        long offer1 = makeOffer(bob, bicycle, createItem(bob, "Monitor", "EXCHANGE"));
        long offer2 = makeOffer(carol, bicycle, createItem(carol, "Lamp", "EXCHANGE"));

        List<Outcome> outcomes = race(
                () -> offerService.accept(offer1, alice.id()),
                () -> offerService.accept(offer2, alice.id()));

        assertThat(outcomes.stream().filter(Outcome::success).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from exchanges", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from offers where status = 'ACCEPTED'", Integer.class))
                .isEqualTo(1);
        assertThat(itemStatus(bicycle)).isEqualTo("RESERVED");
    }

    @Test
    void theSameOfferedItemCannotBeAcceptedByTwoOwners() throws Exception {
        TestUser alice = register("Alice");
        TestUser dave = register("Dave");
        TestUser bob = register("Bob");
        long bicycle = createItem(alice, "Bicycle", "EXCHANGE");
        long guitar = createItem(dave, "Guitar", "EXCHANGE");
        long monitor = createItem(bob, "Monitor", "EXCHANGE");
        long toAlice = makeOffer(bob, bicycle, monitor);
        long toDave = makeOffer(bob, guitar, monitor);

        List<Outcome> outcomes = race(
                () -> offerService.accept(toAlice, alice.id()),
                () -> offerService.accept(toDave, dave.id()));

        assertThat(outcomes.stream().filter(Outcome::success).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from exchanges", Integer.class)).isEqualTo(1);
        assertThat(itemStatus(monitor)).isEqualTo("RESERVED");
        // exactly one of the two owners' items got reserved
        int reservedOwnerItems = jdbc.queryForObject(
                "select count(*) from items where id in (?, ?) and status = 'RESERVED'", Integer.class, bicycle, guitar);
        assertThat(reservedOwnerItems).isEqualTo(1);
    }

    @Test
    void acceptAndCancelRaceLeavesConsistentState() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        long bicycle = createItem(alice, "Bicycle", "EXCHANGE");
        long monitor = createItem(bob, "Monitor", "EXCHANGE");
        long offerId = makeOffer(bob, bicycle, monitor);

        List<Outcome> outcomes = race(
                () -> offerService.accept(offerId, alice.id()),
                () -> offerService.cancel(offerId, bob.id()));

        assertThat(outcomes.stream().filter(Outcome::success).count()).isEqualTo(1);
        String status = offerStatus(offerId);
        int exchanges = jdbc.queryForObject("select count(*) from exchanges", Integer.class);
        if (status.equals("ACCEPTED")) {
            assertThat(exchanges).isEqualTo(1);
            assertThat(itemStatus(bicycle)).isEqualTo("RESERVED");
        } else {
            assertThat(status).isEqualTo("CANCELLED");
            assertThat(exchanges).isZero();
            assertThat(itemStatus(bicycle)).isEqualTo("AVAILABLE");
        }
    }

    private record Outcome(boolean success, Throwable error) {
    }

    @SafeVarargs
    private List<Outcome> race(Callable<?>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        task.call();
                        return new Outcome(true, null);
                    } catch (ApiException | org.springframework.dao.DataAccessException e) {
                        return new Outcome(false, e);
                    }
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get());
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
