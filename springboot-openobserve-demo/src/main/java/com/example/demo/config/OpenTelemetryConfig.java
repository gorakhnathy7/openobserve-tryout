package com.example.demo.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ResourceAttributes;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures OpenTelemetry to export both traces and logs to OpenObserve
 * via the OTLP (OpenTelemetry Protocol) gRPC exporter.
 *
 * Beans are split into Resource → SdkTracerProvider → SdkLoggerProvider → OpenTelemetry
 * so that Spring Boot's OpenTelemetryAutoConfiguration sees our beans and backs off
 * (ConditionalOnMissingBean). Micrometer's OtelTracer then uses our SdkTracerProvider,
 * populating MDC with traceId/spanId on every request via Slf4JSimpleMDCScopeDecorator.
 */
@Configuration
public class OpenTelemetryConfig {

    @Value("${openobserve.otlp.endpoint:http://localhost:5081}")
    private String otlpEndpoint;

    @Value("${openobserve.otlp.auth-header:}")
    private String authHeader;

    @Value("${openobserve.otlp.organization:default}")
    private String organization;

    @Value("${spring.application.name:springboot-demo}")
    private String serviceName;

    /**
     * Resource metadata attached to every span and log record.
     * Exposed as a bean so Spring Boot's auto-configuration can also use it.
     */
    @Bean
    public Resource otelResource() {
        return Resource.getDefault()
                .merge(Resource.create(Attributes.of(
                        ResourceAttributes.SERVICE_NAME, serviceName,
                        ResourceAttributes.SERVICE_VERSION, "1.0.0",
                        ResourceAttributes.DEPLOYMENT_ENVIRONMENT, "demo"
                )));
    }

    /**
     * Trace exporter + processor.
     * Exposed as a Spring bean so Spring Boot's OpenTelemetryAutoConfiguration
     * sees it and skips creating its own — ensuring Micrometer uses this same
     * provider (and its OTLP exporter) when creating spans.
     */
    @Bean
    public SdkTracerProvider sdkTracerProvider() {
        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .addHeader("Authorization", authHeader)
                .addHeader("organization", organization)
                .build();

        return SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .setResource(otelResource())
                .build();
    }

    /**
     * Log exporter + processor.
     * Handles forwarding every Logback log record to OpenObserve via OTLP gRPC.
     * BatchLogRecordProcessor buffers records and sends them in bulk on a
     * background thread — no latency impact on request handling.
     */
    @Bean
    public SdkLoggerProvider sdkLoggerProvider() {
        OtlpGrpcLogRecordExporter logExporter = OtlpGrpcLogRecordExporter.builder()
                .setEndpoint(otlpEndpoint)
                .addHeader("Authorization", authHeader)
                .addHeader("organization", organization)
                .build();

        return SdkLoggerProvider.builder()
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter).build())
                .setResource(otelResource())
                .build();
    }

    /**
     * The root OpenTelemetry SDK instance.
     * Uses build() (not buildAndRegisterGlobal()) to avoid conflicting with
     * Spring Boot's context propagation setup. The Logback appender is installed
     * explicitly here instead of relying on the global registry.
     */
    @Bean
    public OpenTelemetry openTelemetry() {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProvider())
                .setLoggerProvider(sdkLoggerProvider())
                .setPropagators(ContextPropagators.create(
                        TextMapPropagator.composite(
                                W3CTraceContextPropagator.getInstance(),
                                W3CBaggagePropagator.getInstance()
                        )
                ))
                .build();
        OpenTelemetryAppender.install(sdk);
        return sdk;
    }
}
