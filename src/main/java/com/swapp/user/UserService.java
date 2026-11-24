package com.swapp.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.swapp.common.ApiException;
import com.swapp.common.GeoPoint;
import com.swapp.donation.DonationRequestRepository;
import com.swapp.donation.DonationRequestStatus;
import com.swapp.exchange.ExchangeRepository;
import com.swapp.exchange.ExchangeStatus;
import com.swapp.item.ItemRepository;
import com.swapp.item.ItemStatus;
import com.swapp.mail.AccountEmails;
import com.swapp.messaging.MessageRepository;
import com.swapp.moderation.BlockService;
import com.swapp.notification.NotificationRepository;
import com.swapp.offer.OfferRepository;
import com.swapp.offer.OfferStatus;
import com.swapp.realtime.SessionsRevokedEvent;
import com.swapp.security.JwtService;
import com.swapp.security.JwtService.IssuedToken;
import com.swapp.storage.ImageValidator;
import com.swapp.storage.StorageQuota;
import com.swapp.storage.StorageService;
import com.swapp.user.dto.UserDtos.ChangePasswordRequest;
import com.swapp.user.dto.UserDtos.PublicProfileDto;
import com.swapp.user.dto.UserDtos.UpdateProfileRequest;
import com.swapp.user.dto.UserDtos.UserDto;
import com.swapp.user.dto.UserDtos.UserSummaryCounts;

@Service
@Transactional
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;
    private final ItemRepository items;
    private final OfferRepository offers;
    private final ExchangeRepository exchanges;
    private final DonationRequestRepository donationRequests;
    private final NotificationRepository notifications;
    private final MessageRepository messages;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;
    private final StorageService storage;
    private final ImageValidator imageValidator;
    private final StorageQuota storageQuota;
    private final ApplicationEventPublisher events;
    private final AccountEmails emails;
    private final BlockService blocks;

    public UserService(UserRepository users, ItemRepository items, OfferRepository offers,
                       ExchangeRepository exchanges, DonationRequestRepository donationRequests,
                       NotificationRepository notifications, MessageRepository messages, PasswordEncoder encoder,
                       JwtService jwtService, StorageService storage, ImageValidator imageValidator,
                       StorageQuota storageQuota, ApplicationEventPublisher events,
                       AccountEmails emails, BlockService blocks) {
        this.users = users;
        this.items = items;
        this.offers = offers;
        this.exchanges = exchanges;
        this.donationRequests = donationRequests;
        this.notifications = notifications;
        this.messages = messages;
        this.encoder = encoder;
        this.jwtService = jwtService;
        this.storage = storage;
        this.imageValidator = imageValidator;
        this.storageQuota = storageQuota;
        this.events = events;
        this.emails = emails;
        this.blocks = blocks;
    }

    @Transactional(readOnly = true)
    public UserDto me(Long userId) {
        return UserDto.from(load(userId));
    }

    public UserDto updateProfile(Long userId, UpdateProfileRequest request) {
        User user = load(userId);
        user.setDisplayName(request.displayName().trim());
        user.setBio(StringUtils.hasText(request.bio()) ? request.bio().trim() : null);
        user.setLocation(StringUtils.hasText(request.location()) ? request.location().trim() : null);
        GeoPoint point = GeoPoint.ofNullable(request.latitude(), request.longitude());
        user.setLatitude(point == null ? null : point.rounded().latitude());
        user.setLongitude(point == null ? null : point.rounded().longitude());
        return UserDto.from(user);
    }

    public UserDto updateAvatar(Long userId, MultipartFile file) {
        User user = load(userId);
        var image = imageValidator.validate(file);
        // One avatar per user, so only the site-wide ceiling applies; the old file is deleted after commit.
        storageQuota.checkSiteCapacity(image.data().length);
        String previous = user.getAvatarKey();
        user.setAvatarKey(storage.store(image.data(), image.contentType()));
        deleteAfterCommit(previous);
        return UserDto.from(user);
    }

    public UserDto removeAvatar(Long userId) {
        User user = load(userId);
        String previous = user.getAvatarKey();
        user.setAvatarKey(null);
        deleteAfterCommit(previous);
        return UserDto.from(user);
    }

    /** Changes the password and revokes every other session; returns a fresh token for the current one. */
    public IssuedToken changePassword(Long userId, ChangePasswordRequest request) {
        User user = load(userId);
        if (!user.hasPassword()) {
            // Setting a first password goes through the emailed reset link, which proves control of the address.
            throw ApiException.badRequest("Your account doesn't have a password yet. Use \"Set a password\" to get a "
                    + "link by email.");
        }
        if (!encoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("Your current password is incorrect");
        }
        if (encoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("Choose a password that is different from your current one");
        }
        user.setPasswordHash(encoder.encode(request.newPassword()));
        user.revokeTokens();
        users.saveAndFlush(user);
        events.publishEvent(new SessionsRevokedEvent(userId));
        events.publishEvent(emails.passwordChanged(user.getEmail(), user.getDisplayName()));
        log.info("User {} changed password; other sessions revoked", userId);
        return jwtService.issue(user);
    }

    public void signOutEverywhere(Long userId) {
        User user = load(userId);
        user.revokeTokens();
        events.publishEvent(new SessionsRevokedEvent(userId));
        log.info("User {} signed out of all sessions", userId);
    }

    @Transactional(readOnly = true)
    /** @param viewerId the signed-in viewer, or null for visitors (blockedByMe is then null) */
    public PublicProfileDto publicProfile(Long userId, Long viewerId) {
        User user = load(userId);
        if (!user.isActive()) {
            throw ApiException.notFound("User");
        }
        return new PublicProfileDto(user.getId(), user.getDisplayName(), user.getBio(), user.getLocation(),
                StorageService.publicUrl(user.getAvatarKey()), user.getCreatedAt(),
                items.countByOwnerIdAndStatus(userId, ItemStatus.AVAILABLE),
                exchanges.countForUser(userId, ExchangeStatus.COMPLETED),
                donationRequests.countByOwnerIdAndStatus(userId, DonationRequestStatus.COMPLETED),
                viewerId == null || viewerId.equals(userId) ? null : blocks.hasBlocked(viewerId, userId));
    }

    @Transactional(readOnly = true)
    public UserSummaryCounts summary(Long userId) {
        return new UserSummaryCounts(
                notifications.countByRecipientIdAndReadAtIsNull(userId),
                messages.countUnreadForUser(userId),
                offers.countByRecipientIdAndStatus(userId, OfferStatus.PENDING),
                offers.countByOffererIdAndStatus(userId, OfferStatus.PENDING),
                exchanges.countForUser(userId, ExchangeStatus.ACTIVE),
                donationRequests.countByOwnerIdAndStatus(userId, DonationRequestStatus.PENDING),
                items.countByOwnerIdAndStatus(userId, ItemStatus.AVAILABLE));
    }

    private User load(Long userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }

    private void deleteAfterCommit(String key) {
        if (key == null) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.delete(key);
                } catch (RuntimeException e) {
                    log.warn("Could not delete old avatar {}: {}", key, e.getMessage());
                }
            }
        });
    }
}
