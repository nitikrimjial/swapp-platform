package com.swapp.item;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.swapp.common.ApiException;
import com.swapp.common.GeoPoint;
import com.swapp.common.PageResponse;
import com.swapp.item.dto.ItemDtos.ImageOrderRequest;
import com.swapp.item.dto.ItemDtos.ItemDetailDto;
import com.swapp.item.dto.ItemDtos.ItemRequest;
import com.swapp.item.dto.ItemDtos.ItemStatusRequest;
import com.swapp.item.dto.ItemDtos.ItemSummaryDto;
import com.swapp.security.AuthUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemService service;

    public ItemController(ItemService service) {
        this.service = service;
    }

    /**
     * Public discovery endpoint. Every filter is applied in the database query.
     * Distance search: pass {@code lat}, {@code lng} and {@code radiusKm}; {@code sort=distance} orders nearest first.
     */
    @GetMapping
    public PageResponse<ItemSummaryDto> search(@AuthenticationPrincipal AuthUser me,
                                               @RequestParam(required = false) @Size(max = 100) String q,
                                               @RequestParam(required = false) Long categoryId,
                                               @RequestParam(required = false) List<ItemCondition> condition,
                                               @RequestParam(required = false) List<ListingType> type,
                                               @RequestParam(required = false) @Size(max = 120) String location,
                                               @RequestParam(required = false) Long ownerId,
                                               @RequestParam(defaultValue = "false") boolean excludeMine,
                                               @RequestParam(required = false) @Min(1) @Max(365) Integer postedWithinDays,
                                               @RequestParam(defaultValue = "false") boolean withPhotos,
                                               @RequestParam(required = false) Double lat,
                                               @RequestParam(required = false) Double lng,
                                               @RequestParam(required = false) @DecimalMin("0.1") @DecimalMax("200") Double radiusKm,
                                               @RequestParam(defaultValue = "newest") String sort,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "12") int size) {
        Long excludeOwner = excludeMine && me != null ? me.id() : null;
        GeoPoint origin = GeoPoint.ofNullable(lat, lng);
        if (radiusKm != null && origin == null) {
            throw ApiException.badRequest("A radius needs a location (lat and lng)");
        }
        Instant postedAfter = postedWithinDays == null ? null : Instant.now().minus(postedWithinDays, ChronoUnit.DAYS);
        boolean nearestFirst = "distance".equalsIgnoreCase(sort) && origin != null;
        var criteria = new ItemSearchCriteria(q, categoryId, condition, type, location, ownerId, excludeOwner,
                postedAfter, withPhotos, origin, radiusKm, nearestFirst);
        return service.search(criteria, page, size, sort);
    }

    @GetMapping("/{id}")
    public ItemDetailDto get(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.get(id, me);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ItemDetailDto create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody ItemRequest request) {
        return service.create(me.id(), request);
    }

    @PutMapping("/{id}")
    public ItemDetailDto update(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                @Valid @RequestBody ItemRequest request) {
        return service.update(id, request, me);
    }

    @PatchMapping("/{id}/status")
    public ItemDetailDto changeStatus(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                      @Valid @RequestBody ItemStatusRequest request) {
        return service.changeStatus(id, request.status(), me);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        service.delete(id, me);
    }

    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ItemDetailDto uploadImages(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                      @RequestPart("files") List<MultipartFile> files) {
        return service.addImages(id, files, me);
    }

    @DeleteMapping("/{id}/images/{imageId}")
    public ItemDetailDto deleteImage(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                     @PathVariable Long imageId) {
        return service.removeImage(id, imageId, me);
    }

    @PutMapping("/{id}/images/order")
    public ItemDetailDto reorderImages(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                       @Valid @RequestBody ImageOrderRequest request) {
        return service.reorderImages(id, request.imageIds(), me);
    }
}
