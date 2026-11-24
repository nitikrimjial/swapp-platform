package com.swapp.moderation;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.swapp.common.ApiException;
import com.swapp.common.PageResponse;
import com.swapp.donation.DonationRequestRepository;
import com.swapp.donation.DonationRequestStatus;
import com.swapp.exchange.ExchangeRepository;
import com.swapp.exchange.ExchangeStatus;
import com.swapp.item.Item;
import com.swapp.item.ItemRepository;
import com.swapp.item.ItemStatus;
import com.swapp.moderation.audit.AuditService;
import com.swapp.moderation.dto.AdminDtos.AdminItemRow;
import com.swapp.moderation.dto.AdminDtos.AdminUserDetail;
import com.swapp.moderation.dto.AdminDtos.AdminUserRow;
import com.swapp.moderation.dto.AdminDtos.DailyPoint;
import com.swapp.moderation.dto.AdminDtos.MemberActivity;
import com.swapp.moderation.dto.AdminDtos.MemberFilter;
import com.swapp.moderation.dto.AdminDtos.Overview;
import com.swapp.moderation.dto.AdminDtos.OverviewTotals;
import com.swapp.storage.StorageService;
import com.swapp.user.Role;
import com.swapp.user.User;
import com.swapp.user.UserRepository;
import com.swapp.user.identity.UserIdentityRepository;

import jakarta.persistence.criteria.Predicate;

/** Read side of the admin panel: the dashboard numbers, member and listing search, and a member's record. */
@Service
@Transactional(readOnly = true)
public class AdminDirectoryService {

    static final int MIN_DAYS = 7;
    static final int MAX_DAYS = 90;
    private static final int RECENT = 10;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final UserRepository users;
    private final ItemRepository items;
    private final ReportRepository reports;
    private final ExchangeRepository exchanges;
    private final DonationRequestRepository donations;
    private final UserIdentityRepository identities;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public AdminDirectoryService(UserRepository users, ItemRepository items, ReportRepository reports,
                                 ExchangeRepository exchanges, DonationRequestRepository donations,
                                 UserIdentityRepository identities, AuditService audit, JdbcTemplate jdbc) {
        this.users = users;
        this.items = items;
        this.reports = reports;
        this.exchanges = exchanges;
        this.donations = donations;
        this.identities = identities;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- dashboard

    /**
     * Totals plus one point per calendar day for the last {@code days} days, today included. Days are cut in the
     * viewer's time zone, so "today" on the chart is the admin's today rather than UTC's.
     */
    public Overview overview(int days, String timeZone) {
        int span = Math.min(Math.max(days, MIN_DAYS), MAX_DAYS);
        ZoneId zone = zone(timeZone);
        LocalDate first = LocalDate.now(zone).minusDays(span - 1L);
        // The app, not the database, decides where each day starts: it is correct across daylight-saving changes and
        // does not depend on the time-zone names a particular PostgreSQL installation knows.
        Timestamp[] dayStarts = new Timestamp[span];
        for (int i = 0; i < span; i++) {
            dayStarts[i] = Timestamp.from(first.plusDays(i).atStartOfDay(zone).toInstant());
        }

        long[] signUps = perDay("users", "created_at", null, dayStarts);
        long[] listings = perDay("items", "created_at", null, dayStarts);
        long[] swaps = perDay("exchanges", "completed_at", "status = 'COMPLETED'", dayStarts);
        // A giveaway has no completion timestamp of its own; completing it is the row's last update.
        long[] giveaways = perDay("donation_requests", "updated_at", "status = 'COMPLETED'", dayStarts);
        long[] filed = perDay("reports", "created_at", null, dayStarts);

        List<DailyPoint> points = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            points.add(new DailyPoint(first.plusDays(i), signUps[i], listings[i], swaps[i] + giveaways[i], filed[i]));
        }
        OverviewTotals totals = new OverviewTotals(users.countByDeletedAtIsNull(),
                items.countByStatus(ItemStatus.AVAILABLE),
                exchanges.countByStatus(ExchangeStatus.COMPLETED)
                        + donations.countByStatus(DonationRequestStatus.COMPLETED),
                reports.countByStatus(ReportStatus.OPEN), users.countBySuspendedAtIsNotNull());
        return new Overview(totals, zone.getId(), points);
    }

