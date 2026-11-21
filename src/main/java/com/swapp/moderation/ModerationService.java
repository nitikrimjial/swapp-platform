package com.swapp.moderation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.swapp.common.ApiException;
import com.swapp.common.BaseEntity;
import com.swapp.common.PageResponse;
import com.swapp.donation.DonationRequest;
import com.swapp.donation.DonationRequestRepository;
import com.swapp.donation.DonationRequestStatus;
import com.swapp.donation.DonationService;
import com.swapp.exchange.Exchange;
import com.swapp.exchange.ExchangeRepository;
import com.swapp.exchange.ExchangeStatus;
import com.swapp.item.Item;
import com.swapp.item.ItemLocker;
import com.swapp.item.ItemRepository;
import com.swapp.item.ItemStatus;
import com.swapp.mail.AccountEmails;
import com.swapp.moderation.dto.ModerationDtos.ModerationSummary;
import com.swapp.moderation.dto.ModerationDtos.ReportDto;
import com.swapp.moderation.dto.ModerationDtos.ResolveReportRequest;
import com.swapp.moderation.dto.ModerationDtos.SuspendedUserDto;
import com.swapp.notification.NotificationService;
import com.swapp.notification.NotificationType;
import com.swapp.offer.OfferService;
import com.swapp.realtime.SessionsRevokedEvent;
import com.swapp.user.Role;
import com.swapp.user.User;
import com.swapp.user.UserRepository;

/**
 * Moderator tools: review the report queue, dismiss reports, take listings down, and suspend or reinstate members.
 *
 * <p>Every action that changes items follows the system-wide locking order (items via {@link ItemLocker} first, then
 * exchanges, requests and reports), so moderation can run safely alongside members' own actions.
 */
