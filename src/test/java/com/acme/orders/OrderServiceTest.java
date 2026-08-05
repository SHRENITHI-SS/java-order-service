package com.acme.orders;

import com.acme.orders.domain.Money;
import com.acme.orders.domain.Order;
import com.acme.orders.domain.OrderLine;
import com.acme.orders.domain.OrderStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Service tests: reservation, idempotency, discounts and the stock consequences of each lifecycle
 * step. This is where overselling would show up, so concurrency is tested for real rather than
 * assumed.
 */
class OrderServiceTest {

    private Inventory inventory;
    private OrderService service;

    @BeforeEach
    void setUp() {
        inventory = new Inventory();
        inventory.stock("SKU-1", 10);
        inventory.stock("SKU-2", 5);
        inventory.stock("SKU-OUT", 0);
        service = new OrderService(inventory,
                Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"), ZoneOffset.UTC));
    }

    private static OrderLine line(String sku, int quantity, String price) {
        return new OrderLine(sku, sku, quantity, Money.of(price, "USD"));
    }

    private Order place(String sku, int quantity) {
        return service.placeOrder("CUST-1", List.of(line(sku, quantity, "10.00")), null);
    }

    @Nested
    @DisplayName("placing orders")
    class Placing {

        @Test
        void reservesStockOnPlacement() {
            place("SKU-1", 3);
            assertEquals(10, inventory.onHand("SKU-1"), "physical stock does not move until shipment");
            assertEquals(3, inventory.reserved("SKU-1"));
            assertEquals(7, inventory.available("SKU-1"));
        }

        @Test
        @DisplayName("rejects an order that exceeds available stock, naming the short SKU")
        void rejectsInsufficientStock() {
            OrderService.OrderRejectedException e = assertThrows(OrderService.OrderRejectedException.class,
                    () -> place("SKU-2", 6));
            assertEquals("insufficient_stock", e.reason());
            assertTrue(e.getMessage().contains("SKU-2"));
            assertTrue(e.getMessage().contains("available 5"));
            assertEquals(0, inventory.reserved("SKU-2"), "a rejected order must reserve nothing");
        }

        @Test
        @DisplayName("reserves a multi-line basket all-or-nothing")
        void reservesBasketAtomically() {
            assertThrows(OrderService.OrderRejectedException.class, () -> service.placeOrder("CUST-1",
                    List.of(line("SKU-1", 2, "10.00"), line("SKU-2", 99, "10.00")), null));

            assertEquals(0, inventory.reserved("SKU-1"),
                    "the line that would have succeeded must not stay reserved");
            assertEquals(0, inventory.reserved("SKU-2"));
        }

        @Test
        void rejectsEmptyOrAnonymousBaskets() {
            assertEquals("empty_basket",
                    assertThrows(OrderService.OrderRejectedException.class,
                            () -> service.placeOrder("CUST-1", List.of(), null)).reason());
            assertEquals("invalid_customer",
                    assertThrows(OrderService.OrderRejectedException.class,
                            () -> service.placeOrder("  ", List.of(line("SKU-1", 1, "1.00")), null)).reason());
        }

        @Test
        @DisplayName("releases the reservation if the basket turns out to be invalid")
        void doesNotLeakReservationsOnInvalidBaskets() {
            // Duplicate SKUs pass the stock check but fail the aggregate's construction rules.
            OrderService.OrderRejectedException e = assertThrows(OrderService.OrderRejectedException.class,
                    () -> service.placeOrder("CUST-1",
                            List.of(line("SKU-1", 1, "10.00"), line("SKU-1", 2, "10.00")), null));
            assertEquals("invalid_basket", e.reason());
            assertEquals(0, inventory.reserved("SKU-1"), "the reservation must be released, not leaked");
            assertEquals(10, inventory.available("SKU-1"));
        }

        @Test
        void generatesDistinctIds() {
            assertNotEquals(place("SKU-1", 1).id(), place("SKU-1", 1).id());
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("a retried request returns the original order and reserves stock once")
        void retryReturnsTheOriginal() {
            Order first = service.placeOrder("CUST-1", List.of(line("SKU-1", 2, "10.00")), "key-1");
            Order retry = service.placeOrder("CUST-1", List.of(line("SKU-1", 2, "10.00")), "key-1");

            assertSame(first, retry);
            assertEquals(2, inventory.reserved("SKU-1"), "a retry must not double-reserve");
            assertEquals(1, service.findAll().size());
        }

        @Test
        void differentKeysPlaceDifferentOrders() {
            service.placeOrder("CUST-1", List.of(line("SKU-1", 2, "10.00")), "key-1");
            service.placeOrder("CUST-1", List.of(line("SKU-1", 2, "10.00")), "key-2");
            assertEquals(4, inventory.reserved("SKU-1"));
            assertEquals(2, service.findAll().size());
        }

        @Test
        void anAbsentKeyMeansNoDeduplication() {
            place("SKU-1", 1);
            place("SKU-1", 1);
            assertEquals(2, service.findAll().size());
        }
    }

    @Nested
    @DisplayName("volume discounts")
    class Discounts {

        @Test
        void appliesTheHighestMatchingTier() {
            inventory.stock("SKU-BULK", 500);

            assertEquals("0", service.placeOrder("C", List.of(line("SKU-BULK", 5, "10.00")), null)
                    .discountPercent().toPlainString());
            assertEquals("5", service.placeOrder("C", List.of(line("SKU-BULK", 20, "10.00")), null)
                    .discountPercent().toPlainString());
            assertEquals("10", service.placeOrder("C", List.of(line("SKU-BULK", 50, "10.00")), null)
                    .discountPercent().toPlainString());
            assertEquals("15", service.placeOrder("C", List.of(line("SKU-BULK", 120, "10.00")), null)
                    .discountPercent().toPlainString());
        }

        @Test
        void discountAppliesToTheTotal() {
            inventory.stock("SKU-BULK", 500);
            Order order = service.placeOrder("C", List.of(line("SKU-BULK", 100, "10.00")), null);
            assertEquals(Money.of("1000.00", "USD"), order.subtotal());
            assertEquals(Money.of("150.00", "USD"), order.discount());
            assertEquals(Money.of("850.00", "USD"), order.total());
        }

        @Test
        void countsUnitsAcrossLinesNotLineCount() {
            inventory.stock("SKU-A", 100);
            inventory.stock("SKU-B", 100);
            Order order = service.placeOrder("C",
                    List.of(line("SKU-A", 15, "10.00"), line("SKU-B", 10, "10.00")), null);
            assertEquals(25, order.totalUnits());
            assertEquals("5", order.discountPercent().toPlainString());
        }
    }

    @Nested
    @DisplayName("lifecycle and stock")
    class LifecycleAndStock {

        @Test
        @DisplayName("cancelling before shipment returns the stock to available")
        void cancellationReleasesStock() {
            Order order = place("SKU-1", 4);
            assertEquals(6, inventory.available("SKU-1"));

            service.cancel(order.id(), "customer changed their mind");
            assertEquals(0, inventory.reserved("SKU-1"));
            assertEquals(10, inventory.available("SKU-1"));
            assertEquals(OrderStatus.CANCELLED, order.status());
        }

        @Test
        @DisplayName("shipping consumes the reservation and reduces physical stock")
        void shippingConsumesStock() {
            Order order = place("SKU-1", 4);
            service.confirm(order.id());
            service.pay(order.id(), "PAY-1");
            service.ship(order.id(), "TRACK-1");

            assertEquals(6, inventory.onHand("SKU-1"), "the goods have physically left");
            assertEquals(0, inventory.reserved("SKU-1"), "the reservation is consumed, not still held");
            assertEquals(6, inventory.available("SKU-1"));
        }

        @Test
        @DisplayName("cancelling a shipped order does not invent stock that has already left")
        void cancellingAfterShipmentCannotReturnStock() {
            Order order = place("SKU-1", 4);
            service.confirm(order.id());
            service.pay(order.id(), "PAY-1");
            service.ship(order.id(), "TRACK-1");

            assertThrows(Order.IllegalOrderStateException.class,
                    () -> service.cancel(order.id(), "too late"));
            assertEquals(6, inventory.onHand("SKU-1"));
            assertEquals(6, inventory.available("SKU-1"));
        }

        @Test
        void reportsAMissingOrderAsNotFound() {
            assertEquals("not_found",
                    assertThrows(OrderService.OrderRejectedException.class,
                            () -> service.confirm("ORD-nope")).reason());
        }
    }

    @Nested
    @DisplayName("queries")
    class Queries {

        @Test
        void findsByIdAndByCustomer() {
            Order mine = service.placeOrder("CUST-A", List.of(line("SKU-1", 1, "10.00")), null);
            service.placeOrder("CUST-B", List.of(line("SKU-1", 1, "10.00")), null);

            assertTrue(service.find(mine.id()).isPresent());
            assertTrue(service.find("nope").isEmpty());
            assertEquals(1, service.findByCustomer("CUST-A").size());
            assertEquals(2, service.findAll().size());
        }

        @Test
        @DisplayName("revenue counts paid orders only, not everything that exists")
        void summaryRecognisesRevenueOnlyOncePaid() {
            Order unpaid = place("SKU-1", 1);
            Order paid = place("SKU-1", 2);
            service.confirm(paid.id());
            service.pay(paid.id(), "PAY-1");

            Map<String, Object> summary = service.summary();
            assertEquals(2, summary.get("totalOrders"));
            assertEquals("20.00", summary.get("recognisedRevenue"));
            assertEquals("USD", summary.get("currency"));

            @SuppressWarnings("unchecked")
            Map<String, Integer> byStatus = (Map<String, Integer>) summary.get("byStatus");
            assertEquals(1, byStatus.get("PENDING"));
            assertEquals(1, byStatus.get("PAID"));
            assertEquals(0, byStatus.get("CANCELLED"));
        }

        @Test
        void summaryHandlesAnEmptyStore() {
            Map<String, Object> summary = service.summary();
            assertEquals(0, summary.get("totalOrders"));
            assertEquals("0.00", summary.get("recognisedRevenue"));
            assertNull(summary.get("currency"));
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("twenty threads racing for ten units sell exactly ten — no overselling")
        void doesNotOversellUnderContention() throws Exception {
            inventory.stock("SKU-RACE", 10);

            int threads = 20;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startLine = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startLine.await();
                        service.placeOrder("CUST-" + Thread.currentThread().getId(),
                                List.of(line("SKU-RACE", 1, "10.00")), null);
                        accepted.incrementAndGet();
                    } catch (OrderService.OrderRejectedException e) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            startLine.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "threads should finish promptly");

            assertEquals(10, accepted.get(), "exactly the available stock should be sold");
            assertEquals(10, rejected.get(), "the rest must be rejected, not oversold");
            assertEquals(10, inventory.reserved("SKU-RACE"));
            assertEquals(0, inventory.available("SKU-RACE"));
        }
    }

    @Nested
    @DisplayName("Inventory")
    class InventoryBehaviour {

        @Test
        void distinguishesOnHandFromAvailable() {
            inventory.stock("SKU-X", 5);
            assertEquals(ings(5, 0, 5), ings(inventory.onHand("SKU-X"), inventory.reserved("SKU-X"),
                    inventory.available("SKU-X")));

            inventory.reserve(Map.of("SKU-X", 2));
            assertEquals(5, inventory.onHand("SKU-X"));
            assertEquals(2, inventory.reserved("SKU-X"));
            assertEquals(3, inventory.available("SKU-X"));
        }

        @Test
        @DisplayName("a double release cannot create phantom availability")
        void clampsReleaseAtZero() {
            inventory.stock("SKU-X", 5);
            inventory.reserve(Map.of("SKU-X", 2));
            inventory.release(Map.of("SKU-X", 2));
            inventory.release(Map.of("SKU-X", 2));
            assertEquals(0, inventory.reserved("SKU-X"));
            assertEquals(5, inventory.available("SKU-X"));
        }

        @Test
        void rejectsNegativeStock() {
            assertThrows(IllegalArgumentException.class, () -> inventory.stock("SKU-X", -1));
        }

        @Test
        void treatsUnknownSkusAsZero() {
            assertEquals(0, inventory.onHand("SKU-UNKNOWN"));
            assertEquals(0, inventory.available("SKU-UNKNOWN"));
            assertFalse(inventory.reserve(Map.of("SKU-UNKNOWN", 1)).success());
        }

        @Test
        void snapshotIsSortedAndComplete() {
            inventory.reserve(Map.of("SKU-1", 3));
            Map<String, Map<String, Integer>> snapshot = inventory.snapshot();
            assertEquals(List.of("SKU-1", "SKU-2", "SKU-OUT"), List.copyOf(snapshot.keySet()));
            assertEquals(3, snapshot.get("SKU-1").get("reserved"));
            assertEquals(7, snapshot.get("SKU-1").get("available"));
        }

        private static List<Integer> ings(int a, int b, int c) {
            return List.of(a, b, c);
        }
    }
}
