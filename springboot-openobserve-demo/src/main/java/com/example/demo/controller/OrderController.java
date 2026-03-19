package com.example.demo.controller;

import com.example.demo.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller exposing order management endpoints.
 *
 * Every log statement here automatically carries a traceId and spanId,
 * allowing you to jump from a log line directly to the full trace in OpenObserve.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    // GET /api/orders/{orderId}
    @GetMapping("/{orderId}")
    public ResponseEntity<Map<String, Object>> getOrder(@PathVariable String orderId) {
        log.info("Received request to fetch order: {}", orderId);
        Map<String, Object> order = orderService.getOrder(orderId);
        log.info("Successfully fetched order: {}", orderId);
        return ResponseEntity.ok(order);
    }

    // POST /api/orders
    @PostMapping
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody Map<String, Object> request) {
        log.info("Received request to create order for product: {}", request.get("productId"));
        Map<String, Object> order = orderService.createOrder(request);
        log.info("Order created successfully with id: {}", order.get("orderId"));
        return ResponseEntity.ok(order);
    }

    /**
     * Intentionally triggers a RuntimeException to demonstrate error tracing.
     * In OpenObserve, you can filter logs by level=ERROR and then jump to the
     * corresponding trace to see exactly where in the call stack it failed.
     */
    @GetMapping("/simulate-error")
    public ResponseEntity<String> simulateError() {
        log.warn("Simulating an error scenario for demonstration");
        orderService.simulateError();
        return ResponseEntity.ok("No error");
    }

    // GET /api/orders/health — simple liveness check
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        log.debug("Health check requested");
        return ResponseEntity.ok(Map.of("status", "UP", "service", "order-service"));
    }
}
