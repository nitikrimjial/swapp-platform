package com.swapp.moderation.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, Long> {

    @EntityGraph(attributePaths = {"actor", "targetUser", "item"})
    Page<AuditEntry> findAllBy(Pageable pageable);

    @EntityGraph(attributePaths = {"actor", "targetUser", "item"})
    Page<AuditEntry> findByTargetUserId(Long targetUserId, Pageable pageable);
}
