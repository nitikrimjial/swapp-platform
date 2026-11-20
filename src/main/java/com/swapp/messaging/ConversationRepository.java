package com.swapp.messaging;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByUserOneIdAndUserTwoId(Long userOneId, Long userTwoId);

    /** Race-free "create if absent": the unique (user_one_id, user_two_id) key makes concurrent inserts collapse. */
    @Modifying
    @Query(value = "INSERT INTO conversations (user_one_id, user_two_id, last_message_at, created_at, updated_at) "
            + "VALUES (:one, :two, :now, :now, :now) ON CONFLICT (user_one_id, user_two_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("one") Long userOneId, @Param("two") Long userTwoId, @Param("now") Instant now);

    @EntityGraph(attributePaths = {"userOne", "userTwo"})
    @Query("select c from Conversation c where c.userOne.id = :userId or c.userTwo.id = :userId")
    Page<Conversation> findForUser(@Param("userId") Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"userOne", "userTwo"})
    Optional<Conversation> findWithParticipantsById(Long id);
}
