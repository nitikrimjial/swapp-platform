package com.swapp.messaging.dto;

import java.time.Instant;

import com.swapp.item.dto.ItemDtos.ItemSummaryDto;
import com.swapp.messaging.Message;
import com.swapp.user.dto.UserDtos.UserSummaryDto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class MessagingDtos {

    private MessagingDtos() {
    }

    public record StartConversationRequest(
            @NotNull(message = "Recipient is required") Long recipientId,
            Long itemId,
            @NotBlank(message = "Message cannot be empty") @Size(max = 2000, message = "Message is too long") String body) {
    }

    public record SendMessageRequest(
            @NotBlank(message = "Message cannot be empty") @Size(max = 2000, message = "Message is too long") String body,
            Long itemId) {
    }

    public record MessageDto(Long id, Long conversationId, Long senderId, String body, ItemRef item, boolean read,
                             Instant createdAt) {
        public static MessageDto from(Message m) {
            ItemRef item = m.getItem() == null ? null : ItemRef.from(ItemSummaryDto.from(m.getItem()));
            return new MessageDto(m.getId(), m.getConversation().getId(), m.getSender().getId(), m.getBody(), item,
                    m.getReadAt() != null, m.getCreatedAt());
        }
    }

    /** Compact listing reference shown as a card inside the chat. */
    public record ItemRef(Long id, String title, String coverImageUrl) {
        static ItemRef from(ItemSummaryDto s) {
            return new ItemRef(s.id(), s.title(), s.coverImageUrl());
        }
    }

    public record ConversationDto(Long id, UserSummaryDto otherUser, MessageDto lastMessage, long unreadCount,
                                  Instant lastMessageAt) {
    }
}
