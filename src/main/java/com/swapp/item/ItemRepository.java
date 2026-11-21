package com.swapp.item;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ItemRepository extends JpaRepository<Item, Long>, JpaSpecificationExecutor<Item> {

    @Override
    @EntityGraph(attributePaths = {"owner", "category"})
    Page<Item> findAll(Specification<Item> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"owner", "category"})
    @Query("select i from Item i where i.id = :id and i.status <> com.swapp.item.ItemStatus.REMOVED")
    Optional<Item> findActiveById(@Param("id") Long id);

    /**
     * Locks the given items (SELECT ... FOR UPDATE) in primary-key order. Every workflow that changes item
     * availability locks items first and in id order, which serialises conflicting operations and avoids deadlocks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Item i where i.id in :ids order by i.id")
    List<Item> lockAllByIds(@Param("ids") Collection<Long> ids);

    @EntityGraph(attributePaths = {"owner", "category"})
    Page<Item> findByOwnerIdAndStatusIn(Long ownerId, Collection<ItemStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"category"})
    List<Item> findByOwnerIdAndStatusAndListingTypeOrderByCreatedAtDesc(Long ownerId, ItemStatus status,
                                                                       ListingType listingType);

    long countByOwnerIdAndStatus(Long ownerId, ItemStatus status);

    long countByOwnerIdAndStatusNot(Long ownerId, ItemStatus status);

    long countByStatus(ItemStatus status);
}
