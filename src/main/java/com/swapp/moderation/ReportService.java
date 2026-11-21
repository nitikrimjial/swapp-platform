package com.swapp.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.swapp.common.ApiException;
import com.swapp.item.Item;
import com.swapp.item.ItemRepository;
import com.swapp.messaging.Message;
import com.swapp.messaging.MessageRepository;
import com.swapp.moderation.dto.ModerationDtos.CreateReportRequest;
import com.swapp.moderation.dto.ModerationDtos.ReportReceipt;
import com.swapp.user.User;
import com.swapp.user.UserRepository;

/**
 * Lets members report another member, a listing or a message they received. A snapshot of what they saw is stored
 * as evidence. Members may only report things they can see (others' messages only in their own conversations), and
 * each target only once while a report is open.
 */
@Service
@Transactional
public class ReportService {

    /** Generous for genuine use; stops one account flooding the moderation queue. */
    static final int MAX_OPEN_REPORTS_PER_MEMBER = 20;
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ReportRepository reports;
    private final UserRepository users;
    private final ItemRepository items;
    private final MessageRepository messages;

    public ReportService(ReportRepository reports, UserRepository users, ItemRepository items,
                         MessageRepository messages) {
        this.reports = reports;
        this.users = users;
        this.items = items;
        this.messages = messages;
    }

    public ReportReceipt create(Long reporterId, CreateReportRequest request) {
        User reporter = users.findById(reporterId).orElseThrow(() -> ApiException.notFound("User"));
        Report report = new Report();
        report.setReporter(reporter);
        report.setTargetType(request.targetType());
        report.setReason(request.reason());
        report.setDetails(StringUtils.hasText(request.details()) ? request.details().trim() : null);

        switch (request.targetType()) {
            case USER -> {
                User target = users.findById(request.targetId()).filter(u -> !u.isDeleted())
                        .orElseThrow(() -> ApiException.notFound("User"));
                report.setReportedUser(target);
                report.setEvidence(snapshot("Name: " + target.getDisplayName()
                        + (target.getBio() == null ? "" : "\nBio: " + target.getBio())));
            }
            case ITEM -> {
                Item item = items.findActiveById(request.targetId()).orElseThrow(() -> ApiException.notFound("Item"));
                report.setItem(item);
                report.setReportedUser(item.getOwner());
                report.setEvidence(snapshot("Title: " + item.getTitle() + "\nDescription: " + item.getDescription()));
            }
            case MESSAGE -> {
                Message message = messages.findById(request.targetId())
                        .filter(m -> m.getConversation().hasParticipant(reporterId))
                        // 404, not 403: message ids in other people's conversations cannot be probed.
                        .orElseThrow(() -> ApiException.notFound("Message"));
                report.setMessage(message);
                report.setReportedUser(message.getSender());
                report.setEvidence(snapshot(message.getBody()));
            }
        }

        Long reportedId = report.getReportedUser().getId();
        if (reportedId.equals(reporterId)) {
            throw ApiException.badRequest("You can't report yourself or your own content");
        }
        if (alreadyReported(reporterId, report)) {
            throw ApiException.conflict("You've already reported this. Our team will review it soon.");
        }
        if (reports.countByReporterIdAndStatus(reporterId, ReportStatus.OPEN) >= MAX_OPEN_REPORTS_PER_MEMBER) {
            throw ApiException.conflict("You have many reports waiting for review. Please wait until some are "
                    + "resolved before filing more.");
        }
        // The partial unique index uk_reports_open_target backs the duplicate check under concurrency.
        reports.saveAndFlush(report);
        log.info("Report {} filed: {} {} by user {} against user {}", report.getId(), report.getTargetType(),
                report.getReason(), reporterId, reportedId);
        return new ReportReceipt(report.getId(), report.getStatus(), report.getCreatedAt());
    }

    private boolean alreadyReported(Long reporterId, Report report) {
        return switch (report.getTargetType()) {
            case USER -> reports.existsByReporterIdAndTargetTypeAndReportedUserIdAndStatus(reporterId,
                    ReportTargetType.USER, report.getReportedUser().getId(), ReportStatus.OPEN);
            case ITEM -> reports.existsByReporterIdAndItemIdAndStatus(reporterId, report.getItem().getId(),
                    ReportStatus.OPEN);
            case MESSAGE -> reports.existsByReporterIdAndMessageIdAndStatus(reporterId, report.getMessage().getId(),
                    ReportStatus.OPEN);
        };
    }

    private static String snapshot(String text) {
        return text.length() <= 4200 ? text : text.substring(0, 4199) + "…";
    }
}
