package com.acme.orders;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stock levels with reservations.
 *
 * <p>The distinction that matters: <b>on hand</b> is what is physically in the warehouse,
 * <b>reserved</b> is what is already promised to open orders, and <b>available</b> is the
 * difference. Selling against "on hand" is how two customers buy the last unit.
 *
 * <p>{@link #reserve} is all-or-nothing across the whole basket. A partial reservation would leave
 * an order half-fulfillable and the caller with no clean way to unwind it — so the method either
 * reserves every line or reserves nothing and says which SKU was short.
 *
 * <p>Thread safety: mutations synchronise on the instance. A per-SKU lock would allow more
 * concurrency, but it cannot give an atomic multi-SKU basket reservation, which is the property
 * that actually prevents overselling.
 */
public class Inventory {

    /** Why a reservation failed, with enough detail for the caller to tell the customer. */
    public record Shortage(String sku, int requested, int available) {
        @Override
        public String toString() {
            return sku + ": requested " + requested + ", available " + available;
        }
    }

    /** The outcome of a reservation attempt. */
    public record ReservationResult(boolean success, Shortage shortage) {
        public static ReservationResult ok() {
            return new ReservationResult(true, null);
        }

        public static ReservationResult shortOf(String sku, int requested, int available) {
            return new ReservationResult(false, new Shortage(sku, requested, available));
        }
    }

    private final Map<String, Integer> onHand = new ConcurrentHashMap<>();
    private final Map<String, Integer> reserved = new ConcurrentHashMap<>();

    /** Sets the physical stock level for a SKU. */
    public synchronized void stock(String sku, int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("stock cannot be negative: " + quantity);
        }
        onHand.put(sku, quantity);
        reserved.putIfAbsent(sku, 0);
    }

    public int onHand(String sku) {
        return onHand.getOrDefault(sku, 0);
    }

    public int reserved(String sku) {
        return reserved.getOrDefault(sku, 0);
    }

    /** What can still be promised to a new order. */
    public int available(String sku) {
        return onHand(sku) - reserved(sku);
    }

    /**
     * Reserves a whole basket atomically.
     *
     * @param request SKU to quantity
     * @return success, or the first shortage encountered — nothing is reserved on failure
     */
    public synchronized ReservationResult reserve(Map<String, Integer> request) {
        // Check everything before changing anything, so a failure needs no rollback.
        for (Map.Entry<String, Integer> entry : request.entrySet()) {
            int wanted = entry.getValue();
            int free = available(entry.getKey());
            if (wanted > free) {
                return ReservationResult.shortOf(entry.getKey(), wanted, free);
            }
        }
        request.forEach((sku, quantity) -> reserved.merge(sku, quantity, Integer::sum));
        return ReservationResult.ok();
    }

    /**
     * Releases a reservation — an order was cancelled, so the stock is promisable again.
     *
     * <p>Clamped at zero: releasing more than is reserved would create phantom availability, so a
     * double-release is absorbed rather than allowed to corrupt the count.
     */
    public synchronized void release(Map<String, Integer> request) {
        request.forEach((sku, quantity) ->
                reserved.merge(sku, quantity, (current, released) -> Math.max(0, current - released)));
    }

    /**
     * Consumes a reservation — the goods have shipped, so they leave both the reservation and the
     * physical count. Without this, shipped stock would stay "reserved" forever and availability
     * would drift downwards until someone restocked manually.
     */
    public synchronized void consume(Map<String, Integer> request) {
        request.forEach((sku, quantity) -> {
            reserved.merge(sku, quantity, (current, used) -> Math.max(0, current - used));
            onHand.merge(sku, quantity, (current, used) -> Math.max(0, current - used));
        });
    }

    /** A snapshot for the API, ordered by SKU so responses are stable. */
    public synchronized Map<String, Map<String, Integer>> snapshot() {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        onHand.keySet().stream().sorted().forEach(sku -> {
            Map<String, Integer> detail = new LinkedHashMap<>();
            detail.put("onHand", onHand(sku));
            detail.put("reserved", reserved(sku));
            detail.put("available", available(sku));
            out.put(sku, detail);
        });
        return Collections.unmodifiableMap(out);
    }
}
