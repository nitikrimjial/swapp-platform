package com.swapp.exchange;

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

public interface ExchangeRepository extends JpaRepository<Exchange, Long> {

    @Query("select e.ownerItem.id as ownerItemId, e.requesterItem.id as requesterItemId, e.owner.id as ownerId, "
            + "e.requester.id as requesterId from Exchange e where e.id = :id")
    Optional<ExchangeRefs> findRefs(@Param("id") Long id);

    @Query("select e from Exchange e where e.offer.id in :offerIds")
    List<Exchange> findByOfferIdIn(@Param("offerIds") Collection<Long> offerIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Exchange e where e.id = :id")
    Optional<Exchange> lockById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"owner", "requester", "ownerItem", "ownerItem.category", "ownerItem.owner",
            "requesterItem", "requesterItem.category", "requesterItem.owner", "cancelledBy", "offer"})
    @Query("select e from Exchange e where (e.owner.id = :userId or e.requester.id = :userId) and e.status in :statuses")
    Page<Exchange> findForUser(@Param("userId") Long userId, @Param("statuses") Collection<ExchangeStatus> statuses,
                               Pageable pageable);

    @EntityGraph(attributePaths = {"owner", "requester", "ownerItem", "ownerItem.category", "ownerItem.owner",
            "requesterItem", "requesterItem.category", "requesterItem.owner", "cancelledBy", "offer"})
    Optional<Exchange> findWithDetailsById(Long id);

    Optional<Exchange> findByOfferId(Long offerId);

    @Query("select count(e) from Exchange e where (e.owner.id = :userId or e.requester.id = :userId) and e.status = :status")
    long countForUser(@Param("userId") Long userId, @Param("status") ExchangeStatus status);

    long countByStatus(ExchangeStatus status);

    @Query("select e.id as id, e.ownerItem.id as ownerItemId, e.requesterItem.id as requesterItemId from Exchange e "
            + "where (e.owner.id = :userId or e.requester.id = :userId) "
            + "and e.status = com.swapp.exchange.ExchangeStatus.ACTIVE order by e.id")
    List<ActiveExchangeRefs> findActiveRefsForUser(@Param("userId") Long userId);

    interface ActiveExchangeRefs {
        Long getId();

        Long getOwnerItemId();

        Long getRequesterItemId();
    }

    interface ExchangeRefs {
        Long getOwnerItemId();

        Long getRequesterItemId();

        Long getOwnerId();

        Long getRequesterId();
    }
}
