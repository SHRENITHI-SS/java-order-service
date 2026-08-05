package com.acme.orders.domain;

import java.util.Set;

/**
 * The order lifecycle, with the legal transitions declared on the enum itself.
 *
 * <p>Keeping the transition table here rather than as {@code if} statements scattered through a
 * service means there is exactly one place to read to answer "can an order go from X to Y?", and a
 * new state cannot be added without deciding what it connects to.
 *
 * <pre>
 *   PENDING ──► CONFIRMED ──► PAID ──► SHIPPED ──► DELIVERED
 *      │            │           │
 *      └────────────┴───────────┴──────► CANCELLED
 * </pre>
 *
 * <p>Note what is deliberately absent: there is no path out of {@code DELIVERED} or
 * {@code CANCELLED}. A delivered order that comes back is a return — a separate process with its
 * own record — not a state change on the original order. Modelling returns as "un-delivering"
 * destroys the audit trail of what was actually sent.
 */
public enum OrderStatus {

    /** Created, stock reserved, awaiting confirmation. */
    PENDING,

    /** Customer has confirmed; awaiting payment. */
    CONFIRMED,

    /** Payment captured. */
    PAID,

    /** Handed to the carrier. Stock has left the building. */
    SHIPPED,

    /** Terminal. */
    DELIVERED,

    /** Terminal. Any reserved stock has been released. */
    CANCELLED;

    private static final Set<OrderStatus> CANCELLABLE = Set.of(PENDING, CONFIRMED, PAID);

    /** Whether this state may move to {@code target}. */
    public boolean canTransitionTo(OrderStatus target) {
        if (target == CANCELLED) {
            return CANCELLABLE.contains(this);
        }
        return switch (this) {
            case PENDING -> target == CONFIRMED;
            case CONFIRMED -> target == PAID;
            case PAID -> target == SHIPPED;
            case SHIPPED -> target == DELIVERED;
            case DELIVERED, CANCELLED -> false;
        };
    }

    /** True once the order can no longer change. */
    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED;
    }

    /**
     * True while the order still holds an inventory reservation.
     *
     * <p>Once shipped, the stock is physically gone: the reservation is consumed rather than held,
     * so cancelling a shipped order must not "return" units that have left the warehouse.
     */
    public boolean holdsReservation() {
        return this == PENDING || this == CONFIRMED || this == PAID;
    }
}
