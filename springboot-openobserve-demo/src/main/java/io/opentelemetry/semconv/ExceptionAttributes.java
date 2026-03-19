package io.opentelemetry.semconv;

import io.opentelemetry.api.common.AttributeKey;

/**
 * Compatibility shim for the OpenTelemetry Logback appender.
 *
 * The appender version used by this demo still references the older
 * io.opentelemetry.semconv.ExceptionAttributes type. Newer semconv artifacts
 * no longer ship that class, so we provide the three attribute keys it needs.
 */
public final class ExceptionAttributes {

    public static final AttributeKey<String> EXCEPTION_TYPE =
            AttributeKey.stringKey("exception.type");

    public static final AttributeKey<String> EXCEPTION_MESSAGE =
            AttributeKey.stringKey("exception.message");

    public static final AttributeKey<String> EXCEPTION_STACKTRACE =
            AttributeKey.stringKey("exception.stacktrace");

    private ExceptionAttributes() {
    }
}
