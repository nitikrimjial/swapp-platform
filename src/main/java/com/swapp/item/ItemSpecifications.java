package com.swapp.item;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import com.swapp.common.GeoPoint;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

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
            if (c.conditions() != null && !c.conditions().isEmpty()) {
                predicates.add(root.get("condition").in(c.conditions()));
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
            if (c.postedAfter() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), c.postedAfter()));
            }
            if (c.withPhotosOnly()) {
                predicates.add(cb.isNotEmpty(root.<List<ItemImage>>get("images")));
            }

            GeoPoint origin = c.origin();
            if (origin != null && c.radiusKm() != null) {
                // 1) Cheap, index-friendly bounding box around the circle...
                double dLat = GeoPoint.latitudeDelta(c.radiusKm());
                double dLon = origin.longitudeDelta(c.radiusKm());
                predicates.add(cb.between(root.<Double>get("latitude"), origin.latitude() - dLat,
                        origin.latitude() + dLat));
                predicates.add(cb.between(root.<Double>get("longitude"), origin.longitude() - dLon,
                        origin.longitude() + dLon));
                // 2) ...then the exact great-circle distance for rows inside the box.
                predicates.add(cb.le(distanceKm(cb, root, origin), c.radiusKm()));
            }

            // Order by distance on the data query only (never on the count query).
            boolean isCountQuery = query.getResultType() == Long.class || query.getResultType() == long.class;
            if (c.nearestFirst() && origin != null && !isCountQuery) {
                predicates.add(cb.isNotNull(root.get("latitude")));
                query.orderBy(cb.asc(distanceKm(cb, root, origin)), cb.desc(root.get("createdAt")));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Spherical law of cosines in SQL: R * acos(cos φ1 cos φ2 cos(λ2 − λ1) + sin φ1 sin φ2), with the argument
     * clamped to 1 to avoid NaN from floating-point rounding when the points coincide.
     */
    static Expression<Double> distanceKm(CriteriaBuilder cb, Root<Item> root, GeoPoint origin) {
        double lat0 = Math.toRadians(origin.latitude());
        double lon0 = Math.toRadians(origin.longitude());
        Expression<Double> lat = cb.function("radians", Double.class, root.get("latitude"));
        Expression<Double> lon = cb.function("radians", Double.class, root.get("longitude"));
        Expression<Double> cosTerm = cb.prod(cb.prod(cb.literal(Math.cos(lat0)), cos(cb, lat)),
                cos(cb, cb.diff(lon, cb.literal(lon0))));
        Expression<Double> sinTerm = cb.prod(cb.literal(Math.sin(lat0)), cb.function("sin", Double.class, lat));
        Expression<Double> clamped = cb.function("least", Double.class, cb.literal(1.0), cb.sum(cosTerm, sinTerm));
        return cb.prod(cb.literal(GeoPoint.EARTH_RADIUS_KM), cb.function("acos", Double.class, clamped));
    }

    private static Expression<Double> cos(CriteriaBuilder cb, Expression<Double> x) {
        return cb.function("cos", Double.class, x);
    }

    static String escapeLike(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