    /**
     * Rows per day: {@code width_bucket} places each timestamp in the day whose start it follows. Table, column and
     * condition are constants from this class, never request input.
     */
    private long[] perDay(String table, String column, String condition, Timestamp[] dayStarts) {
        String sql = "select width_bucket(" + column + ", ?), count(*) from " + table + " where " + column + " >= ?"
                + (condition == null ? "" : " and " + condition) + " group by 1";
        long[] counts = new long[dayStarts.length];
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setArray(1, con.createArrayOf("timestamptz", dayStarts));
            ps.setTimestamp(2, dayStarts[0]);
            return ps;
        }, rs -> {
            counts[rs.getInt(1) - 1] = rs.getLong(2);
        });
        return counts;
    }

    private static ZoneId zone(String timeZone) {
        if (!StringUtils.hasText(timeZone)) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timeZone.trim());
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    // ---------------------------------------------------------------- members

    /** Members whose name or email contains {@code q}. Erased accounts have nothing to search and are left out. */
    public PageResponse<AdminUserRow> searchMembers(String q, MemberFilter filter, int page, int size) {
        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.isNull(root.get("deletedAt")));
            if (StringUtils.hasText(q)) {
                String pattern = contains(q);
                where.add(cb.or(cb.like(cb.lower(root.get("displayName")), pattern, '\\'),
                        cb.like(cb.lower(root.get("email")), pattern, '\\')));
            }
            switch (filter == null ? MemberFilter.ALL : filter) {
                case SUSPENDED -> where.add(cb.isNotNull(root.get("suspendedAt")));
                case STAFF -> where.add(cb.notEqual(root.get("role"), Role.USER));
                case UNVERIFIED -> where.add(cb.isNull(root.get("emailVerifiedAt")));
                default -> {
                    // ALL: no extra condition
                }
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return PageResponse.from(users.findAll(spec, PageResponse.request(page, size, NEWEST_FIRST)),
                AdminUserRow::from);
    }

    public AdminUserDetail member(Long id) {
        User u = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        List<String> methods = new ArrayList<>();
        if (u.hasPassword()) {
            methods.add("PASSWORD");
        }
        identities.findByUserIdOrderByCreatedAtAsc(id).forEach(i -> methods.add(i.getProvider().name()));
        MemberActivity activity = new MemberActivity(items.countByOwnerIdAndStatusNot(id, ItemStatus.REMOVED),
                exchanges.countForUser(id, ExchangeStatus.COMPLETED),
                reports.countByReportedUserIdAndStatus(id, ReportStatus.OPEN), reports.countByReportedUserId(id),
                reports.countByReporterId(id));
        List<AdminItemRow> listings = searchListings(null, null, id, 0, RECENT).content();
        return new AdminUserDetail(u.getId(), u.getDisplayName(), StorageService.publicUrl(u.getAvatarKey()),
                u.isDeleted() ? null : u.getEmail(), u.getRole(), u.getBio(), u.getLocation(), u.getCreatedAt(),
                u.isEmailVerified(), methods, u.getSuspendedAt(), u.getSuspensionReason(), u.isDeleted(), activity,
                listings, audit.forMember(id, 0, RECENT).content());
    }

    // ---------------------------------------------------------------- listings

    /** Every listing, removed ones included, newest first; optionally one member's or one status. */
    public PageResponse<AdminItemRow> searchListings(String q, ItemStatus status, Long ownerId, int page, int size) {
        Specification<Item> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (StringUtils.hasText(q)) {
                where.add(cb.like(cb.lower(root.get("title")), contains(q), '\\'));
            }
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (ownerId != null) {
                where.add(cb.equal(root.get("owner").get("id"), ownerId));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return PageResponse.from(items.findAll(spec, PageResponse.request(page, size, NEWEST_FIRST)),
                AdminItemRow::from);
    }

    /** A "contains" LIKE pattern with the wildcard characters in the search text escaped. */
    private static String contains(String text) {
        String escaped = text.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
