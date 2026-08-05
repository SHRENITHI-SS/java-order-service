package com.acme.orders.api;

import com.acme.orders.Inventory;
import com.acme.orders.OrderService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end HTTP tests against a real server on a real socket.
 *
 * <p>These drive the service the way a client does — no mocks, no direct service calls — so they
 * cover routing, JSON round-tripping, status-code mapping and header handling together. Port 0 lets
 * the OS pick a free port, so the suite never collides with a developer's running instance.
 */
class ApiServerTest {

    private ApiServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void setUp() throws IOException {
        Inventory inventory = new Inventory();
        inventory.stock("SKU-1", 10);
        inventory.stock("SKU-OUT", 0);
        OrderService orders = new OrderService(inventory,
                Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"), ZoneOffset.UTC));

        server = new ApiServer(orders, inventory);
        int port = server.start(0);
        base = "http://localhost:" + port;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // -- helpers -------------------------------------------------------------

    private record Result(int status, Map<String, Object> body) {}

    private Result get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), Json.parseObject(response.body()));
    }

    private Result post(String path, String body, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), Json.parseObject(response.body()));
    }

    private String orderBody(String sku, int quantity) {
        return "{\"customerId\":\"CUST-1\",\"lines\":[{\"sku\":\"" + sku
                + "\",\"description\":\"a thing\",\"quantity\":" + quantity
                + ",\"unitPrice\":\"25.00\",\"currency\":\"USD\"}]}";
    }

    private String placeOrder(String sku, int quantity) throws Exception {
        return String.valueOf(post("/orders", orderBody(sku, quantity)).body().get("id"));
    }

    // -- tests ---------------------------------------------------------------

    @Nested
    @DisplayName("basics")
    class Basics {

        @Test
        void reportsHealth() throws Exception {
            Result result = get("/health");
            assertEquals(200, result.status());
            assertEquals("up", result.body().get("status"));
        }

        @Test
        void returns404ForAnUnknownRoute() throws Exception {
            Result result = get("/nope");
            assertEquals(404, result.status());
            assertEquals("not_found", result.body().get("error"));
        }

        @Test
        void exposesInventory() throws Exception {
            Result result = get("/inventory");
            assertEquals(200, result.status());
            @SuppressWarnings("unchecked")
            Map<String, Object> stock = (Map<String, Object>) result.body().get("inventory");
            @SuppressWarnings("unchecked")
            Map<String, Object> sku1 = (Map<String, Object>) stock.get("SKU-1");
            assertEquals(10L, sku1.get("onHand"));
            assertEquals(10L, sku1.get("available"));
        }

        @Test
        void setsStockLevels() throws Exception {
            Result result = post("/inventory", "{\"sku\":\"SKU-NEW\",\"quantity\":42}");
            assertEquals(200, result.status());
            assertEquals(42L, result.body().get("onHand"));
        }
    }

    @Nested
    @DisplayName("placing orders")
    class Placing {

        @Test
        void returns201WithTheFullOrder() throws Exception {
            Result result = post("/orders", orderBody("SKU-1", 2));
            assertEquals(201, result.status());
            assertEquals("PENDING", result.body().get("status"));
            assertEquals("50.00", result.body().get("total"));
            assertEquals(2L, result.body().get("units"));
            assertTrue(String.valueOf(result.body().get("id")).startsWith("ORD-"));

            @SuppressWarnings("unchecked")
            List<Object> history = (List<Object>) result.body().get("history");
            assertEquals(1, history.size());
        }

        @Test
        @DisplayName("returns 409 when stock is short")
        void returns409OnInsufficientStock() throws Exception {
            Result result = post("/orders", orderBody("SKU-OUT", 1));
            assertEquals(409, result.status());
            assertEquals("insufficient_stock", result.body().get("error"));
            assertTrue(String.valueOf(result.body().get("message")).contains("SKU-OUT"));
        }

        @Test
        @DisplayName("honours the Idempotency-Key header across retries")
        void honoursIdempotencyKey() throws Exception {
            Result first = post("/orders", orderBody("SKU-1", 2), "Idempotency-Key", "abc-123");
            Result retry = post("/orders", orderBody("SKU-1", 2), "Idempotency-Key", "abc-123");

            assertEquals(first.body().get("id"), retry.body().get("id"));
            Result orders = get("/orders");
            assertEquals(1L, orders.body().get("count"));

            Result inventory = get("/inventory");
            @SuppressWarnings("unchecked")
            Map<String, Object> stock = (Map<String, Object>) inventory.body().get("inventory");
            @SuppressWarnings("unchecked")
            Map<String, Object> sku1 = (Map<String, Object>) stock.get("SKU-1");
            assertEquals(2L, sku1.get("reserved"), "a retry must not reserve twice");
        }

        @Test
        void returns400OnMalformedJson() throws Exception {
            Result result = post("/orders", "{not json");
            assertEquals(400, result.status());
            assertEquals("malformed_json", result.body().get("error"));
        }

        @Test
        void returns400OnMissingFields() throws Exception {
            assertEquals(400, post("/orders", "{\"lines\":[]}").status());
            assertEquals(400, post("/orders", "{\"customerId\":\"C\"}").status());

            Result result = post("/orders",
                    "{\"customerId\":\"C\",\"lines\":[{\"quantity\":1,\"unitPrice\":\"1.00\",\"currency\":\"USD\"}]}");
            assertEquals(400, result.status());
            assertTrue(String.valueOf(result.body().get("message")).contains("sku"));
        }
    }

    @Nested
    @DisplayName("lifecycle over HTTP")
    class Lifecycle {

        @Test
        void walksTheHappyPath() throws Exception {
            String id = placeOrder("SKU-1", 2);

            assertEquals("CONFIRMED", post("/orders/" + id + "/confirm", null).body().get("status"));
            assertEquals("PAID", post("/orders/" + id + "/pay",
                    "{\"paymentReference\":\"PAY-1\"}").body().get("status"));
            assertEquals("SHIPPED", post("/orders/" + id + "/ship",
                    "{\"trackingNumber\":\"TRACK-1\"}").body().get("status"));

            Result delivered = post("/orders/" + id + "/deliver", null);
            assertEquals("DELIVERED", delivered.body().get("status"));
            assertEquals("TRACK-1", delivered.body().get("trackingNumber"));

            @SuppressWarnings("unchecked")
            List<Object> history = (List<Object>) delivered.body().get("history");
            assertEquals(5, history.size(), "created, confirmed, paid, shipped, delivered");
        }

        @Test
        @DisplayName("returns 409 for a transition the order's state does not allow")
        void returns409OnIllegalTransition() throws Exception {
            String id = placeOrder("SKU-1", 1);
            Result result = post("/orders/" + id + "/ship", "{\"trackingNumber\":\"TRACK-1\"}");
            assertEquals(409, result.status());
            assertEquals("illegal_state", result.body().get("error"));
            assertTrue(String.valueOf(result.body().get("message")).contains("PENDING"));
        }

        @Test
        void cancellingReleasesStock() throws Exception {
            String id = placeOrder("SKU-1", 4);
            Result cancelled = post("/orders/" + id + "/cancel", "{\"reason\":\"changed mind\"}");
            assertEquals("CANCELLED", cancelled.body().get("status"));
            assertEquals("changed mind", cancelled.body().get("cancellationReason"));

            Result inventory = get("/inventory");
            @SuppressWarnings("unchecked")
            Map<String, Object> stock = (Map<String, Object>) inventory.body().get("inventory");
            @SuppressWarnings("unchecked")
            Map<String, Object> sku1 = (Map<String, Object>) stock.get("SKU-1");
            assertEquals(0L, sku1.get("reserved"));
            assertEquals(10L, sku1.get("available"));
        }

        @Test
        void requiresTheActionsMandatoryFields() throws Exception {
            String id = placeOrder("SKU-1", 1);
            post("/orders/" + id + "/confirm", null);

            assertEquals(400, post("/orders/" + id + "/pay", "{}").status());
            assertEquals(400, post("/orders/" + id + "/cancel", "{}").status());
        }

        @Test
        void returns404ForAnUnknownOrderOrAction() throws Exception {
            assertEquals(404, get("/orders/ORD-nope").status());
            assertEquals(404, post("/orders/ORD-nope/confirm", null).status());

            String id = placeOrder("SKU-1", 1);
            Result unknown = post("/orders/" + id + "/teleport", null);
            assertEquals(404, unknown.status());
            assertEquals("unknown_action", unknown.body().get("error"));
        }
    }

    @Nested
    @DisplayName("queries over HTTP")
    class Queries {

        @Test
        void listsAndFiltersOrders() throws Exception {
            post("/orders", orderBody("SKU-1", 1));
            post("/orders", "{\"customerId\":\"CUST-2\",\"lines\":[{\"sku\":\"SKU-1\",\"quantity\":1,"
                    + "\"unitPrice\":\"10.00\",\"currency\":\"USD\"}]}");

            assertEquals(2L, get("/orders").body().get("count"));
            assertEquals(1L, get("/orders?customerId=CUST-2").body().get("count"));
            assertEquals(0L, get("/orders?customerId=NOBODY").body().get("count"));
        }

        @Test
        void summarisesByStatusAndRevenue() throws Exception {
            String paid = placeOrder("SKU-1", 2);
            post("/orders/" + paid + "/confirm", null);
            post("/orders/" + paid + "/pay", "{\"paymentReference\":\"PAY-1\"}");
            placeOrder("SKU-1", 1);

            Result summary = get("/summary");
            assertEquals(2L, summary.body().get("totalOrders"));
            assertEquals("50.00", summary.body().get("recognisedRevenue"));

            @SuppressWarnings("unchecked")
            Map<String, Object> byStatus = (Map<String, Object>) summary.body().get("byStatus");
            assertEquals(1L, byStatus.get("PAID"));
            assertEquals(1L, byStatus.get("PENDING"));
        }
    }

    @Nested
    @DisplayName("JSON codec")
    class JsonCodec {

        @Test
        void roundTripsNestedStructures() {
            String encoded = Json.write(Map.of("a", List.of(1, 2, 3)));
            @SuppressWarnings("unchecked")
            List<Object> decoded = (List<Object>) Json.parseObject(encoded).get("a");
            assertEquals(List.of(1L, 2L, 3L), decoded);
        }

        @Test
        void escapesControlCharactersAndQuotes() {
            String encoded = Json.write(Map.of("text", "he said \"hi\"\nnew\tline"));
            assertEquals("he said \"hi\"\nnew\tline", Json.parseObject(encoded).get("text"));
        }

        @Test
        void distinguishesIntegersFromDecimals() {
            Map<String, Object> parsed = Json.parseObject("{\"i\":42,\"d\":42.5,\"neg\":-7}");
            assertEquals(42L, parsed.get("i"));
            assertEquals(42.5, parsed.get("d"));
            assertEquals(-7L, parsed.get("neg"));
        }

        @Test
        void handlesEmptyContainersNullsAndBooleans() {
            Map<String, Object> parsed = Json.parseObject(
                    "{\"o\":{},\"a\":[],\"n\":null,\"t\":true,\"f\":false}");
            assertEquals(Map.of(), parsed.get("o"));
            assertEquals(List.of(), parsed.get("a"));
            assertNull(parsed.get("n"));
            assertEquals(Boolean.TRUE, parsed.get("t"));
            assertEquals(Boolean.FALSE, parsed.get("f"));
        }

        @Test
        void reportsThePositionOfMalformedInput() {
            Json.ParseException e = assertThrows(Json.ParseException.class,
                    () -> Json.parse("{\"a\":1,}"));
            assertTrue(e.getMessage().contains("position"));

            assertThrows(Json.ParseException.class, () -> Json.parse(""));
            assertThrows(Json.ParseException.class, () -> Json.parse("{\"a\":1} trailing"));
            assertThrows(Json.ParseException.class, () -> Json.parse("{\"unterminated\":\"x"));
        }
    }
}
