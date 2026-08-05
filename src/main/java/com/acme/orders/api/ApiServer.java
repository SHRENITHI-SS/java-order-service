package com.acme.orders.api;

import com.acme.orders.Inventory;
import com.acme.orders.OrderService;
import com.acme.orders.domain.Money;
import com.acme.orders.domain.Order;
import com.acme.orders.domain.OrderLine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * The HTTP API, on the JDK's built-in server.
 *
 * <p>Routes:
 * <pre>
 *   GET    /health                     liveness
 *   GET    /inventory                  stock levels: on hand / reserved / available
 *   POST   /inventory                  set stock for a SKU  {"sku":"...","quantity":10}
 *   GET    /orders                     all orders, newest first (?customerId= to filter)
 *   POST   /orders                     place an order (honours the Idempotency-Key header)
 *   GET    /orders/{id}                one order, with its event history
 *   POST   /orders/{id}/confirm
 *   POST   /orders/{id}/pay            {"paymentReference":"..."}
 *   POST   /orders/{id}/ship           {"trackingNumber":"..."}
 *   POST   /orders/{id}/deliver
 *   POST   /orders/{id}/cancel         {"reason":"..."}
 *   GET    /summary                    counts by status and recognised revenue
 * </pre>
 *
 * <p>Every response is a JSON object. Errors carry a stable machine-readable {@code error} code
 * alongside the human message, so a client can branch on the code without string-matching prose:
 * <pre>
 *   {"error":"insufficient_stock","message":"insufficient stock for SKU-1: requested 5, available 2"}
 * </pre>
 *
 * <p>Domain exceptions are translated once, here, in {@link #handle}. Scattering try/catch through
 * each route is how one endpoint ends up returning 500 for a condition its neighbour reports as 409.
 */
public class ApiServer {

    private final OrderService orders;
    private final Inventory inventory;
    private HttpServer server;

    public ApiServer(OrderService orders, Inventory inventory) {
        this.orders = orders;
        this.inventory = inventory;
    }

    /**
     * Starts the server.
     *
     * @param port the port, or 0 to let the OS pick a free one (used by the tests)
     * @return the port actually bound
     */
    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", this::handle);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // -- routing -------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        try {
            Response response = route(method, path, exchange);
            respond(exchange, response.status, response.body);
        } catch (OrderService.OrderRejectedException e) {
            // A rejection is an expected outcome with a known reason, so it maps to a 4xx with the
            // reason code preserved rather than to a generic 500.
            int status = switch (e.reason()) {
                case "not_found" -> 404;
                case "insufficient_stock" -> 409;
                default -> 400;
            };
            respond(exchange, status, error(e.reason(), e.getMessage()));
        } catch (Order.IllegalOrderStateException e) {
            // 409: the request was well-formed, but the order is not in a state that permits it.
            respond(exchange, 409, error("illegal_state", e.getMessage()));
        } catch (Json.ParseException e) {
            respond(exchange, 400, error("malformed_json", e.getMessage()));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error("invalid_request", e.getMessage()));
        } catch (RuntimeException e) {
            // Unexpected: log server-side, and do not leak internals to the caller.
            System.err.println("[error] " + method + " " + path + ": " + e);
            respond(exchange, 500, error("internal_error", "an unexpected error occurred"));
        } finally {
            exchange.close();
        }
    }

    private Response route(String method, String path, HttpExchange exchange) throws IOException {
        String[] segments = split(path);

        if (segments.length == 1 && segments[0].equals("health")) {
            return Response.ok(Map.of("status", "up"));
        }

        if (segments.length == 1 && segments[0].equals("summary") && method.equals("GET")) {
            return Response.ok(orders.summary());
        }

        if (segments.length == 1 && segments[0].equals("inventory")) {
            if (method.equals("GET")) {
                return Response.ok(Map.of("inventory", inventory.snapshot()));
            }
            if (method.equals("POST")) {
                Map<String, Object> body = Json.parseObject(readBody(exchange));
                String sku = requireString(body, "sku");
                int quantity = requireInt(body, "quantity");
                inventory.stock(sku, quantity);
                return Response.ok(Map.of("sku", sku, "onHand", inventory.onHand(sku),
                        "available", inventory.available(sku)));
            }
        }

        if (segments.length >= 1 && segments[0].equals("orders")) {
            if (segments.length == 1 && method.equals("GET")) {
                String customerId = queryParam(exchange, "customerId");
                List<Order> found = customerId == null ? orders.findAll() : orders.findByCustomer(customerId);
                List<Object> payload = new ArrayList<>();
                found.forEach(order -> payload.add(summarise(order)));
                return Response.ok(Map.of("count", payload.size(), "orders", payload));
            }

            if (segments.length == 1 && method.equals("POST")) {
                return placeOrder(exchange);
            }

            if (segments.length == 2 && method.equals("GET")) {
                Optional<Order> order = orders.find(segments[1]);
                if (order.isEmpty()) {
                    return new Response(404, error("not_found", "no such order: " + segments[1]));
                }
                return Response.ok(detail(order.get()));
            }

            if (segments.length == 3 && method.equals("POST")) {
                String id = segments[1];
                String action = segments[2];
                Map<String, Object> body = hasBody(exchange) ? Json.parseObject(readBody(exchange)) : Map.of();

                Order updated = switch (action) {
                    case "confirm" -> orders.confirm(id);
                    case "pay" -> orders.pay(id, requireString(body, "paymentReference"));
                    case "ship" -> orders.ship(id, requireString(body, "trackingNumber"));
                    case "deliver" -> orders.markDelivered(id);
                    case "cancel" -> orders.cancel(id, requireString(body, "reason"));
                    default -> null;
                };
                if (updated == null) {
                    return new Response(404, error("unknown_action", "no such action: " + action));
                }
                return Response.ok(detail(updated));
            }
        }

        return new Response(404, error("not_found", method + " " + path + " is not a route"));
    }

    private Response placeOrder(HttpExchange exchange) throws IOException {
        Map<String, Object> body = Json.parseObject(readBody(exchange));
        String customerId = requireString(body, "customerId");

        Object rawLines = body.get("lines");
        if (!(rawLines instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("lines must be a non-empty array");
        }

        List<OrderLine> lines = new ArrayList<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("each line must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> line = (Map<String, Object>) map;
            lines.add(new OrderLine(
                    requireString(line, "sku"),
                    line.get("description") == null ? "" : String.valueOf(line.get("description")),
                    requireInt(line, "quantity"),
                    Money.of(new BigDecimal(requireString(line, "unitPrice")),
                            requireString(line, "currency"))));
        }

        // The idempotency key belongs in a header, not the body: it is transport-level retry
        // metadata, not part of the order.
        String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");

        Order order = orders.placeOrder(customerId, lines, idempotencyKey);
        return new Response(201, detail(order));
    }

    // -- serialisation -------------------------------------------------------

    private Map<String, Object> summarise(Order order) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", order.id());
        out.put("customerId", order.customerId());
        out.put("status", order.status().name());
        out.put("units", order.totalUnits());
        out.put("total", order.total().amount().toPlainString());
        out.put("currency", order.currency());
        out.put("createdAt", order.createdAt().toString());
        return out;
    }

    private Map<String, Object> detail(Order order) {
        Map<String, Object> out = new LinkedHashMap<>(summarise(order));
        out.put("subtotal", order.subtotal().amount().toPlainString());
        out.put("discountPercent", order.discountPercent().toPlainString());
        out.put("discount", order.discount().amount().toPlainString());
        out.put("paymentReference", order.paymentReference());
        out.put("trackingNumber", order.trackingNumber());
        out.put("cancellationReason", order.cancellationReason());

        List<Object> lines = new ArrayList<>();
        for (OrderLine line : order.lines()) {
            Map<String, Object> serialised = new LinkedHashMap<>();
            serialised.put("sku", line.sku());
            serialised.put("description", line.description());
            serialised.put("quantity", line.quantity());
            serialised.put("unitPrice", line.unitPrice().amount().toPlainString());
            serialised.put("lineTotal", line.lineTotal().amount().toPlainString());
            lines.add(serialised);
        }
        out.put("lines", lines);

        List<Object> history = new ArrayList<>();
        for (Order.Event event : order.history()) {
            Map<String, Object> serialised = new LinkedHashMap<>();
            serialised.put("at", event.at().toString());
            serialised.put("type", event.type());
            serialised.put("detail", event.detail());
            history.add(serialised);
        }
        out.put("history", history);
        return out;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", code);
        out.put("message", message);
        return out;
    }

    // -- request helpers -----------------------------------------------------

    private record Response(int status, Map<String, Object> body) {
        static Response ok(Map<String, Object> body) {
            return new Response(200, body);
        }
    }

    private void respond(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] payload = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static String[] split(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? new String[0] : trimmed.split("/");
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean hasBody(HttpExchange exchange) {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        return length != null && !length.equals("0");
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(name)) {
                return java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String requireString(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return String.valueOf(value);
    }

    private static int requireInt(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a number, got: " + value);
        }
    }
}
