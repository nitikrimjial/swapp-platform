package com.swapp.item.dto;

import java.time.Instant;
import java.util.List;

import com.swapp.category.CategoryDto;
import com.swapp.common.GeoPoint;
import com.swapp.donation.DonationRequestStatus;
import com.swapp.item.Item;
import com.swapp.item.ItemCondition;
import com.swapp.item.ItemStatus;
import com.swapp.item.ListingType;
import com.swapp.storage.StorageService;
import com.swapp.user.dto.UserDtos.UserSummaryDto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ItemDtos {

    private ItemDtos() {
    }

    public record ItemRequest(
            @NotBlank(message = "Title is required") @Size(min = 3, max = 120, message = "Title must be 3-120 characters")
            String title,
            @NotBlank(message = "Description is required")
            @Size(min = 10, max = 4000, message = "Description must be 10-4000 characters") String description,
            @NotNull(message = "Choose a category") Long categoryId,
            @NotNull(message = "Choose a condition") ItemCondition condition,
            @NotNull(message = "Choose a listing type") ListingType listingType,
            @NotBlank(message = "Location is required") @Size(max = 120, message = "Location must be at most 120 characters")
            String location,
            @DecimalMin(value = "-90", message = "Invalid latitude") @DecimalMax(value = "90", message = "Invalid latitude")
            Double latitude,
            @DecimalMin(value = "-180", message = "Invalid longitude") @DecimalMax(value = "180", message = "Invalid longitude")
            Double longitude) {
    }

    public record ItemStatusRequest(@NotNull(message = "Status is required") ItemStatus status) {
    }

    public record ImageOrderRequest(@NotEmpty(message = "Image order is required") List<Long> imageIds) {
    }

    public record ImageDto(Long id, String url) {
    }

    /** {@code distanceKm} is only set when the search had an origin point (rounded to 0.1 km). */
    public record ItemSummaryDto(Long id, String title, CategoryDto category, ItemCondition condition,
                                 ListingType listingType, ItemStatus status, String location, String coverImageUrl,
                                 UserSummaryDto owner, Instant createdAt, Double distanceKm) {
        public static ItemSummaryDto from(Item i) {
            return from(i, null);
        }

        public static ItemSummaryDto from(Item i, GeoPoint origin) {
            if (i == null) {
                return null;
            }
            Double distance = null;
            GeoPoint point = i.point();
            if (origin != null && point != null) {
                distance = Math.round(origin.distanceKmTo(point) * 10) / 10.0;
            }
            return new ItemSummaryDto(i.getId(), i.getTitle(), CategoryDto.from(i.getCategory()), i.getCondition(),
                    i.getListingType(), i.getStatus(), i.getLocation(), StorageService.publicUrl(i.coverImageKey()),
                    UserSummaryDto.from(i.getOwner()), i.getCreatedAt(), distance);
        }
    }

    /** What the current viewer can do with this item; null fields mean "not applicable". */
    public record ViewerState(boolean owner, boolean authenticated, Long pendingOfferId,
                              Long donationRequestId, DonationRequestStatus donationRequestStatus,
                              long pendingOffersReceived, long pendingDonationRequests) {
        public static ViewerState anonymous() {
            return new ViewerState(false, false, null, null, null, 0, 0);
        }
    }

    /** Coordinates are only returned to the owner (for editing); everyone else just sees {@code hasCoordinates}. */
    public record ItemDetailDto(Long id, String title, String description, CategoryDto category,
                                ItemCondition condition, ListingType listingType, ItemStatus status, String location,
                                boolean hasCoordinates, Double latitude, Double longitude,
                                List<ImageDto> images, UserSummaryDto owner, Instant createdAt, Instant updatedAt,
                                ViewerState viewer) {
        public static ItemDetailDto from(Item i, ViewerState viewer) {
            List<ImageDto> images = i.getImages().stream()
                    .map(img -> new ImageDto(img.getId(), StorageService.publicUrl(img.getStorageKey())))
                    .toList();
            boolean owner = viewer != null && viewer.owner();
            return new ItemDetailDto(i.getId(), i.getTitle(), i.getDescription(), CategoryDto.from(i.getCategory()),
                    i.getCondition(), i.getListingType(), i.getStatus(), i.getLocation(), i.point() != null,
                    owner ? i.getLatitude() : null, owner ? i.getLongitude() : null, images,
                    UserSummaryDto.from(i.getOwner()), i.getCreatedAt(), i.getUpdatedAt(), viewer);
        }
    }
}
