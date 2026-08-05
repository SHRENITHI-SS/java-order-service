package com.acme.orders.domain;

import java.util.Objects;

/**
 * One line on an order: a SKU, a quantity, and the price that applied at the moment of ordering.
 *
 * <p>The unit price is copied onto the line rather than read from the catalogue at display time.
 * This is not redundancy — it is the point. A catalogue price change next week must not silently
 * rewrite what the customer agreed to pay today, and an invoice has to be reproducible from the
 * order alone.
 */
public record OrderLine(String sku, String description, int quantity, Money unitPrice) {

    public OrderLine {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1, got: " + quantity);
        }
        if (unitPrice.isNegative()) {
            throw new IllegalArgumentException("unitPrice must not be negative: " + unitPrice);
        }
        description = description == null ? "" : description;
    }

    /** Line total, exact — no intermediate rounding. */
    public Money lineTotal() {
        return unitPrice.times(quantity);
    }
}
