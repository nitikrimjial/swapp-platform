package com.swapp.moderation;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.swapp.common.ApiException;
import com.swapp.common.BaseEntity;
import com.swapp.user.User;
import com.swapp.user.UserRepository;
import com.swapp.user.dto.UserDtos.UserSummaryDto;

/**
 * Member-to-member blocking. A block works in both directions: neither member can message the other, make an exchange
 * offer on the other's listing or request the other's giveaway. Existing history stays; the blocked member is not
 * told.
 */
@Service
@Transactional
public class BlockService {

    private static final Logger log = LoggerFactory.getLogger(BlockService.class);

    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public BlockService(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    /** Idempotent: blocking someone twice is not an error. */
    public void block(Long blockerId, Long blockedId) {
        if (blockerId.equals(blockedId)) {
            throw ApiException.badRequest("You cannot block yourself");
        }
        users.findById(blockedId).filter(u -> !u.isDeleted()).orElseThrow(() -> ApiException.notFound("User"));
        int inserted = jdbc.update("insert into user_blocks (blocker_id, blocked_id, created_at) values (?, ?, ?) "
                + "on conflict do nothing", blockerId, blockedId, java.sql.Timestamp.from(BaseEntity.now()));
        if (inserted > 0) {
            log.info("User {} blocked user {}", blockerId, blockedId);
        }
    }

    public void unblock(Long blockerId, Long blockedId) {
        jdbc.update("delete from user_blocks where blocker_id = ? and blocked_id = ?", blockerId, blockedId);
    }

    /** Removes every block made by or against the member (account deletion). */
    public void removeAllFor(Long userId) {
        jdbc.update("delete from user_blocks where blocker_id = ? or blocked_id = ?", userId, userId);
    }

    @Transactional(readOnly = true)
    public boolean hasBlocked(Long blockerId, Long blockedId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from user_blocks where blocker_id = ? and blocked_id = ?)",
                Boolean.class, blockerId, blockedId));
    }

    @Transactional(readOnly = true)
    public List<UserSummaryDto> blockedBy(Long blockerId) {
        List<Long> ids = jdbc.queryForList(
                "select blocked_id from user_blocks where blocker_id = ? order by created_at desc", Long.class,
                blockerId);
        List<User> found = users.findAllById(ids);
        return ids.stream()
                .flatMap(id -> found.stream().filter(u -> u.getId().equals(id)))
                .map(UserSummaryDto::from)
                .toList();
    }

    /**
     * Throws 403 if either member has blocked the other. The wording differs so a member who blocked someone knows
     * how to undo it, while a blocked member is not told explicitly that they were blocked.
     */
    @Transactional(readOnly = true)
    public void ensureCanInteract(Long actorId, Long otherId) {
        if (hasBlocked(actorId, otherId)) {
            throw ApiException.forbidden("You have blocked this member. Unblock them from their profile to continue.");
        }
        if (hasBlocked(otherId, actorId)) {
            throw ApiException.forbidden("You can't contact this member.");
        }
    }
}