@Service
@Transactional
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);
    private static final EnumSet<DonationRequestStatus> ACTIVE_REQUESTS =
            EnumSet.of(DonationRequestStatus.PENDING, DonationRequestStatus.ACCEPTED);

    private final ReportRepository reports;
    private final UserRepository users;
    private final ItemRepository items;
    private final ItemLocker locker;
    private final ExchangeRepository exchanges;
    private final DonationRequestRepository donationRequests;
    private final OfferService offerService;
    private final DonationService donationService;
    private final NotificationService notifications;
    private final AccountEmails emails;
    private final ApplicationEventPublisher events;

    public ModerationService(ReportRepository reports, UserRepository users, ItemRepository items, ItemLocker locker,
                             ExchangeRepository exchanges, DonationRequestRepository donationRequests,
                             OfferService offerService, DonationService donationService,
                             NotificationService notifications, AccountEmails emails,
                             ApplicationEventPublisher events) {
        this.reports = reports;
        this.users = users;
        this.items = items;
        this.locker = locker;
        this.exchanges = exchanges;
        this.donationRequests = donationRequests;
        this.offerService = offerService;
        this.donationService = donationService;
        this.notifications = notifications;
        this.emails = emails;
        this.events = events;
    }

    // ---------------------------------------------------------------- queue

    @Transactional(readOnly = true)
    public PageResponse<ReportDto> list(ReportStatus status, int page, int size) {
        // Open reports oldest first (a queue); resolved ones newest first (an audit log).
        Sort sort = status == ReportStatus.OPEN ? Sort.by("createdAt") : Sort.by(Sort.Direction.DESC, "resolvedAt");
        var result = reports.findByStatus(status, PageResponse.request(page, size, sort));
        return PageResponse.from(result, r -> ReportDto.from(r,
                reports.countByReportedUserIdAndStatus(r.getReportedUser().getId(), ReportStatus.OPEN)));
    }

    @Transactional(readOnly = true)
    public ModerationSummary summary() {
        return new ModerationSummary(reports.countByStatus(ReportStatus.OPEN), users.countBySuspendedAtIsNotNull());
    }

    @Transactional(readOnly = true)
    public PageResponse<SuspendedUserDto> suspended(int page, int size) {
        var pageable = PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "suspendedAt"));
        return PageResponse.from(users.findBySuspendedAtIsNotNull(pageable), SuspendedUserDto::from);
    }

    // ---------------------------------------------------------------- decisions

    public ReportDto resolve(Long moderatorId, Long reportId, ResolveReportRequest request) {
        var refs = reports.findRefs(reportId).orElseThrow(() -> ApiException.notFound("Report"));
        User moderator = users.findById(moderatorId).orElseThrow(() -> ApiException.notFound("User"));
        String note = StringUtils.hasText(request.note()) ? request.note().trim() : null;
        Report report;
        switch (request.action()) {
            case DISMISS -> {
                report = lockOpen(reportId);
                report.resolve(ReportResolution.DISMISSED, note, moderator);
            }
            case REMOVE_ITEM -> {
                if (refs.getTargetType() != ReportTargetType.ITEM) {
                    throw ApiException.badRequest("Only reports about a listing can remove a listing");
                }
                Item item = locker.lockAny(refs.getItemId()).get(refs.getItemId());
                report = lockOpen(reportId);
                takeDown(item, note);
                report.resolve(ReportResolution.ITEM_REMOVED, note, moderator);
                for (Report other : reports.lockOpenForItem(item.getId(), reportId)) {
                    other.resolve(ReportResolution.ITEM_REMOVED, "Resolved with report #" + reportId, moderator);
                    notifyReporter(other);
                }
            }
            case SUSPEND_USER -> {
                if (note == null) {
                    throw ApiException.badRequest("Give a reason for the suspension; the member will see it");
                }
                suspendLocked(refs.getReportedUserId(), note); // takes the item locks first
                report = lockOpen(reportId);
                report.resolve(ReportResolution.USER_SUSPENDED, note, moderator);
                for (Report other : reports.lockOpenForUser(refs.getReportedUserId(), reportId)) {
                    other.resolve(ReportResolution.USER_SUSPENDED, "Resolved with report #" + reportId, moderator);
                    notifyReporter(other);
                }
            }
            default -> throw new IllegalStateException("Unknown action " + request.action());
        }
        notifyReporter(report);
        log.info("Report {} resolved as {} by moderator {}", reportId, report.getResolution(), moderatorId);
        return ReportDto.from(report, reports.countByReportedUserIdAndStatus(refs.getReportedUserId(),
                ReportStatus.OPEN));
    }

    public SuspendedUserDto suspend(Long moderatorId, Long userId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw ApiException.badRequest("Give a reason for the suspension; the member will see it");
        }
        if (moderatorId.equals(userId)) {
            throw ApiException.badRequest("You cannot suspend yourself");
        }
        User user = suspendLocked(userId, reason.trim());
        log.info("User {} suspended by moderator {}", userId, moderatorId);
        return SuspendedUserDto.from(user);
    }

    public void reinstate(Long moderatorId, Long userId) {
        User user = users.findById(userId).filter(u -> !u.isDeleted())
                .orElseThrow(() -> ApiException.notFound("User"));
        if (!user.isSuspended()) {
            throw ApiException.conflict("This member is not suspended");
        }
        user.setSuspendedAt(null);
        user.setSuspensionReason(null);
        events.publishEvent(emails.accountReinstated(user.getEmail(), user.getDisplayName()));
        log.info("User {} reinstated by moderator {}", userId, moderatorId);
    }

    // ---------------------------------------------------------------- internals

    private Report lockOpen(Long reportId) {
        Report report = reports.lockById(reportId).orElseThrow(() -> ApiException.notFound("Report"));
        if (!report.isOpen()) {
            throw ApiException.conflict("This report has already been resolved");
        }
        return report;
    }

    /** Takes a listing down. The caller holds the item lock. */
    private void takeDown(Item item, String note) {
        if (item.getStatus() == ItemStatus.REMOVED) {
            return; // the owner already removed it
        }
        if (item.getStatus() == ItemStatus.RESERVED) {
            throw ApiException.conflict("This listing is part of an exchange or hand-over in progress. "
                    + "Suspend the member instead, which cancels it.");
        }
        offerService.closePendingOffersInvolving(List.of(item), null);
        donationService.closeActiveRequestsFor(List.of(item));
        item.setStatus(ItemStatus.REMOVED);
        User owner = item.getOwner();
        notifications.notify(owner, NotificationType.LISTING_REMOVED, "Listing removed: \"" + item.getTitle() + "\"",
                "Our moderators removed this listing for breaking the community guidelines."
                        + (note == null ? "" : " " + note), "/my-listings.html");
        events.publishEvent(emails.listingRemoved(owner.getEmail(), owner.getDisplayName(), item.getTitle(), note));
    }

    /**
     * Suspends a member: cancels their exchanges and hand-overs in progress (nobody should go to meet them), closes
     * their open offers and requests, hides their listings, revokes every session and emails them the reason.
     */
    private User suspendLocked(Long userId, String reason) {
        User user = users.findById(userId).filter(u -> !u.isDeleted())
                .orElseThrow(() -> ApiException.notFound("User"));
        if (user.getRole() == Role.ADMIN) {
            throw ApiException.badRequest("Moderators cannot be suspended from here");
        }
        if (user.isSuspended()) {
            throw ApiException.conflict("This member is already suspended");
        }

        // 1. Lock every affected item first, in id order.
        List<Long> ownItemIds = items.findLiveIdsByOwnerId(userId);
        var activeExchanges = exchanges.findActiveRefsForUser(userId);
        List<Long> lockIds = new ArrayList<>(ownItemIds);
        activeExchanges.forEach(e -> {
            lockIds.add(e.getOwnerItemId());
            lockIds.add(e.getRequesterItemId());
        });
        lockIds.addAll(donationRequests.findItemIdsByRequester(userId, ACTIVE_REQUESTS));
        Map<Long, Item> locked = lockIds.isEmpty() ? Map.of() : locker.lockAny(lockIds.toArray(Long[]::new));

        // 2. Cancel exchanges in progress; the other member's item becomes available again.
        for (var refs : activeExchanges) {
            Exchange exchange = exchanges.lockById(refs.getId()).orElseThrow();
            if (exchange.getStatus() != ExchangeStatus.ACTIVE) {
                continue;
            }
            exchange.setStatus(ExchangeStatus.CANCELLED);
            exchange.setCancelledAt(BaseEntity.now());
            release(exchange.getOwnerItem());
            release(exchange.getRequesterItem());
            notifications.notify(exchange.otherParty(userId), NotificationType.EXCHANGE_CANCELLED,
                    "Exchange cancelled",
                    "Your exchange of \"" + exchange.getOwnerItem().getTitle() + "\" for \""
                            + exchange.getRequesterItem().getTitle() + "\" was cancelled because the other member's "
                            + "account was suspended. Your item is available again.",
                    "/exchanges.html?id=" + exchange.getId());
        }

        // 3. Withdraw their requests for other members' giveaways.
        for (DonationRequest request : donationRequests.lockByRequesterAndStatuses(userId, ACTIVE_REQUESTS)) {
            if (request.getStatus() == DonationRequestStatus.ACCEPTED) {
                release(request.getItem());
            }
            request.setStatus(DonationRequestStatus.CANCELLED);
            request.setResolutionNote("The requester's account was suspended");
            notifications.notify(request.getOwner(), NotificationType.DONATION_CANCELLED,
                    "Request withdrawn: \"" + request.getItem().getTitle() + "\"",
                    "The member who asked for this item can no longer use Swapp. Your item is available again.",
                    "/giveaways.html?tab=incoming");
        }

        // 4. Close offers and requests on their listings, then hide the listings.
        List<Item> ownItems = ownItemIds.stream().map(locked::get).toList();
        offerService.closePendingOffersInvolving(ownItems, null);
        donationService.closeActiveRequestsFor(ownItems);
        for (Item item : ownItems) {
            if (item.getStatus() == ItemStatus.AVAILABLE || item.getStatus() == ItemStatus.RESERVED) {
                item.setStatus(ItemStatus.UNAVAILABLE);
            }
        }

        // 5. Suspend and end every session.
        user.setSuspendedAt(BaseEntity.now());
        user.setSuspensionReason(reason.length() <= 500 ? reason : reason.substring(0, 500));
        user.revokeTokens();
        users.saveAndFlush(user);
        events.publishEvent(new SessionsRevokedEvent(userId));
        events.publishEvent(emails.accountSuspended(user.getEmail(), user.getDisplayName(), reason));
        return user;
    }

    private void notifyReporter(Report report) {
        boolean actioned = report.getResolution() != ReportResolution.DISMISSED;
        notifications.notify(report.getReporter(), NotificationType.REPORT_RESOLVED, "We reviewed your report",
                actioned ? "Thanks for letting us know. We found that it broke our guidelines and took action."
                        : "Thanks for letting us know. We reviewed it and didn't find a breach of our guidelines.",
                null);
    }

    private static void release(Item item) {
        if (item.getStatus() == ItemStatus.RESERVED) {
            item.setStatus(ItemStatus.AVAILABLE);
        }
    }
}
