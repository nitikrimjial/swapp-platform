package com.swapp.item;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.swapp.common.ApiException;

/**
 * Single entry point for taking row locks on items. All state-changing workflows (offers, exchanges, donations,
 * listing edits) call this first, so locks are always acquired in the same order: items (by id), then the
 * offer/exchange/request row. A consistent order prevents deadlocks; the locks themselves prevent races such as two
 * offers for the same item being accepted at the same time.
 */
@Component
public class ItemLocker {

    private final ItemRepository items;

    public ItemLocker(ItemRepository items) {
        this.items = items;
    }

    /** Locks the items and fails with 404 if any of them does not exist or was removed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Item> lockActive(Long... ids) {
        Map<Long, Item> locked = lockAny(ids);
        for (Long id : ids) {
            Item item = locked.get(id);
            if (item == null || item.getStatus() == ItemStatus.REMOVED) {
                throw ApiException.notFound("Item");
            }
        }
        return locked;
    }

    /** Locks the items regardless of status (used for history-bearing workflows such as exchanges). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Item> lockAny(Long... ids) {
        List<Long> distinct = Arrays.stream(ids).filter(Objects::nonNull).distinct().sorted().toList();
        Map<Long, Item> result = new LinkedHashMap<>();
        for (Item item : items.lockAllByIds(distinct)) {
            result.put(item.getId(), item);
        }
        return result;
    }
}
