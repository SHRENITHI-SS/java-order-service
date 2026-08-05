package com.acme.orders.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An immutable amount of money in a single currency.
 *
 * <p>Money is a value object rather than a {@code BigDecimal} field for two reasons that matter in
 * an ordering system:
 *
 * <ol>
 *   <li><b>Currency is part of the value.</b> Adding GBP to USD is a bug, and this class makes it
 *       throw rather than silently produce a number that looks plausible on an invoice.</li>
 *   <li><b>Scale is fixed at construction.</b> All amounts are held at 2 decimal places with
 *       {@link RoundingMode#HALF_UP}, so {@code 0.1 + 0.2} behaves the way an accountant expects
 *       and equality is not defeated by trailing zeros ({@code 5.0} vs {@code 5.00}).</li>
 * </ol>
 *
 * <p>Rounding is applied once, at construction, rather than at each arithmetic step. Rounding on
 * every operation accumulates error across a long line-item list; rounding once keeps intermediate
 * multiplications exact.
 */
public final class Money implements Comparable<Money> {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final BigDecimal amount;
    private final String currency;

    private Money(BigDecimal amount, String currency) {
        this.amount = amount.setScale(SCALE, ROUNDING);
        this.currency = currency;
    }

    public static Money of(String amount, String currency) {
        return of(new BigDecimal(amount), currency);
    }

    public static Money of(double amount, String currency) {
        // BigDecimal.valueOf(double) goes via the canonical String form, avoiding the binary
        // representation surprises of the BigDecimal(double) constructor.
        return of(BigDecimal.valueOf(amount), currency);
    }

    public static Money of(BigDecimal amount, String currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO code, got: " + currency);
        }
        return new Money(amount, currency.toUpperCase());
    }

    public static Money zero(String currency) {
        return of(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money times(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must not be negative: " + quantity);
        }
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }

    /**
     * Applies a percentage, e.g. {@code percentage(10)} returns 10% of this amount.
     * Used for discounts and tax, where the result must be rounded to the currency's scale.
     */
    public Money percentage(BigDecimal percent) {
        return new Money(
                amount.multiply(percent).divide(BigDecimal.valueOf(100), SCALE + 4, ROUNDING),
                currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "cannot combine " + currency + " with " + other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        // compareTo, not equals: BigDecimal.equals() distinguishes 5.0 from 5.00. Scale is
        // normalised at construction, but comparing by value keeps that an implementation detail.
        return currency.equals(other.currency) && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
