package com.swapp.donation;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.swapp.common.ApiException;
import com.swapp.common.PageResponse;
import com.swapp.donation.dto.DonationDtos.CreateDonationRequest;
import com.swapp.donation.dto.DonationDtos.DonationRequestDto;
import com.swapp.item.Item;
import com.swapp.item.ItemLocker;
import com.swapp.item.ItemStatus;
import com.swapp.notification.NotificationService;
import com.swapp.notification.NotificationType;
import com.swapp.user.User;
import com.swapp.user.UserRepository;

/**
 * Request workflow for donation and free-giveaway listings: users request the item, the owner picks one requester
 * (reserving the item), and the owner marks it handed over once collected.
 */
@Service
@Transactional
public class DonationService {

    private static final Logger log = LoggerFactory.getLogger(DonationService.class);
    private static final Set<DonationRequestStatus> ACTIVE =
            EnumSet.of(DonationRequestStatus.PENDING, DonationRequestStatus.ACCEPTED);

    private final DonationRequestRepository requests;
    private final UserRepository users;
    private final ItemLocker locker;
    private final NotificationService notifications;

    public DonationService(DonationRequestRepository requests, UserRepository users, ItemLocker locker,
                           NotificationService notifications) {
        this.requests = requests;
        this.users = users;
        this.locker = locker;
        this.notifications = notifications;
    }

