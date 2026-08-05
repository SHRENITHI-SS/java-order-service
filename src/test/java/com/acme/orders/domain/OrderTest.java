package com.acme.orders.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Domain tests: money arithmetic, order invariants and the lifecycle transition table. */
class OrderTest {

    private static final Instant T0 = Instant.parse("2026-03-15T09:00:00Z");

    private static OrderLine line(String sku, int quantity, String price) {
        return new OrderLine(sku, sku + " description", quantity, Money.of(price, "USD"));
    }

    private static Order order(OrderLine... lines) {
        return new Order("ORD-1", "CUST-1", List.of(lines), T0);
    }

    @Nested
    @DisplayName("Money")
    class MoneyTest {

        @Test
        void addsAndSubtractsAtTwoDecimalPlaces() {
            assertEquals(Money.of("3.00", "USD"), Money.of("1.00", "USD").plus(Money.of("2.00", "USD")));
            assertEquals(Money.of("0.30", "USD"), Money.of("0.10", "USD").plus(Money.of("0.20", "USD")));
        }

        @Test
        @DisplayName("refuses to combine different currencies rather than producing a plausible wrong number")
        void refusesMixedCurrencies() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Money.of("1.00", "USD").plus(Money.of("1.00", "GBP")));
            assertTrue(e.getMessage().contains("USD"));
            assertTrue(e.getMessage().contains("GBP"));
        }

        @Test
        void treatsScaleAsAnImplementationDetailForEquality() {
            assertEquals(Money.of("5.0", "USD"), Money.of("5.00", "USD"));
            assertEquals(Money.of("5.0", "USD").hashCode(), Money.of("5.00", "USD").hashCode());
        }

        @Test
        void multipliesWithoutIntermediateRounding() {
            // 3 x 19.99 = 59.97 exactly, not 60.00
            assertEquals(Money.of("59.97", "USD"), Money.of("19.99", "USD").times(3));
        }

        @Test
        void computesPercentagesWithHalfUpRounding() {
            // 10% of 19.99 = 1.999 -> 2.00
            assertEquals(Money.of("2.00", "USD"), Money.of("19.99", "USD").percentage(BigDecimal.TEN));
        }

        @Test
        void rejectsNegativeQuantityAndBadCurrencyCodes() {
            assertThrows(IllegalArgumentException.class, () -> Money.of("1.00", "USD").times(-1));
            assertThrows(IllegalArgumentException.class, () -> Money.of("1.00", "DOLLARS"));
        }

        @Test
        void comparesAndReportsSign() {
            assertTrue(Money.of("2.00", "USD").isGreaterThan(Money.of("1.00", "USD")));
            assertTrue(Money.zero("USD").isZero());
            assertTrue(Money.of("1.00", "USD").minus(Money.of("2.00", "USD")).isNegative());
        }
    }

    @Nested
    @DisplayName("OrderLine")
    class OrderLineTest {

        @Test
        void rejectsInvalidLines() {
            assertThrows(IllegalArgumentException.class, () -> line("", 1, "1.00"));
            assertThrows(IllegalArgumentException.class, () -> line("SKU-1", 0, "1.00"));
            assertThrows(IllegalArgumentException.class, () -> line("SKU-1", -3, "1.00"));
        }

        @Test
        void computesLineTotal() {
            assertEquals(Money.of("29.97", "USD"), line("SKU-1", 3, "9.99").lineTotal());
        }

        @Test
        void toleratesAMissingDescription() {
            assertEquals("", new OrderLine("SKU-1", null, 1, Money.of("1.00", "USD")).description());
        }
    }

    @Nested
    @DisplayName("construction invariants")
    class Construction {

        @Test
        void requiresAtLeastOneLine() {
            assertThrows(IllegalArgumentException.class, () -> new Order("ORD-1", "CUST-1", List.of(), T0));
        }

        @Test
        @DisplayName("rejects mixed currencies at construction, not later inside subtotal()")
        void rejectsMixedCurrencies() {
            OrderLine usd = new OrderLine("SKU-1", "", 1, Money.of("1.00", "USD"));
            OrderLine gbp = new OrderLine("SKU-2", "", 1, Money.of("1.00", "GBP"));
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new Order("ORD-1", "CUST-1", List.of(usd, gbp), T0));
            assertTrue(e.getMessage().contains("share a currency"));
        }

        @Test
        @DisplayName("rejects duplicate SKUs, which would reserve the same item twice")
        void rejectsDuplicateSkus() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> order(line("SKU-1", 1, "5.00"), line("SKU-1", 2, "5.00")));
            assertTrue(e.getMessage().contains("duplicate SKUs"));
        }

        @Test
        void startsPendingAndRecordsCreation() {
            Order order = order(line("SKU-1", 2, "10.00"));
            assertEquals(OrderStatus.PENDING, order.status());
            assertEquals(1, order.history().size());
            assertEquals("CREATED", order.history().get(0).type());
        }
    }

    @Nested
    @DisplayName("pricing")
    class Pricing {

        @Test
        void totalsTheLines() {
            Order order = order(line("SKU-1", 2, "10.00"), line("SKU-2", 3, "5.50"));
            assertEquals(Money.of("36.50", "USD"), order.subtotal());
            assertEquals(5, order.totalUnits());
        }

        @Test
        void appliesADiscountToTheTotal() {
            Order order = order(line("SKU-1", 1, "100.00"));
            order.applyDiscount(BigDecimal.TEN, "loyalty", T0);
            assertEquals(Money.of("10.00", "USD"), order.discount());
            assertEquals(Money.of("90.00", "USD"), order.total());
        }

        @Test
        void rejectsDiscountsOutsideZeroToOneHundred() {
            Order order = order(line("SKU-1", 1, "100.00"));
            assertThrows(IllegalArgumentException.class,
                    () -> order.applyDiscount(BigDecimal.valueOf(-5), "bad", T0));
            assertThrows(IllegalArgumentException.class,
                    () -> order.applyDiscount(BigDecimal.valueOf(101), "bad", T0));
        }

        @Test
        @DisplayName("refuses to re-price after payment — that is a refund, not a discount")
        void refusesRepricingAfterPayment() {
            Order order = order(line("SKU-1", 1, "100.00"));
            order.confirm(T0);
            order.markPaid("PAY-1", T0);
            Order.IllegalOrderStateException e = assertThrows(Order.IllegalOrderStateException.class,
                    () -> order.applyDiscount(BigDecimal.TEN, "too late", T0));
            assertTrue(e.getMessage().contains("refund"));
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        void walksTheHappyPath() {
            Order order = order(line("SKU-1", 1, "10.00"));
            order.confirm(T0);
            assertEquals(OrderStatus.CONFIRMED, order.status());
            order.markPaid("PAY-1", T0);
            assertEquals(OrderStatus.PAID, order.status());
            order.ship("TRACK-1", T0);
            assertEquals(OrderStatus.SHIPPED, order.status());
            order.markDelivered(T0);
            assertEquals(OrderStatus.DELIVERED, order.status());
            assertTrue(order.status().isTerminal());
        }

        @Test
        @DisplayName("refuses to skip a step")
        void refusesToSkipSteps() {
            Order order = order(line("SKU-1", 1, "10.00"));
            assertThrows(Order.IllegalOrderStateException.class, () -> order.ship("TRACK-1", T0));
            assertThrows(Order.IllegalOrderStateException.class, () -> order.markPaid("PAY-1", T0));
            assertEquals(OrderStatus.PENDING, order.status(), "a refused transition must not mutate state");
        }

        @Test
        void cancelsFromAnyPrePaidOrPaidState() {
            for (OrderStatus from : List.of(OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.PAID)) {
                Order order = order(line("SKU-1", 1, "10.00"));
                if (from != OrderStatus.PENDING) {
                    order.confirm(T0);
                }
                if (from == OrderStatus.PAID) {
                    order.markPaid("PAY-1", T0);
                }
                order.cancel("customer changed their mind", T0);
                assertEquals(OrderStatus.CANCELLED, order.status());
            }
        }

        @Test
        @DisplayName("cannot cancel once shipped — the goods have already left")
        void cannotCancelOnceShipped() {
            Order order = order(line("SKU-1", 1, "10.00"));
            order.confirm(T0);
            order.markPaid("PAY-1", T0);
            order.ship("TRACK-1", T0);
            assertThrows(Order.IllegalOrderStateException.class, () -> order.cancel("too late", T0));
        }

        @Test
        void requiresReasonsAndReferences() {
            Order pending = order(line("SKU-1", 1, "10.00"));
            assertThrows(IllegalArgumentException.class, () -> pending.cancel("  ", T0));

            Order confirmed = order(line("SKU-1", 1, "10.00"));
            confirmed.confirm(T0);
            assertThrows(IllegalArgumentException.class, () -> confirmed.markPaid("", T0));

            Order paid = order(line("SKU-1", 1, "10.00"));
            paid.confirm(T0);
            paid.markPaid("PAY-1", T0);
            assertThrows(IllegalArgumentException.class, () -> paid.ship(null, T0));
        }

        @Test
        void terminalStatesAreFinal() {
            Order order = order(line("SKU-1", 1, "10.00"));
            order.cancel("changed mind", T0);
            assertThrows(Order.IllegalOrderStateException.class, () -> order.confirm(T0));
            assertFalse(order.status().holdsReservation());
        }

        @Test
        @DisplayName("shipped orders no longer hold a reservation")
        void shippedOrdersReleaseTheirClaim() {
            assertTrue(OrderStatus.PENDING.holdsReservation());
            assertTrue(OrderStatus.CONFIRMED.holdsReservation());
            assertTrue(OrderStatus.PAID.holdsReservation());
            assertFalse(OrderStatus.SHIPPED.holdsReservation());
            assertFalse(OrderStatus.DELIVERED.holdsReservation());
        }
    }

    @Nested
    @DisplayName("history")
    class History {

        @Test
        void recordsEveryChangeInOrder() {
            Order order = order(line("SKU-1", 1, "10.00"));
            order.applyDiscount(BigDecimal.TEN, "loyalty", T0);
            order.confirm(T0);
            order.markPaid("PAY-1", T0);

            List<String> types = order.history().stream().map(Order.Event::type).toList();
            assertEquals(List.of("CREATED", "DISCOUNT_APPLIED", "CONFIRMED", "PAID"), types);
            assertTrue(order.history().get(3).detail().contains("PAY-1"));
        }

        @Test
        void historyIsNotModifiableFromOutside() {
            Order order = order(line("SKU-1", 1, "10.00"));
            assertThrows(UnsupportedOperationException.class,
                    () -> order.history().add(new Order.Event(T0, "FORGED", "nope")));
        }

        @Test
        void linesAreImmutable() {
            Order order = order(line("SKU-1", 1, "10.00"));
            assertThrows(UnsupportedOperationException.class,
                    () -> order.lines().add(line("SKU-2", 1, "1.00")));
        }
    }
}
