package com.swapp.offer;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface OfferRepository extends JpaRepository<Offer, Long> {

    String GRAPH_REQUESTED = "requestedItem";

    /** Reads only foreign keys, so nothing is loaded into the persistence context before locks are taken. */
    @Query("select o.requestedItem.id as requestedItemId, o.offeredItem.id as offeredItemId, "
            + "o.offerer.id as offererId, o.recipient.id as recipientId from Offer o where o.id = :id")
    Optional<OfferRefs> findRefs(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Offer o where o.id = :id")
    Optional<Offer> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Offer o where o.status = com.swapp.offer.OfferStatus.PENDING "
            + "and (o.requestedItem.id in :itemIds or o.offeredItem.id in :itemIds) order by o.id")
    List<Offer> lockPendingInvolving(@Param("itemIds") Collection<Long> itemIds);

    @EntityGraph(attributePaths = {"requestedItem", "requestedItem.owner", "requestedItem.category",
            "offeredItem", "offeredItem.owner", "offeredItem.category", "offerer", "recipient"})
    Page<Offer> findByRecipientIdAndStatusIn(Long recipientId, Collection<OfferStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"requestedItem", "requestedItem.owner", "requestedItem.category",
            "offeredItem", "offeredItem.owner", "offeredItem.category", "offerer", "recipient"})
    Page<Offer> findByOffererIdAndStatusIn(Long offererId, Collection<OfferStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"requestedItem", "requestedItem.owner", "requestedItem.category",
            "offeredItem", "offeredItem.owner", "offeredItem.category", "offerer", "recipient"})
    Optional<Offer> findWithDetailsById(Long id);

    boolean existsByRequestedItemIdAndOfferedItemIdAndStatus(Long requestedItemId, Long offeredItemId,
                                                             OfferStatus status);

    Optional<Offer> findFirstByOffererIdAndRequestedItemIdAndStatus(Long offererId, Long requestedItemId,
                                                                    OfferStatus status);

    long countByRecipientIdAndStatus(Long recipientId, OfferStatus status);

    long countByOffererIdAndStatus(Long offererId, OfferStatus status);

    long countByRequestedItemIdAndStatus(Long requestedItemId, OfferStatus status);

    interface OfferRefs {
        Long getRequestedItemId();

        Long getOfferedItemId();

        Long getOffererId();

        Long getRecipientId();
    }
}
