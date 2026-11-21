package com.swapp.user;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.swapp.common.ApiException;
import com.swapp.common.BaseEntity;
import com.swapp.donation.DonationRequest;
import com.swapp.donation.DonationRequestRepository;
import com.swapp.donation.DonationRequestStatus;
import com.swapp.donation.DonationService;
import com.swapp.exchange.ExchangeRepository;
import com.swapp.exchange.ExchangeStatus;
import com.swapp.item.Item;
import com.swapp.item.ItemImage;
import com.swapp.item.ItemLocker;
import com.swapp.item.ItemRepository;
import com.swapp.item.ItemStatus;
import com.swapp.mail.AccountEmails;
import com.swapp.moderation.BlockService;
import com.swapp.notification.NotificationRepository;
import com.swapp.notification.NotificationService;
import com.swapp.notification.NotificationType;
import com.swapp.offer.OfferService;
import com.swapp.realtime.SessionsRevokedEvent;
import com.swapp.security.AuthUser;
import com.swapp.storage.StorageService;
import com.swapp.user.identity.UserIdentityRepository;
import com.swapp.user.token.UserTokenRepository;

/**
 * Self-service account deletion (right to erasure under India's DPDP Act 2023, and GDPR Art. 17).
 *
 * <p>The account row is kept but anonymised, because completed exchanges, offers and messages reference it and the
 * other members keep their history. Everything personal is erased: email, name, bio, location, coordinates, avatar,
 * listing photos, notifications and account tokens. Open activity is closed and the other members are notified.
 * Chat messages stay visible to the other participant, shown as from "Deleted member".
 *
 * <p>Deletion is refused while an exchange or hand-over is in progress, because someone may be about to meet this
 * member; they must finish or cancel it first.
 *
 * <p>Locking follows the system-wide order: every affected item (the member's own listings and items they asked for)
 * is locked first via {@link ItemLocker}, then offers and requests. A concurrent accept therefore either completes
 * before deletion starts (and deletion is refused) or finds the offer already closed.
 */
@Service
@Transactional
public class AccountDeletionService {

    public static final String DELETED_NAME = "Deleted member";
    /** How recently a member without a password must have signed in to delete their account. */
    static final Duration RECENT_SIGN_IN = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final UserRepository users;
    private final ItemRepository items;
    private final ItemLocker locker;
    private final ExchangeRepository exchanges;
    private final DonationRequestRepository donationRequests;
    private final OfferService offerService;
    private final DonationService donationService;
    private final NotificationRepository notificationRepository;
    private final NotificationService notifications;
    private final UserTokenRepository tokens;
    private final StorageService storage;
    private final PasswordEncoder encoder;
    private final AccountEmails emails;
    private final ApplicationEventPublisher events;
    private final BlockService blocks;
    private final UserIdentityRepository identities;

    public AccountDeletionService(UserRepository users, ItemRepository items, ItemLocker locker,
                                  ExchangeRepository exchanges, DonationRequestRepository donationRequests,
                                  OfferService offerService, DonationService donationService,
                                  NotificationRepository notificationRepository, NotificationService notifications,
                                  UserTokenRepository tokens, StorageService storage, PasswordEncoder encoder,
                                  AccountEmails emails, ApplicationEventPublisher events,
                                  BlockService blocks, UserIdentityRepository identities) {
        this.users = users;
        this.items = items;
        this.locker = locker;
        this.exchanges = exchanges;
        this.donationRequests = donationRequests;
        this.offerService = offerService;
        this.donationService = donationService;
        this.notificationRepository = notificationRepository;
        this.notifications = notifications;
        this.tokens = tokens;
        this.storage = storage;
        this.encoder = encoder;
        this.emails = emails;
        this.events = events;
        this.blocks = blocks;
        this.identities = identities;
    }

