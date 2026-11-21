package com.swapp.messaging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.swapp.common.ApiException;
import com.swapp.common.BaseEntity;
import com.swapp.common.PageResponse;
import com.swapp.item.Item;
import com.swapp.item.ItemRepository;
import com.swapp.moderation.BlockService;
import com.swapp.messaging.dto.MessagingDtos.ConversationDto;
import com.swapp.messaging.dto.MessagingDtos.MessageDto;
import com.swapp.messaging.dto.MessagingDtos.StartConversationRequest;
import com.swapp.realtime.RealtimeEvent;
import com.swapp.user.User;
import com.swapp.user.UserRepository;
import com.swapp.user.dto.UserDtos.UserSummaryDto;

@Service
@Transactional
public class MessagingService {

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final UserRepository users;
    private final ItemRepository items;
    private final ApplicationEventPublisher events;
    private final BlockService blocks;

    public MessagingService(ConversationRepository conversations, MessageRepository messages, UserRepository users,
                            ItemRepository items, ApplicationEventPublisher events, BlockService blocks) {
        this.conversations = conversations;
        this.messages = messages;
        this.users = users;
        this.items = items;
        this.events = events;
        this.blocks = blocks;
    }

    /** Opens (or reuses) the conversation with another user and posts the first message. */
    public ConversationDto start(Long userId, StartConversationRequest request) {
        if (request.recipientId().equals(userId)) {
            throw ApiException.badRequest("You cannot message yourself");
        }
        User recipient = users.findById(request.recipientId()).filter(User::isActive)
                .orElseThrow(() -> ApiException.notFound("User"));
        blocks.ensureCanInteract(userId, recipient.getId());
        long one = Math.min(userId, recipient.getId());
        long two = Math.max(userId, recipient.getId());
        conversations.insertIfAbsent(one, two, BaseEntity.now());
        Conversation conversation = conversations.findByUserOneIdAndUserTwoId(one, two)
                .orElseThrow(() -> new IllegalStateException("Conversation was not created"));
        Message message = post(conversation, userId, request.body(), request.itemId());
        return new ConversationDto(conversation.getId(), UserSummaryDto.from(recipient), MessageDto.from(message), 0,
                conversation.getLastMessageAt());
    }

    public MessageDto send(Long userId, Long conversationId, String body, Long itemId) {
        Conversation conversation = participantConversation(conversationId, userId);
        User other = conversation.otherParticipant(userId);
        if (!other.isActive()) {
            throw ApiException.conflict("This member's account is no longer active, so they can't receive messages");
        }
        blocks.ensureCanInteract(userId, other.getId());
        return MessageDto.from(post(conversation, userId, body, itemId));
    }

    @Transactional(readOnly = true)
    public PageResponse<ConversationDto> list(Long userId, int page, int size) {
        var pageable = PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "lastMessageAt"));
        var result = conversations.findForUser(userId, pageable);
        List<Long> ids = result.getContent().stream().map(Conversation::getId).toList();
        Map<Long, Message> last = ids.isEmpty() ? Map.of()
                : messages.findLastMessages(ids).stream()
                        .collect(Collectors.toMap(m -> m.getConversation().getId(), Function.identity()));
        Map<Long, Long> unread = ids.isEmpty() ? Map.of()
                : messages.countUnreadByConversation(ids, userId).stream()
                        .collect(Collectors.toMap(MessageRepository.UnreadCount::getConversationId,
                                MessageRepository.UnreadCount::getUnread));
        return PageResponse.from(result, c -> new ConversationDto(c.getId(),
                UserSummaryDto.from(c.otherParticipant(userId)),
                last.containsKey(c.getId()) ? MessageDto.from(last.get(c.getId())) : null,
                unread.getOrDefault(c.getId(), 0L), c.getLastMessageAt()));
    }

    @Transactional(readOnly = true)
    public ConversationDto get(Long userId, Long conversationId) {
        Conversation c = participantConversation(conversationId, userId);
        return new ConversationDto(c.getId(), UserSummaryDto.from(c.otherParticipant(userId)), null, 0,
                c.getLastMessageAt());
    }

    /** Returns messages oldest-first; pass {@code beforeId} to page back through history. */
    @Transactional(readOnly = true)
    public List<MessageDto> messages(Long userId, Long conversationId, Long beforeId, int size) {
        participantConversation(conversationId, userId);
        var page = PageRequest.of(0, Math.min(Math.max(size, 1), 100));
        List<Message> batch = beforeId == null
                ? messages.findLatest(conversationId, page)
                : messages.findBefore(conversationId, beforeId, page);
        List<MessageDto> result = new ArrayList<>(batch.stream().map(MessageDto::from).toList());
        Collections.reverse(result);
        return result;
    }

    public int markRead(Long userId, Long conversationId) {
        Conversation c = participantConversation(conversationId, userId);
        int updated = messages.markRead(conversationId, userId, BaseEntity.now());
        if (updated > 0) {
            Long other = c.otherParticipant(userId).getId();
            events.publishEvent(new RealtimeEvent(other, "messages-read", Map.of("conversationId", conversationId)));
        }
        return updated;
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return messages.countUnreadForUser(userId);
    }

    private Message post(Conversation conversation, Long senderId, String body, Long itemId) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) {
            throw ApiException.badRequest("Message cannot be empty");
        }
        Item item = null;
        if (itemId != null) {
            item = items.findActiveById(itemId).orElseThrow(() -> ApiException.notFound("Item"));
        }
        User sender = conversation.getUserOne().getId().equals(senderId)
                ? conversation.getUserOne() : conversation.getUserTwo();
        Message message = new Message();
        message.setConversation(conversation);
        message.setSender(sender);
        message.setItem(item);
        message.setBody(text);
        messages.save(message);
        conversation.setLastMessageAt(message.getCreatedAt());

        Long recipientId = conversation.otherParticipant(senderId).getId();
        events.publishEvent(new RealtimeEvent(recipientId, "message", MessageDto.from(message)));
        return message;
    }

    private Conversation participantConversation(Long conversationId, Long userId) {
        Conversation c = conversations.findWithParticipantsById(conversationId)
                .orElseThrow(() -> ApiException.notFound("Conversation"));
        if (!c.hasParticipant(userId)) {
            // 404 rather than 403 so conversation ids cannot be probed.
            throw ApiException.notFound("Conversation");
        }
        return c;
    }
}
