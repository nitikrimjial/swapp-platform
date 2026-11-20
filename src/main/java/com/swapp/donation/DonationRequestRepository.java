package com.swapp.donation;

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

public interface DonationRequestRepository extends JpaRepository<DonationRequest, Long> {

    @Query("select r.item.id as itemId, r.owner.id as ownerId, r.requester.id as requesterId "
            + "from DonationRequest r where r.id = :id")
    Optional<RequestRefs> findRefs(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DonationRequest r where r.id = :id")
    Optional<DonationRequest> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DonationRequest r where r.item.id in :itemIds and r.status in :statuses order by r.id")
    List<DonationRequest> lockByItemsAndStatuses(@Param("itemIds") Collection<Long> itemIds,
                                                 @Param("statuses") Collection<DonationRequestStatus> statuses);

    @EntityGraph(attributePaths = {"item", "item.owner", "item.category", "requester", "owner"})
    Page<DonationRequest> findByOwnerIdAndStatusIn(Long ownerId, Collection<DonationRequestStatus> statuses,
                                                   Pageable pageable);

    @EntityGraph(attributePaths = {"item", "item.owner", "item.category", "requester", "owner"})
    Page<DonationRequest> findByRequesterIdAndStatusIn(Long requesterId, Collection<DonationRequestStatus> statuses,
                                                       Pageable pageable);

    Optional<DonationRequest> findFirstByItemIdAndRequesterIdAndStatusIn(Long itemId, Long requesterId,
                                                                         Collection<DonationRequestStatus> statuses);

    long countByOwnerIdAndStatus(Long ownerId, DonationRequestStatus status);

    long countByItemIdAndStatus(Long itemId, DonationRequestStatus status);

    long countByStatus(DonationRequestStatus status);

    interface RequestRefs {
        Long getItemId();

        Long getOwnerId();

        Long getRequesterId();
    }
}