    public void deleteAccount(AuthUser me, String password) {
        Long userId = me.id();
        User user = users.findById(userId).filter(u -> !u.isDeleted())
                .orElseThrow(() -> ApiException.notFound("User"));
        reauthenticate(me, user, password);

        // 1. Lock every item this deletion touches, in id order (ItemLocker), before reading any state.
        List<Long> ownItemIds = items.findLiveIdsByOwnerId(userId);
        List<Long> requestedItemIds = donationRequests.findItemIdsByRequester(userId,
                EnumSet.of(DonationRequestStatus.PENDING, DonationRequestStatus.ACCEPTED));
        List<Long> lockIds = new ArrayList<>(ownItemIds);
        lockIds.addAll(requestedItemIds);
        Map<Long, Item> locked = lockIds.isEmpty() ? Map.of() : locker.lockAny(lockIds.toArray(Long[]::new));

        // 2. Re-check under the locks: no hand-over may be in progress.
        if (exchanges.countForUser(userId, ExchangeStatus.ACTIVE) > 0
                || donationRequests.countByOwnerIdAndStatus(userId, DonationRequestStatus.ACCEPTED) > 0
                || donationRequests.countByRequesterIdAndStatus(userId, DonationRequestStatus.ACCEPTED) > 0) {
            throw ApiException.conflict("You have an exchange or hand-over in progress. Complete or cancel it "
                    + "first, so nobody is left waiting to meet you.");
        }

        // 3. Close open activity; the other members are notified.
        List<Item> ownItems = ownItemIds.stream().map(locked::get).toList();
        offerService.closePendingOffersInvolving(ownItems, null);
        donationService.closeActiveRequestsFor(ownItems);
        for (DonationRequest request : donationRequests.lockByRequesterAndStatuses(userId,
                EnumSet.of(DonationRequestStatus.PENDING))) {
            request.setStatus(DonationRequestStatus.CANCELLED);
            request.setResolutionNote("The requester deleted their account");
            notifications.notify(request.getOwner(), NotificationType.DONATION_CANCELLED,
                    "Request withdrawn: \"" + request.getItem().getTitle() + "\"",
                    "The member who asked for this item deleted their account.", "/giveaways.html?tab=incoming");
        }

        // 4. Remove listings and their photos (soft delete keeps history references valid).
        List<String> filesToDelete = new ArrayList<>();
        for (Item item : ownItems) {
            item.getImages().stream().map(ItemImage::getStorageKey).forEach(filesToDelete::add);
            item.getImages().clear();
            item.setStatus(ItemStatus.REMOVED);
        }

        // 5. Erase personal data and end every session.
        String originalEmail = user.getEmail();
        String displayName = user.getDisplayName();
        if (user.getAvatarKey() != null) {
            filesToDelete.add(user.getAvatarKey());
        }
        user.setEmail("deleted-" + user.getId() + "@deleted.invalid");
        user.setDisplayName(DELETED_NAME);
        user.setBio(null);
        user.setLocation(null);
        user.setLatitude(null);
        user.setLongitude(null);
        user.setAvatarKey(null);
        user.setEmailVerifiedAt(null);
        user.setPasswordHash(null);
        user.setDeletedAt(BaseEntity.now());
        user.revokeTokens();
        users.saveAndFlush(user);
        notificationRepository.deleteByRecipientId(userId);
        tokens.deleteByUserId(userId);
        identities.deleteByUserId(userId); // the Google account can be used to join again as a new member
        blocks.removeAllFor(userId);

        deleteFilesAfterCommit(filesToDelete);
        events.publishEvent(new SessionsRevokedEvent(userId));
        events.publishEvent(emails.accountDeleted(originalEmail, displayName));
        log.info("User {} deleted their account ({} listings removed)", userId, ownItems.size());
    }

    /**
     * A stolen session alone must not be able to destroy an account. Members with a password re-enter it; members who
     * only use Google must have signed in within the last few minutes (the usual "recent login" rule).
     */
    private void reauthenticate(AuthUser me, User user, String password) {
        if (user.hasPassword()) {
            if (password == null || password.isEmpty()) {
                throw ApiException.badRequest("Enter your password to confirm");
            }
            if (!encoder.matches(password, user.getPasswordHash())) {
                throw ApiException.badRequest("Your password is incorrect");
            }
        } else if (!me.signedInWithin(RECENT_SIGN_IN)) {
            throw ApiException.badRequest("For your security, sign out and sign in with Google again, then delete "
                    + "your account within " + RECENT_SIGN_IN.toMinutes() + " minutes.");
        }
    }

    /** Stored files are deleted only once the erasure has committed; a failure is logged, not fatal. */
    private void deleteFilesAfterCommit(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String key : keys) {
                    try {
                        storage.delete(key);
                    } catch (RuntimeException e) {
                        log.warn("Could not delete stored file {} of a deleted account: {}", key, e.getMessage());
                    }
                }
            }
        });
    }
}
