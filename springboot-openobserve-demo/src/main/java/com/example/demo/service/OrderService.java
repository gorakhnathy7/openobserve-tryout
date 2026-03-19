package com.example.demo.service;

import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Business logic for order management.
 *
 * The @Observed annotation on each method automatically creates a child span
 * in the active trace — no manual tracer code required. This is how you get
 * distributed tracing without polluting your business logic.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /**
     * @Observed creates a span named "order.fetch" for every call to this method.
     * The span will appear as a child of the HTTP request span in the trace waterfall.
     */
    @Observed(name = "order.fetch", contextualName = "fetching-order")
    public Map<String, Object> getOrder(String orderId) {
        log.info("Fetching order from database: {}", orderId);

        simulateProcessing(50); // Simulate DB read latency

        Map<String, Object> order = new HashMap<>();
        order.put("orderId", orderId);
        order.put("productId", "PROD-001");
        order.put("quantity", 2);
        order.put("status", "CONFIRMED");
        order.put("totalAmount", 99.99);

        log.debug("Order details: {}", order);
        return order;
    }

    /**
     * @Observed creates a span named "order.create" — visible in the trace timeline
     * alongside the parent HTTP span, showing exactly how long the DB write took.
     */
    @Observed(name = "order.create", contextualName = "creating-order")
    public Map<String, Object> createOrder(Map<String, Object> request) {
        String newOrderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("Creating new order with id: {}", newOrderId);

        simulateProcessing(100); // Simulate DB write latency

        Map<String, Object> order = new HashMap<>(request);
        order.put("orderId", newOrderId);
        order.put("status", "PENDING");
        order.put("createdAt", System.currentTimeMillis());

        log.info("Order {} created and saved to database", newOrderId);
        return order;
    }

    /**
     * Deliberately throws an exception to demonstrate error visibility.
     * Hit GET /api/orders/simulate-error to trigger this.
     */
    @Observed(name = "order.error", contextualName = "simulating-order-error")
    public void simulateError() {
        log.warn("About to throw a simulated exception");
        simulateProcessing(30);
        throw new RuntimeException("Simulated database connection timeout");
    }

    private void simulateProcessing(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Processing interrupted");
        }
    }
}
