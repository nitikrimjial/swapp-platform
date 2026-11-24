package com.swapp.moderation.audit;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.swapp.common.PageResponse;
import com.swapp.item.Item;
import com.swapp.moderation.dto.AdminDtos.AuditEntryDto;
import com.swapp.user.User;

/**
 * Records staff actions. An entry is written in the same transaction as the action itself, so the two can never
 * disagree: if the action rolls back, so does its entry, and an action cannot commit without one.
 */
@Service
public class AuditService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final int MAX_NOTE = 500;

    private final AuditEntryRepository entries;

    public AuditService(AuditEntryRepository entries) {
        this.entries = entries;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(User actor, AuditAction action, User targetUser, Item item, Long reportId, String note) {
        String trimmed = note == null || note.isBlank() ? null : note.trim();
        if (trimmed != null && trimmed.length() > MAX_NOTE) {
            trimmed = trimmed.substring(0, MAX_NOTE);
        }
        entries.save(new AuditEntry(actor, action, targetUser, item, reportId, trimmed));
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEntryDto> list(int page, int size) {
        return PageResponse.from(entries.findAllBy(PageResponse.request(page, size, NEWEST_FIRST)),
                AuditEntryDto::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEntryDto> forMember(Long userId, int page, int size) {
        return PageResponse.from(entries.findByTargetUserId(userId, PageResponse.request(page, size, NEWEST_FIRST)),
                AuditEntryDto::from);
    }
}
