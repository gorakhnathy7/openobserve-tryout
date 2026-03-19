package com.example.demo.config;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Populates SLF4J MDC from the active OpenTelemetry span for request-thread logs.
 *
 * This runs after Spring's HTTP observation filter so controller/service logs can
 * render traceId/spanId in the console pattern even when Micrometer does not
 * bridge them into MDC automatically.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        SpanContext spanContext = Span.current().getSpanContext();
        boolean mdcApplied = false;

        if (spanContext.isValid()) {
            MDC.put("traceId", spanContext.getTraceId());
            MDC.put("spanId", spanContext.getSpanId());
            mdcApplied = true;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (mdcApplied) {
                MDC.remove("traceId");
                MDC.remove("spanId");
            }
        }
    }
}
