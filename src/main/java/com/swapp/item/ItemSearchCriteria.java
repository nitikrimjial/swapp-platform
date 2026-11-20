package com.swapp.item;

import java.util.List;

public record ItemSearchCriteria(String q, Long categoryId, ItemCondition condition, List<ListingType> listingTypes,
                                 String location, Long ownerId, Long excludeOwnerId) {
}
