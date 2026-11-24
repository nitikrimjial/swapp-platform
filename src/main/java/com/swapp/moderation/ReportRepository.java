package com.swapp.moderation;

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

public interface ReportRepository extends JpaRepository<Report, Long> {

    /** Foreign keys only, read before any lock is taken (see ItemLocker). */
    @Query("select r.targetType as targetType, r.item.id as itemId, r.reportedUser.id as reportedUserId "
            + "from Report r where r.id = :id")
    Optional<ReportRefs> findRefs(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Report r where r.id = :id")
    Optional<Report> lockById(@Param("id") Long id);

    /** Other open reports about the same listing, resolved together when it is taken down. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Report r where r.status = com.swapp.moderation.ReportStatus.OPEN and r.item.id = :itemId "
            + "and r.id <> :excludeId order by r.id")
    List<Report> lockOpenForItem(@Param("itemId") Long itemId, @Param("excludeId") Long excludeId);

    /** Other open reports about the same member, resolved together when they are suspended. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Report r where r.status = com.swapp.moderation.ReportStatus.OPEN "
            + "and r.reportedUser.id = :userId and r.id <> :excludeId order by r.id")
    List<Report> lockOpenForUser(@Param("userId") Long userId, @Param("excludeId") Long excludeId);

    @EntityGraph(attributePaths = {"reporter", "reportedUser", "item", "message", "resolvedBy"})
    Page<Report> findByStatus(ReportStatus status, Pageable pageable);

    boolean existsByReporterIdAndTargetTypeAndReportedUserIdAndStatus(Long reporterId, ReportTargetType type,
                                                                     Long reportedUserId, ReportStatus status);

    boolean existsByReporterIdAndItemIdAndStatus(Long reporterId, Long itemId, ReportStatus status);

    boolean existsByReporterIdAndMessageIdAndStatus(Long reporterId, Long messageId, ReportStatus status);

    long countByReporterIdAndStatus(Long reporterId, ReportStatus status);

    long countByReportedUserIdAndStatus(Long reportedUserId, ReportStatus status);

    long countByStatus(ReportStatus status);

    long countByReportedUserId(Long reportedUserId);

    long countByReporterId(Long reporterId);

    interface ReportRefs {
        ReportTargetType getTargetType();

        Long getItemId();

        Long getReportedUserId();
    }
}