    public DonationRequestDto create(Long userId, CreateDonationRequest body) {
        Item item = locker.lockActive(body.itemId()).get(body.itemId());
        if (item.isOwnedBy(userId)) {
            throw ApiException.badRequest("You cannot request your own item");
        }
        if (!item.getListingType().isFreeTransfer()) {
            throw ApiException.badRequest("Only donation and giveaway listings can be requested");
        }
        if (!item.isAvailable()) {
            throw ApiException.conflict("This item is no longer available");
        }
        if (requests.findFirstByItemIdAndRequesterIdAndStatusIn(item.getId(), userId, ACTIVE).isPresent()) {
            throw ApiException.conflict("You have already requested this item");
        }
        User requester = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));

        DonationRequest request = new DonationRequest();
        request.setItem(item);
        request.setRequester(requester);
        request.setOwner(item.getOwner());
        request.setMessage(StringUtils.hasText(body.message()) ? body.message().trim() : null);
        requests.save(request);

        notifications.notify(item.getOwner(), NotificationType.DONATION_REQUESTED,
                requester.getDisplayName() + " is interested in \"" + item.getTitle() + "\"",
                request.getMessage() != null ? "\"" + request.getMessage() + "\""
                        : requester.getDisplayName() + " would like to receive your item.",
                "/giveaways.html?tab=incoming");
        log.info("Donation request {} created for item {} by user {}", request.getId(), item.getId(), userId);
        return DonationRequestDto.from(request);
    }

    public DonationRequestDto accept(Long requestId, Long userId) {
        var refs = refsForOwner(requestId, userId);
        Item item = locker.lockAny(refs.getItemId()).get(refs.getItemId());
        DonationRequest request = requests.lockById(requestId).orElseThrow(() -> ApiException.notFound("Request"));
        if (request.getStatus() != DonationRequestStatus.PENDING) {
            throw ApiException.conflict("This request is no longer pending");
        }
        if (!item.isAvailable()) {
            throw ApiException.conflict("This item is no longer available");
        }
        request.setStatus(DonationRequestStatus.ACCEPTED);
        request.setResolutionNote("Accepted by the owner");
        item.setStatus(ItemStatus.RESERVED);

        for (DonationRequest other : requests.lockByItemsAndStatuses(List.of(item.getId()),
                EnumSet.of(DonationRequestStatus.PENDING))) {
            if (!other.getId().equals(requestId)) {
                declineAutomatically(other, "The owner chose another requester");
            }
        }
        notifications.notify(request.getRequester(), NotificationType.DONATION_ACCEPTED,
                "Your request for \"" + item.getTitle() + "\" was accepted",
                item.getOwner().getDisplayName() + " reserved the item for you. Message them to arrange pick-up.",
                "/giveaways.html?tab=outgoing");
        log.info("Donation request {} accepted", requestId);
        return DonationRequestDto.from(request);
    }

    public DonationRequestDto decline(Long requestId, Long userId) {
        refsForOwner(requestId, userId);
        DonationRequest request = requests.lockById(requestId).orElseThrow(() -> ApiException.notFound("Request"));
        if (request.getStatus() != DonationRequestStatus.PENDING) {
            throw ApiException.conflict("Only pending requests can be declined");
        }
        request.setStatus(DonationRequestStatus.DECLINED);
        request.setResolutionNote("Declined by the owner");
        notifications.notify(request.getRequester(), NotificationType.DONATION_DECLINED,
                "Request declined: \"" + request.getItem().getTitle() + "\"",
                request.getOwner().getDisplayName() + " declined your request.", "/giveaways.html?tab=outgoing");
        return DonationRequestDto.from(request);
    }

    /** Requester may withdraw a pending/accepted request; owner may call off an accepted hand-over. */
    public DonationRequestDto cancel(Long requestId, Long userId) {
        var refs = requests.findRefs(requestId).orElseThrow(() -> ApiException.notFound("Request"));
        boolean isRequester = refs.getRequesterId().equals(userId);
        boolean isOwner = refs.getOwnerId().equals(userId);
        if (!isRequester && !isOwner) {
            throw ApiException.notFound("Request");
        }
        Item item = locker.lockAny(refs.getItemId()).get(refs.getItemId());
        DonationRequest request = requests.lockById(requestId).orElseThrow(() -> ApiException.notFound("Request"));
        DonationRequestStatus status = request.getStatus();
        if (isRequester && !ACTIVE.contains(status)) {
            throw ApiException.conflict("This request can no longer be cancelled");
        }
        if (isOwner && status != DonationRequestStatus.ACCEPTED) {
            throw ApiException.conflict("Only accepted requests can be called off; decline pending requests instead");
        }
        request.setStatus(DonationRequestStatus.CANCELLED);
        request.setResolutionNote(isRequester ? "Withdrawn by the requester" : "Called off by the owner");
        if (status == DonationRequestStatus.ACCEPTED && item.getStatus() == ItemStatus.RESERVED) {
            item.setStatus(ItemStatus.AVAILABLE);
        }
        User recipient = isRequester ? request.getOwner() : request.getRequester();
        User actor = isRequester ? request.getRequester() : request.getOwner();
        notifications.notify(recipient, NotificationType.DONATION_CANCELLED,
                "Request cancelled: \"" + item.getTitle() + "\"",
                actor.getDisplayName() + (isRequester ? " withdrew their request." : " called off the hand-over.")
                        + (status == DonationRequestStatus.ACCEPTED ? " The item is available again." : ""),
                isRequester ? "/giveaways.html?tab=incoming" : "/giveaways.html?tab=outgoing");
        log.info("Donation request {} cancelled by user {}", requestId, userId);
        return DonationRequestDto.from(request);
    }

    public DonationRequestDto complete(Long requestId, Long userId) {
        var refs = refsForOwner(requestId, userId);
        Item item = locker.lockAny(refs.getItemId()).get(refs.getItemId());
        DonationRequest request = requests.lockById(requestId).orElseThrow(() -> ApiException.notFound("Request"));
        if (request.getStatus() != DonationRequestStatus.ACCEPTED) {
            throw ApiException.conflict("Only accepted requests can be marked as handed over");
        }
        request.setStatus(DonationRequestStatus.COMPLETED);
        request.setResolutionNote("Item handed over");
        item.setStatus(ItemStatus.COMPLETED);
        notifications.notify(request.getRequester(), NotificationType.DONATION_COMPLETED,
                "\"" + item.getTitle() + "\" handed over",
                item.getOwner().getDisplayName() + " marked the item as handed over to you. Enjoy!",
                "/giveaways.html?tab=outgoing");
        log.info("Donation request {} completed", requestId);
        return DonationRequestDto.from(request);
    }

    /** Declines pending requests and calls off accepted ones for items that are no longer available. */
    public void closeActiveRequestsFor(Collection<Item> items) {
        Set<Long> ids = items.stream().map(Item::getId).collect(Collectors.toSet());
        for (DonationRequest r : requests.lockByItemsAndStatuses(ids, ACTIVE)) {
            declineAutomatically(r, "The item is no longer available");
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<DonationRequestDto> incoming(Long userId, DonationRequestStatus status, int page, int size) {
        var pageable = PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return PageResponse.from(requests.findByOwnerIdAndStatusIn(userId, statuses(status), pageable),
                DonationRequestDto::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<DonationRequestDto> outgoing(Long userId, DonationRequestStatus status, int page, int size) {
        var pageable = PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return PageResponse.from(requests.findByRequesterIdAndStatusIn(userId, statuses(status), pageable),
                DonationRequestDto::from);
    }

    private void declineAutomatically(DonationRequest request, String reason) {
        request.setStatus(DonationRequestStatus.DECLINED);
        request.setResolutionNote(reason);
        notifications.notify(request.getRequester(), NotificationType.DONATION_DECLINED,
                "Request closed: \"" + request.getItem().getTitle() + "\"", reason + ".",
                "/giveaways.html?tab=outgoing");
    }

    private DonationRequestRepository.RequestRefs refsForOwner(Long requestId, Long userId) {
        var refs = requests.findRefs(requestId).orElseThrow(() -> ApiException.notFound("Request"));
        if (!refs.getOwnerId().equals(userId)) {
            if (refs.getRequesterId().equals(userId)) {
                throw ApiException.forbidden("Only the item owner can do this");
            }
            throw ApiException.notFound("Request");
        }
        return refs;
    }

    private static Collection<DonationRequestStatus> statuses(DonationRequestStatus status) {
        return status == null ? EnumSet.allOf(DonationRequestStatus.class) : EnumSet.of(status);
    }
}
