package com.swapp.notification;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.swapp.common.ApiException;
import com.swapp.common.BaseEntity;
import com.swapp.common.PageResponse;
import com.swapp.realtime.RealtimeEvent;
import com.swapp.user.User;

@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final ApplicationEventPublisher events;

    public NotificationService(NotificationRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /**
     * Persists a notification as part of the caller's transaction. The realtime push is dispatched only after the
     * transaction commits, so users are never told about changes that were rolled back.
     */
    @Transactional
    public void notify(User recipient, NotificationType type, String title, String body, String link) {
        Notification n = new Notification();
        n.setRecipient(recipient);
        n.setType(type);
        n.setTitle(truncate(title, 160));
        n.setBody(truncate(body, 500));
        n.setLink(link);
        repository.save(n);
        events.publishEvent(new RealtimeEvent(recipient.getId(), "notification", NotificationDto.from(n)));
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationDto> list(Long userId, boolean unreadOnly, int page, int size) {
        var pageable = PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "id"));
        var result = unreadOnly
                ? repository.findByRecipientIdAndReadAtIsNull(userId, pageable)
                : repository.findByRecipientId(userId, pageable);
        return PageResponse.from(result, NotificationDto::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return repository.countByRecipientIdAndReadAtIsNull(userId);
    }

    @Transactional
    public NotificationDto markRead(Long id, Long userId) {
        Notification n = repository.findByIdAndRecipientId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Notification"));
        if (n.getReadAt() == null) {
            n.setReadAt(BaseEntity.now());
        }
        return NotificationDto.from(n);
    }

    @Transactional
    public int markAllRead(Long userId) {
        return repository.markAllRead(userId, BaseEntity.now());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
