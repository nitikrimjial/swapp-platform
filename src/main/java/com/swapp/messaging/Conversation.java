package com.swapp.messaging;

import java.time.Instant;

import com.swapp.common.BaseEntity;
import com.swapp.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A private thread between exactly two users. Participants are stored in id order (user_one_id &lt; user_two_id). */
@Entity
@Table(name = "conversations")
@Getter
@Setter
@NoArgsConstructor
public class Conversation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_one_id", nullable = false)
    private User userOne;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_two_id", nullable = false)
    private User userTwo;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    public boolean hasParticipant(Long userId) {
        return userOne.getId().equals(userId) || userTwo.getId().equals(userId);
    }

    public User otherParticipant(Long userId) {
        return userOne.getId().equals(userId) ? userTwo : userOne;
    }
}
