package com.swapp.item;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.swapp.category.Category;
import com.swapp.category.CategoryRepository;
import com.swapp.common.ApiException;
import com.swapp.common.GeoPoint;
import com.swapp.common.PageResponse;
import com.swapp.config.AppProperties;
import com.swapp.donation.DonationRequestRepository;
import com.swapp.donation.DonationRequestStatus;
import com.swapp.donation.DonationService;
import com.swapp.item.dto.ItemDtos.ItemDetailDto;
import com.swapp.item.dto.ItemDtos.ItemRequest;
import com.swapp.item.dto.ItemDtos.ItemSummaryDto;
import com.swapp.item.dto.ItemDtos.ViewerState;
import com.swapp.offer.OfferRepository;
import com.swapp.offer.OfferService;
import com.swapp.offer.OfferStatus;
import com.swapp.security.AuthUser;
import com.swapp.storage.ImageValidator;
import com.swapp.storage.StorageService;
import com.swapp.user.User;
import com.swapp.user.UserRepository;

@Service
@Transactional
public class ItemService {

    private static final Logger log = LoggerFactory.getLogger(ItemService.class);
    private static final Set<ItemStatus> EDITABLE = EnumSet.of(ItemStatus.AVAILABLE, ItemStatus.UNAVAILABLE);
    private static final Set<DonationRequestStatus> ACTIVE_DONATION =
            EnumSet.of(DonationRequestStatus.PENDING, DonationRequestStatus.ACCEPTED);

    private final ItemRepository items;
    private final CategoryRepository categories;
    private final UserRepository users;
    private final ItemLocker locker;
    private final OfferService offerService;
    private final OfferRepository offers;
    private final DonationService donationService;
    private final DonationRequestRepository donationRequests;
    private final StorageService storage;
    private final ImageValidator imageValidator;
    private final int maxImages;

    public ItemService(ItemRepository items, CategoryRepository categories, UserRepository users, ItemLocker locker,
                       OfferService offerService, OfferRepository offers, DonationService donationService,
                       DonationRequestRepository donationRequests, StorageService storage,
                       ImageValidator imageValidator, AppProperties props) {
        this.items = items;
        this.categories = categories;
        this.users = users;
        this.locker = locker;
        this.offerService = offerService;
        this.offers = offers;
        this.donationService = donationService;
        this.donationRequests = donationRequests;
        this.storage = storage;
        this.imageValidator = imageValidator;
        this.maxImages = props.storage().maxImagesPerItem();
    }

