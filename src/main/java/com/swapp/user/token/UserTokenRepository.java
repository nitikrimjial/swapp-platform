package com.swapp.user.token;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    /** Locks the token row so two simultaneous uses of the same link cannot both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from UserToken t join fetch t.user where t.tokenHash = :hash")
    Optional<UserToken> lockByHash(@Param("hash") String hash);

    /** Invalidates every outstanding token of one purpose for a user (a new link replaces older ones). */
    // No clearAutomatically: callers keep working with the managed User returned by consume().
    @Modifying(flushAutomatically = true)
    @Query("update UserToken t set t.usedAt = :now where t.user.id = :userId and t.purpose = :purpose "
            + "and t.usedAt is null")
    int invalidateOutstanding(@Param("userId") Long userId, @Param("purpose") TokenPurpose purpose,
                              @Param("now") Instant now);

    @Modifying
    @Query("delete from UserToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from UserToken t where t.user.id = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
