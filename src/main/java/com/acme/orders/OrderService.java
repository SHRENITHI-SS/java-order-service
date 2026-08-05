package com.acme.orders;

import com.acme.orders.domain.Money;
import com.acme.orders.domain.Order;
import com.acme.orders.domain.OrderLine;
import com.acme.orders.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Application service: coordinates the {@link Order} aggregate with {@link Inventory}.
 *
 * <p>The service owns the things that span more than one object — stock reservation, idempotency,
 * id generation, volume discounts — and nothing else. Rules about a single order's lifecycle stay
 * on the aggregate, which is why this class has no {@code switch} on status.
 *
 * <p><b>Idempotency.</b> {@link #placeOrder} takes an idempotency key. Retrying a request that
 * already succeeded returns the original order instead of placing a second one and reserving the
 * stock twice. Clients retry — on a timeout they cannot tell whether the first attempt landed, so
 * the server has to be the one that makes retrying safe.
 *
 * <p>The {@link Clock} is injected so tests can assert on ordering and timestamps without sleeping.
 */
public class OrderService {

    /** Thrown when an order cannot be placed. Carries a machine-readable reason for the API layer. */
    public static class OrderRejectedException extends RuntimeException {
        private final String reason;

        public OrderRejectedException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** Volume discount tiers: units ordered to percent off. Applied automatically at placement. */
    private static final Map<Integer, BigDecimal> VOLUME_DISCOUNTS = new LinkedHashMap<>();

    static {
        VOLUME_DISCOUNTS.put(100, BigDecimal.valueOf(15));
        VOLUME_DISCOUNTS.put(50, BigDecimal.valueOf(10));
        VOLUME_DISCOUNTS.put(20, BigDecimal.valueOf(5));
    }

    private final Inventory inventory;
    private final Clock clock;
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyKeys = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);

    public OrderService(Inventory inventory, Clock clock) {
        this.inventory = inventory;
        this.clock = clock;
    }

    /**
     * Places an order: validates, reserves stock atomically, applies any volume discount.
     *
     * @param idempotencyKey caller-supplied; a repeat returns the original order untouched
     * @throws OrderRejectedException if the basket is invalid or stock is short
     */
    public Order placeOrder(String customerId, List<OrderLine> lines, String idempotencyKey) {
        if (customerId == null || customerId.isBlank()) {
            throw new OrderRejectedException("invalid_customer", "customerId is required");
        }
        if (lines == null || lines.isEmpty()) {
            throw new OrderRejectedException("empty_basket", "an order must contain at least one line");
        }

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String existing = idempotencyKeys.get(idempotencyKey);
            if (existing != null) {
                // Return the original rather than a fresh order: the caller is retrying, not reordering.
                return orders.get(existing);
            }
        }

        Map<String, Integer> request = quantitiesBySku(lines);

        // Reserve before constructing the order. An order that exists without its stock reservation
        // is a promise the warehouse cannot keep.
        Inventory.ReservationResult reservation = inventory.reserve(request);
        if (!reservation.success()) {
            Inventory.Shortage shortage = reservation.shortage();
            throw new OrderRejectedException("insufficient_stock",
                    "insufficient stock for " + shortage.sku()
                            + ": requested " + shortage.requested()
                            + ", available " + shortage.available());
        }

        Order order;
        try {
            order = new Order(nextId(), customerId, lines, clock.instant());
            applyVolumeDiscount(order);
        } catch (RuntimeException e) {
            // Construction validates the basket (mixed currencies, duplicate SKUs). If it throws,
            // the reservation we just took must not leak, or that stock becomes unsellable.
            inventory.release(request);
            throw new OrderRejectedException("invalid_basket", e.getMessage());
        }

        orders.put(order.id(), order);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyKeys.put(idempotencyKey, order.id());
        }
        return order;
    }

    public Optional<Order> find(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    /** All orders, newest first. */
    public List<Order> findAll() {
        return orders.values().stream()
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .collect(Collectors.toList());
    }

    public List<Order> findByCustomer(String customerId) {
        return findAll().stream()
                .filter(order -> order.customerId().equals(customerId))
                .collect(Collectors.toList());
    }

    public Order confirm(String orderId) {
        Order order = require(orderId);
        order.confirm(clock.instant());
        return order;
    }

    public Order pay(String orderId, String paymentReference) {
        Order order = require(orderId);
        order.markPaid(paymentReference, clock.instant());
        return order;
    }

    /**
     * Ships the order and consumes its reservation — the goods have physically left, so they come
     * off both the reserved count and the on-hand count.
     */
    public Order ship(String orderId, String trackingNumber) {
        Order order = require(orderId);
        order.ship(trackingNumber, clock.instant());
        inventory.consume(quantitiesBySku(order.lines()));
        return order;
    }

    public Order markDelivered(String orderId) {
        Order order = require(orderId);
        order.markDelivered(clock.instant());
        return order;
    }

    /**
     * Cancels the order, releasing stock only if the order still held a reservation.
     *
     * <p>The {@code holdsReservation()} check is the important part: a shipped order's stock is
     * already gone, so releasing it would invent inventory that does not exist.
     */
    public Order cancel(String orderId, String reason) {
        Order order = require(orderId);
        boolean wasHoldingStock = order.status().holdsReservation();
        order.cancel(reason, clock.instant());
        if (wasHoldingStock) {
            inventory.release(quantitiesBySku(order.lines()));
        }
        return order;
    }

    /** Counts by status, for the dashboard endpoint. */
    public Map<String, Object> summary() {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (OrderStatus status : OrderStatus.values()) {
            byStatus.put(status.name(), 0);
        }
        Money revenue = null;
        for (Order order : orders.values()) {
            byStatus.merge(order.status().name(), 1, Integer::sum);
            // Revenue counts orders that have been paid for, not orders that merely exist.
            if (order.status() == OrderStatus.PAID
                    || order.status() == OrderStatus.SHIPPED
                    || order.status() == OrderStatus.DELIVERED) {
                revenue = revenue == null ? order.total() : revenue.plus(order.total());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalOrders", orders.size());
        out.put("byStatus", byStatus);
        out.put("recognisedRevenue", revenue == null ? "0.00" : revenue.amount().toPlainString());
        out.put("currency", revenue == null ? null : revenue.currency());
        return out;
    }

    // -- internals -----------------------------------------------------------

    private Order require(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            throw new OrderRejectedException("not_found", "no such order: " + orderId);
        }
        return order;
    }

    /**
     * Applies the best matching volume discount. Tiers are checked highest-first so a 120-unit
     * order gets 15%, not the 5% of the first tier it happens to clear.
     */
    private void applyVolumeDiscount(Order order) {
        int units = order.totalUnits();
        for (Map.Entry<Integer, BigDecimal> tier : VOLUME_DISCOUNTS.entrySet()) {
            if (units >= tier.getKey()) {
                order.applyDiscount(tier.getValue(),
                        "volume discount for " + units + " units", clock.instant());
                return;
            }
        }
    }

    private static Map<String, Integer> quantitiesBySku(List<OrderLine> lines) {
        Map<String, Integer> request = new HashMap<>();
        for (OrderLine line : lines) {
            request.merge(line.sku(), line.quantity(), Integer::sum);
        }
        return request;
    }

    private String nextId() {
        return "ORD-" + sequence.incrementAndGet();
    }
}
