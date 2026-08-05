package com.acme.orders.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The order aggregate: the single place where an order's rules live.
 *
 * <p>Two design decisions are load-bearing:
 *
 * <ol>
 *   <li><b>Illegal states are unreachable, not merely unwritten.</b> Every mutation goes through a
 *       method that checks the transition table and throws {@link IllegalOrderStateException} on
 *       violation. There are no setters, so no caller can put an order into a state the domain
 *       does not recognise — including a future caller who has not read this class.</li>
 *   <li><b>Every change appends to an event log.</b> {@code history()} is the answer to "what
 *       happened to this order and when?", which is the first question support asks. It is
 *       maintained by the aggregate itself so it cannot drift from the actual state.</li>
 * </ol>
 *
 * <p>Totals are computed on demand rather than stored. A stored total is a second source of truth
 * that can disagree with the lines, and reconciling the two is a support problem nobody enjoys.
 */
public class Order {

    /** Thrown when a caller attempts a transition the lifecycle does not allow. */
    public static class IllegalOrderStateException extends RuntimeException {
        public IllegalOrderStateException(String message) {
            super(message);
        }
    }

    /** An append-only record of something that happened to the order. */
    public record Event(Instant at, String type, String detail) {}

    private final String id;
    private final String customerId;
    private final String currency;
    private final List<OrderLine> lines;
    private final List<Event> history = new ArrayList<>();
    private final Instant createdAt;

    private OrderStatus status = OrderStatus.PENDING;
    private java.math.BigDecimal discountPercent = java.math.BigDecimal.ZERO;
    private String cancellationReason;
    private String paymentReference;
    private String trackingNumber;

    public Order(String id, String customerId, List<OrderLine> lines, Instant createdAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(createdAt, "createdAt");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("an order must have at least one line");
        }

        // Mixed currencies on one order cannot be totalled, so reject them at construction rather
        // than failing later inside subtotal() where the cause is much less obvious.
        String first = lines.get(0).unitPrice().currency();
        for (OrderLine line : lines) {
            if (!line.unitPrice().currency().equals(first)) {
                throw new IllegalArgumentException(
                        "all lines must share a currency; found " + first
                                + " and " + line.unitPrice().currency());
            }
        }

        // Duplicate SKUs would let the same item be reserved twice and shown twice on the invoice.
        long distinctSkus = lines.stream().map(OrderLine::sku).distinct().count();
        if (distinctSkus != lines.size()) {
            throw new IllegalArgumentException("duplicate SKUs on one order; merge the quantities instead");
        }

        this.id = id;
        this.customerId = customerId;
        this.currency = first;
        this.lines = List.copyOf(lines);
        this.createdAt = createdAt;
        record(createdAt, "CREATED", lines.size() + " line(s), subtotal " + subtotal());
    }

    // -- pricing -------------------------------------------------------------

    /** Sum of the line totals, before discount. */
    public Money subtotal() {
        Money total = Money.zero(currency);
        for (OrderLine line : lines) {
            total = total.plus(line.lineTotal());
        }
        return total;
    }

    public Money discount() {
        return subtotal().percentage(discountPercent);
    }

    /** What the customer pays. */
    public Money total() {
        return subtotal().minus(discount());
    }

    /**
     * Applies a percentage discount.
     *
     * <p>Only allowed before payment: re-pricing an order that has already been charged creates a
     * mismatch between the order and the payment record, which is a refund, not a discount.
     */
    public void applyDiscount(java.math.BigDecimal percent, String reason, Instant at) {
        Objects.requireNonNull(percent, "percent");
        if (percent.signum() < 0 || percent.compareTo(java.math.BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("discount must be between 0 and 100, got: " + percent);
        }
        if (status != OrderStatus.PENDING && status != OrderStatus.CONFIRMED) {
            throw new IllegalOrderStateException(
                    "cannot change pricing once the order is " + status + "; issue a refund instead");
        }
        this.discountPercent = percent;
        record(at, "DISCOUNT_APPLIED", percent.toPlainString() + "% (" + reason + "), total now " + total());
    }

    // -- lifecycle -----------------------------------------------------------

    public void confirm(Instant at) {
        transition(OrderStatus.CONFIRMED, at, "confirmed by customer");
    }

    public void markPaid(String paymentReference, Instant at) {
        if (paymentReference == null || paymentReference.isBlank()) {
            throw new IllegalArgumentException("a payment reference is required");
        }
        transition(OrderStatus.PAID, at, "payment " + paymentReference);
        this.paymentReference = paymentReference;
    }

    public void ship(String trackingNumber, Instant at) {
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw new IllegalArgumentException("a tracking number is required to ship");
        }
        transition(OrderStatus.SHIPPED, at, "tracking " + trackingNumber);
        this.trackingNumber = trackingNumber;
    }

    public void markDelivered(Instant at) {
        transition(OrderStatus.DELIVERED, at, "delivery confirmed");
    }

    public void cancel(String reason, Instant at) {
        if (reason == null || reason.isBlank()) {
            // An unexplained cancellation is useless to whoever investigates the refund later.
            throw new IllegalArgumentException("a cancellation reason is required");
        }
        transition(OrderStatus.CANCELLED, at, reason);
        this.cancellationReason = reason;
    }

    private void transition(OrderStatus target, Instant at, String detail) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalOrderStateException(
                    "cannot move order " + id + " from " + status + " to " + target);
        }
        OrderStatus from = status;
        status = target;
        record(at, target.name(), from + " -> " + target + ": " + detail);
    }

    private void record(Instant at, String type, String detail) {
        history.add(new Event(at, type, detail));
    }

    // -- accessors -----------------------------------------------------------

    public String id() {
        return id;
    }

    public String customerId() {
        return customerId;
    }

    public String currency() {
        return currency;
    }

    public OrderStatus status() {
        return status;
    }

    public List<OrderLine> lines() {
        return lines;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public java.math.BigDecimal discountPercent() {
        return discountPercent;
    }

    public String cancellationReason() {
        return cancellationReason;
    }

    public String paymentReference() {
        return paymentReference;
    }

    public String trackingNumber() {
        return trackingNumber;
    }

    /** Total units across all lines — what the warehouse actually has to pick. */
    public int totalUnits() {
        return lines.stream().mapToInt(OrderLine::quantity).sum();
    }

    public List<Event> history() {
        return Collections.unmodifiableList(history);
    }
}
