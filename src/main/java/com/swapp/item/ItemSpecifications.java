package com.swapp.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import jakarta.persistence.criteria.Predicate;

/** Builds type-safe, parameterised search predicates (no string concatenation, so no SQL injection). */
public final class ItemSpecifications {

    private ItemSpecifications() {
    }

    public static Specification<Item> matching(ItemSearchCriteria c) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), ItemStatus.AVAILABLE));

            if (StringUtils.hasText(c.q())) {
                String pattern = "%" + escapeLike(c.q().trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern, '\\'),
                        cb.like(cb.lower(root.get("description")), pattern, '\\')));
            }
            if (c.categoryId() != null) {
                predicates.add(cb.equal(root.get("category").get("id"), c.categoryId()));
            }
            if (c.condition() != null) {
                predicates.add(cb.equal(root.get("condition"), c.condition()));
            }
            if (c.listingTypes() != null && !c.listingTypes().isEmpty()) {
                predicates.add(root.get("listingType").in(c.listingTypes()));
            }
            if (StringUtils.hasText(c.location())) {
                String pattern = "%" + escapeLike(c.location().trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.like(cb.lower(root.get("location")), pattern, '\\'));
            }
            if (c.ownerId() != null) {
                predicates.add(cb.equal(root.get("owner").get("id"), c.ownerId()));
            }
            if (c.excludeOwnerId() != null) {
                predicates.add(cb.notEqual(root.get("owner").get("id"), c.excludeOwnerId()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    static String escapeLike(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