    @Transactional(readOnly = true)
    public PageResponse<ItemSummaryDto> search(ItemSearchCriteria criteria, int page, int size, String sort) {
        Sort order;
        if (criteria.nearestFirst()) {
            order = Sort.unsorted(); // distance ordering is applied inside the specification
        } else if ("oldest".equalsIgnoreCase(sort)) {
            order = Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by("id"));
        } else {
            order = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        }
        var pageable = PageResponse.request(page, size, order);
        GeoPoint origin = criteria.origin();
        return PageResponse.from(items.findAll(ItemSpecifications.matching(criteria), pageable),
                item -> ItemSummaryDto.from(item, origin));
    }

    @Transactional(readOnly = true)
    public ItemDetailDto get(Long id, AuthUser viewer) {
        Item item = items.findActiveById(id).orElseThrow(() -> ApiException.notFound("Item"));
        return ItemDetailDto.from(item, viewerState(item, viewer));
    }

    public ItemDetailDto create(Long userId, ItemRequest request) {
        User owner = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        Item item = new Item();
        item.setOwner(owner);
        apply(item, request);
        item.setStatus(ItemStatus.AVAILABLE);
        items.save(item);
        log.info("Item {} created by user {}", item.getId(), userId);
        return ItemDetailDto.from(item, ownerState(item));
    }

    public ItemDetailDto update(Long id, ItemRequest request, AuthUser me) {
        Item item = lockOwned(id, me);
        if (!EDITABLE.contains(item.getStatus())) {
            throw ApiException.conflict("Listings that are reserved or completed cannot be edited");
        }
        ListingType previousType = item.getListingType();
        apply(item, request);
        if (previousType != item.getListingType()) {
            // Offers/requests made under the old listing type no longer make sense.
            closeOpenActivity(item);
        }
        return ItemDetailDto.from(item, ownerState(item));
    }

    public ItemDetailDto changeStatus(Long id, ItemStatus target, AuthUser me) {
        if (!EDITABLE.contains(target)) {
            throw ApiException.badRequest("A listing can only be marked as available or unavailable");
        }
        Item item = lockOwned(id, me);
        if (!EDITABLE.contains(item.getStatus())) {
            throw ApiException.conflict("Reserved or completed listings cannot change availability");
        }
        if (item.getStatus() != target) {
            item.setStatus(target);
            if (target == ItemStatus.UNAVAILABLE) {
                closeOpenActivity(item);
            }
            log.info("Item {} marked {} by user {}", id, target, me.id());
        }
        return ItemDetailDto.from(item, ownerState(item));
    }

    public void delete(Long id, AuthUser me) {
        Item item = lockOwned(id, me);
        if (item.getStatus() == ItemStatus.RESERVED) {
            throw ApiException.conflict("This item is part of an active exchange or hand-over. Cancel that first.");
        }
        closeOpenActivity(item);
        // Soft delete: completed exchanges and offers keep referencing the item for history.
        item.setStatus(ItemStatus.REMOVED);
        log.info("Item {} removed by user {}", id, me.id());
    }

    public ItemDetailDto addImages(Long id, List<MultipartFile> files, AuthUser me) {
        Item item = lockOwned(id, me);
        if (files == null || files.isEmpty()) {
            throw ApiException.badRequest("Please choose at least one image");
        }
        if (item.getImages().size() + files.size() > maxImages) {
            throw ApiException.badRequest("A listing can have at most " + maxImages + " images");
        }
        var validated = files.stream().map(imageValidator::validate).toList();
        int position = item.getImages().stream().mapToInt(ItemImage::getPosition).max().orElse(-1) + 1;
        List<String> storedKeys = new ArrayList<>();
        for (var image : validated) {
            String key = storage.store(image.data(), image.contentType());
            storedKeys.add(key);
            item.getImages().add(new ItemImage(item, key, image.contentType(), position++));
        }
        deleteFilesOnRollback(storedKeys);
        return ItemDetailDto.from(item, ownerState(item));
    }

    public ItemDetailDto removeImage(Long id, Long imageId, AuthUser me) {
        Item item = lockOwned(id, me);
        ItemImage image = item.getImages().stream().filter(i -> i.getId().equals(imageId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Image"));
        item.getImages().remove(image);
        String key = image.getStorageKey();
        deleteFileAfterCommit(key);
        renumber(item.getImages());
        return ItemDetailDto.from(item, ownerState(item));
    }

    public ItemDetailDto reorderImages(Long id, List<Long> imageIds, AuthUser me) {
        Item item = lockOwned(id, me);
        Map<Long, ItemImage> byId = new HashMap<>();
        item.getImages().forEach(i -> byId.put(i.getId(), i));
        if (imageIds.size() != byId.size() || !new HashSet<>(imageIds).equals(byId.keySet())) {
            throw ApiException.badRequest("Image order must list every image of the listing exactly once");
        }
        List<ItemImage> ordered = imageIds.stream().map(byId::get).toList();
        item.getImages().clear();
        item.getImages().addAll(ordered);
        renumber(item.getImages());
        return ItemDetailDto.from(item, ownerState(item));
    }

    @Transactional(readOnly = true)
    public PageResponse<ItemSummaryDto> myItems(Long userId, ItemStatus status, int page, int size) {
        Set<ItemStatus> statuses = status == null
                ? EnumSet.complementOf(EnumSet.of(ItemStatus.REMOVED))
                : EnumSet.of(status);
        if (statuses.contains(ItemStatus.REMOVED)) {
            throw ApiException.badRequest("Removed listings are not available");
        }
        var pageable = PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(items.findByOwnerIdAndStatusIn(userId, statuses, pageable), ItemSummaryDto::from);
    }

    /** The current user's items that can be offered in an exchange. */
    @Transactional(readOnly = true)
    public List<ItemSummaryDto> offerableItems(Long userId) {
        return items.findByOwnerIdAndStatusAndListingTypeOrderByCreatedAtDesc(userId, ItemStatus.AVAILABLE,
                ListingType.EXCHANGE).stream().map(ItemSummaryDto::from).toList();
    }

    private void apply(Item item, ItemRequest request) {
        Category category = categories.findById(request.categoryId())
                .orElseThrow(() -> ApiException.badRequest("Unknown category"));
        item.setTitle(request.title().trim());
        item.setDescription(request.description().trim());
        item.setCategory(category);
        item.setCondition(request.condition());
        item.setListingType(request.listingType());
        item.setLocation(request.location().trim());
        GeoPoint point = GeoPoint.ofNullable(request.latitude(), request.longitude());
        item.setPoint(point == null ? null : point.rounded());
    }

    private Item lockOwned(Long id, AuthUser me) {
        Item item = locker.lockActive(id).get(id);
        if (!item.isOwnedBy(me.id()) && !me.isAdmin()) {
            throw ApiException.forbidden("You can only modify your own listings");
        }
        return item;
    }

    private void closeOpenActivity(Item item) {
        offerService.closePendingOffersInvolving(List.of(item), null);
        donationService.closeActiveRequestsFor(List.of(item));
    }

    private ViewerState viewerState(Item item, AuthUser viewer) {
        if (viewer == null) {
            return ViewerState.anonymous();
        }
        if (item.isOwnedBy(viewer.id())) {
            return ownerState(item);
        }
        Long pendingOfferId = offers.findFirstByOffererIdAndRequestedItemIdAndStatus(viewer.id(), item.getId(),
                OfferStatus.PENDING).map(o -> o.getId()).orElse(null);
        var donation = donationRequests.findFirstByItemIdAndRequesterIdAndStatusIn(item.getId(), viewer.id(),
                ACTIVE_DONATION);
        return new ViewerState(false, true, pendingOfferId, donation.map(d -> d.getId()).orElse(null),
                donation.map(d -> d.getStatus()).orElse(null), 0, 0);
    }

    private ViewerState ownerState(Item item) {
        if (item.getId() == null) {
            return new ViewerState(true, true, null, null, null, 0, 0);
        }
        return new ViewerState(true, true, null, null, null,
                offers.countByRequestedItemIdAndStatus(item.getId(), OfferStatus.PENDING),
                donationRequests.countByItemIdAndStatus(item.getId(), DonationRequestStatus.PENDING));
    }

    private static void renumber(List<ItemImage> images) {
        for (int i = 0; i < images.size(); i++) {
            images.get(i).setPosition(i);
        }
    }

    /** Filesystem storage is not transactional: remove orphaned files if the database transaction rolls back. */
    private void deleteFilesOnRollback(List<String> keys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    keys.forEach(ItemService.this::safeDelete);
                }
            }
        });
    }

    private void deleteFileAfterCommit(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeDelete(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeDelete(key);
            }
        });
    }

    private void safeDelete(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete stored file {}: {}", key, e.getMessage());
        }
    }
}
