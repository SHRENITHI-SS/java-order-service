package com.acme.orders;

import com.acme.orders.api.ApiServer;

import java.io.IOException;
import java.time.Clock;

/**
 * Entry point. Starts the API with a small seeded catalogue so the service is useful the moment it
 * boots — there is no separate migration or fixture step to run first.
 *
 * <pre>
 *   mvn -q package
 *   java -jar target/order-service-1.0.0.jar          # port 8080
 *   java -jar target/order-service-1.0.0.jar 9090     # or pick one
 * </pre>
 */
public final class Main {

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        Inventory inventory = new Inventory();
        inventory.stock("SKU-LAPTOP", 25);
        inventory.stock("SKU-DOCK", 60);
        inventory.stock("SKU-MONITOR", 12);
        inventory.stock("SKU-CABLE", 500);
        inventory.stock("SKU-KEYBOARD", 0);   // deliberately out of stock, to exercise the 409 path

        OrderService orders = new OrderService(inventory, Clock.systemUTC());
        ApiServer api = new ApiServer(orders, inventory);
        int bound = api.start(port);

        Runtime.getRuntime().addShutdownHook(new Thread(api::stop));

        System.out.println("Order service listening on http://localhost" + ":" + bound);
        System.out.println();
        System.out.println("  curl localhost:" + bound + "/inventory");
        System.out.println("  curl -X POST localhost:" + bound + "/orders \\");
        System.out.println("    -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \\");
        System.out.println("    -d '{\"customerId\":\"CUST-1\",\"lines\":[");
        System.out.println("          {\"sku\":\"SKU-LAPTOP\",\"quantity\":2,\"unitPrice\":\"1299.00\",\"currency\":\"USD\"}]}'");
        System.out.println("  curl localhost:" + bound + "/summary");
    }

    private Main() {
    }
}
