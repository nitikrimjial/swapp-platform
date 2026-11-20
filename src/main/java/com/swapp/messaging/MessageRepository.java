package com.swapp.messaging;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<Message, Long> {

    @EntityGraph(attributePaths = {"sender", "item"})
    @Query("select m from Message m where m.conversation.id = :conversationId order by m.id desc")
    List<Message> findLatest(@Param("conversationId") Long conversationId, Pageable pageable);

    @EntityGraph(attributePaths = {"sender", "item"})
    @Query("select m from Message m where m.conversation.id = :conversationId and m.id < :beforeId order by m.id desc")
    List<Message> findBefore(@Param("conversationId") Long conversationId, @Param("beforeId") Long beforeId,
                             Pageable pageable);

    @EntityGraph(attributePaths = {"sender"})
    @Query("select m from Message m where m.id in "
            + "(select max(m2.id) from Message m2 where m2.conversation.id in :ids group by m2.conversation.id)")
    List<Message> findLastMessages(@Param("ids") Collection<Long> conversationIds);

    @Query("select m.conversation.id as conversationId, count(m) as unread from Message m "
            + "where m.conversation.id in :ids and m.sender.id <> :userId and m.readAt is null "
            + "group by m.conversation.id")
    List<UnreadCount> countUnreadByConversation(@Param("ids") Collection<Long> conversationIds,
                                                @Param("userId") Long userId);

    @Query("select count(m) from Message m where (m.conversation.userOne.id = :userId or m.conversation.userTwo.id = :userId) "
            + "and m.sender.id <> :userId and m.readAt is null")
    long countUnreadForUser(@Param("userId") Long userId);

    @Modifying
    @Query("update Message m set m.readAt = :now, m.updatedAt = :now "
            + "where m.conversation.id = :conversationId and m.sender.id <> :userId and m.readAt is null")
    int markRead(@Param("conversationId") Long conversationId, @Param("userId") Long userId,
                 @Param("now") Instant now);

    interface UnreadCount {
        Long getConversationId();

        Long getUnread();
    }
}
