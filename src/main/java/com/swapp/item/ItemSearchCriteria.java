package com.swapp.item;

import java.time.Instant;
import java.util.List;

import com.swapp.common.GeoPoint;

/**
 * Discovery filters. All are optional; {@code origin} + {@code radiusKm} restrict results to listings within that
 * distance, and {@code nearestFirst} orders by distance from {@code origin}.
 */
public record ItemSearchCriteria(String q, Long categoryId, List<ItemCondition> conditions,
                                 List<ListingType> listingTypes, String location, Long ownerId, Long excludeOwnerId,
                                 Instant postedAfter, boolean withPhotosOnly, GeoPoint origin, Double radiusKm,
                                 boolean nearestFirst) {
}
