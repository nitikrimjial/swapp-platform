package com.swapp.item.dto;

import java.time.Instant;
import java.util.List;

import com.swapp.category.CategoryDto;
import com.swapp.donation.DonationRequestStatus;
import com.swapp.item.Item;
import com.swapp.item.ItemCondition;
import com.swapp.item.ItemStatus;
import com.swapp.item.ListingType;
import com.swapp.storage.StorageService;
import com.swapp.user.dto.UserDtos.UserSummaryDto;

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
            String location) {
    }

    public record ItemStatusRequest(@NotNull(message = "Status is required") ItemStatus status) {
    }

    public record ImageOrderRequest(@NotEmpty(message = "Image order is required") List<Long> imageIds) {
    }

    public record ImageDto(Long id, String url) {
    }

    public record ItemSummaryDto(Long id, String title, CategoryDto category, ItemCondition condition,
                                 ListingType listingType, ItemStatus status, String location, String coverImageUrl,
                                 UserSummaryDto owner, Instant createdAt) {
        public static ItemSummaryDto from(Item i) {
            if (i == null) {
                return null;
            }
            return new ItemSummaryDto(i.getId(), i.getTitle(), CategoryDto.from(i.getCategory()), i.getCondition(),
                    i.getListingType(), i.getStatus(), i.getLocation(), StorageService.publicUrl(i.coverImageKey()),
                    UserSummaryDto.from(i.getOwner()), i.getCreatedAt());
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

    public record ItemDetailDto(Long id, String title, String description, CategoryDto category,
                                ItemCondition condition, ListingType listingType, ItemStatus status, String location,
                                List<ImageDto> images, UserSummaryDto owner, Instant createdAt, Instant updatedAt,
                                ViewerState viewer) {
        public static ItemDetailDto from(Item i, ViewerState viewer) {
            List<ImageDto> images = i.getImages().stream()
                    .map(img -> new ImageDto(img.getId(), StorageService.publicUrl(img.getStorageKey())))
                    .toList();
            return new ItemDetailDto(i.getId(), i.getTitle(), i.getDescription(), CategoryDto.from(i.getCategory()),
                    i.getCondition(), i.getListingType(), i.getStatus(), i.getLocation(), images,
                    UserSummaryDto.from(i.getOwner()), i.getCreatedAt(), i.getUpdatedAt(), viewer);
        }
    }
}
