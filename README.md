# Order Service (Java 21)

Order lifecycle with atomic inventory reservation, volume pricing, idempotent order placement and
an HTTP/JSON API. **74 tests, all passing.**

Zero runtime dependencies — the HTTP server is the JDK's own, and JSON is a small hand-written
codec. One jar, no container, no database, nothing to configure before it runs.

## Build and run

Requires **JDK 21** (the `--release 21` features are used):

```bash
mvn test                                        # 74 tests
mvn package
java -jar target/order-service-1.0.0.jar        # port 8080, seeded catalogue
java -jar target/order-service-1.0.0.jar 9090   # or pick a port
```

## Try it

```bash
curl localhost:8080/inventory

# 2 laptops + 25 docks = 27 units, so the 5% volume tier applies automatically
curl -X POST localhost:8080/orders \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
  -d '{"customerId":"CUST-1","lines":[
        {"sku":"SKU-LAPTOP","quantity":2,"unitPrice":"1299.00","currency":"USD"},
        {"sku":"SKU-DOCK","quantity":25,"unitPrice":"189.00","currency":"USD"}]}'
# -> 201  subtotal 7323.00, discount 5%, total 6956.85

# same key again -> the original order, stock reserved once
# SKU-KEYBOARD is seeded at 0 -> 409 insufficient_stock
curl localhost:8080/summary
```

## API

| Method | Path | Notes |
|---|---|---|
| `GET` | `/health` | liveness |
| `GET` | `/inventory` | on hand / reserved / available per SKU |
| `POST` | `/inventory` | `{"sku":"…","quantity":10}` |
| `GET` | `/orders` | newest first; `?customerId=` filters |
| `POST` | `/orders` | place an order; honours the `Idempotency-Key` header |
| `GET` | `/orders/{id}` | one order with its full event history |
| `POST` | `/orders/{id}/confirm` | |
| `POST` | `/orders/{id}/pay` | `{"paymentReference":"…"}` |
| `POST` | `/orders/{id}/ship` | `{"trackingNumber":"…"}` — consumes the reservation |
| `POST` | `/orders/{id}/deliver` | |
| `POST` | `/orders/{id}/cancel` | `{"reason":"…"}` — releases the reservation |
| `GET` | `/summary` | counts by status, recognised revenue |

Errors carry a stable machine-readable code so clients branch on the code, not on prose:

```json
{"error":"insufficient_stock","message":"insufficient stock for SKU-KEYBOARD: requested 1, available 0"}
```

`400` invalid request · `404` unknown order or route · `409` insufficient stock or illegal
transition · `500` unexpected (logged server-side, never leaked to the caller).

## Design choices worth knowing

**Reserved ≠ on hand.** [Inventory](src/main/java/com/acme/orders/Inventory.java) tracks *on hand*
(physically present), *reserved* (promised to open orders) and *available* (the difference). Selling
against on-hand is how two customers buy the last unit. A test races 20 threads for 10 units and
asserts exactly 10 sell.

**Basket reservation is all-or-nothing.** A partial reservation leaves an order half-fulfillable
with no clean way to unwind, so `reserve()` checks every line before changing anything — a failure
needs no rollback, and the response names the SKU that was short.

**Shipping consumes stock; cancelling releases it — but only if it was still held.** Once shipped,
the goods have physically left, so `OrderStatus.holdsReservation()` returns false and cancelling a
shipped order can't invent inventory that no longer exists.

**Idempotency is the server's job.** On a timeout a client can't tell whether the first attempt
landed, so it retries. `POST /orders` with a repeated `Idempotency-Key` returns the original order
rather than reserving the stock twice.

**Illegal states are unreachable, not merely unwritten.**
[Order](src/main/java/com/acme/orders/domain/Order.java) has no setters; every mutation checks the
transition table on [OrderStatus](src/main/java/com/acme/orders/domain/OrderStatus.java) and throws
otherwise. There's no path out of `DELIVERED` or `CANCELLED` — a returned order is a *return*, a
separate record, not an un-delivery that erases what was actually sent.

**Money is a value object, not a `BigDecimal` field.** Currency is part of the value, so adding GBP
to USD throws instead of producing a plausible-looking invoice total. Scale is normalised once at
construction, so `0.1 + 0.2` behaves the way an accountant expects and `5.0` equals `5.00`.

**Line prices are copied onto the order, not read from the catalogue.** That's not redundancy — a
price change next week must not rewrite what the customer agreed to pay today.

**Totals are computed, never stored.** A stored total is a second source of truth that can disagree
with the lines.

**Every change appends to an event log.** `history()` answers "what happened to this order and
when?", maintained by the aggregate itself so it can't drift from the state.

## Layout

```
domain/     Money, OrderLine, OrderStatus, Order   — no framework, no I/O, fully unit tested
Inventory   stock levels + atomic reservations
OrderService  reservation, idempotency, volume discounts, id generation
api/        Json (hand-written codec), ApiServer (routing + error mapping)
Main        entry point with a seeded catalogue
```
