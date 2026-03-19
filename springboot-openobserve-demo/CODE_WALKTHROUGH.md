# Code Walkthrough — SpringBoot + OpenObserve

This document explains the code structure, the reasoning behind each decision,
and the tradeoffs considered. Use this as a reference while recording the video.

---

## Part 1: The App Code

### `OrderController.java`

**What it is:**
A standard Spring REST controller with 4 endpoints — fetch order, create order,
simulate error, and health check.

**Why this structure:**
The controller is intentionally kept thin. It only handles HTTP concerns:
parsing the request, calling the service, returning a response. All business
logic lives in `OrderService`. This separation matters for observability because
traces will clearly show the HTTP layer and the business layer as distinct spans.

**Why `log.info` at the controller level:**
Logging at entry and exit of each endpoint gives you a breadcrumb trail in
OpenObserve. When something goes wrong you can filter logs by endpoint and
immediately see whether the request even reached the controller, or failed deeper.

**Why the `simulate-error` endpoint:**
Real errors are unpredictable. Having a deterministic way to trigger an error
lets you demonstrate error traces and ERROR-level logs in the dashboard without
waiting for something to break naturally. It also shows that the observability
setup captures exceptions — not just happy-path requests.

**Tradeoff:**
Using `Map<String, Object>` as request/response types instead of proper DTOs
keeps the demo code shorter and easier to follow. In production you'd define
typed request/response objects.

---

### `OrderService.java`

**What it is:**
The business logic layer. Simulates database reads and writes using `Thread.sleep`
to introduce realistic latency.

**Why `@Observed` instead of manual tracing:**
There are two ways to create spans in Spring:
1. Manual: inject a `Tracer` bean, call `tracer.spanBuilder(...).startSpan()`, wrap logic in try/finally
2. Declarative: annotate the method with `@Observed`

`@Observed` is the right choice here because:
- It keeps business logic clean — no OTel imports in your service classes
- It's AOP-based, so the span lifecycle is managed automatically
- The span is automatically a child of the active HTTP request span

The tradeoff is that `@Observed` gives you less control — you can't add custom
attributes to the span or record exceptions manually. For a demo showing the
fundamentals, this is the right level of abstraction.

**Why `simulateProcessing(long millis)`:**
Without artificial latency, all spans would complete in under 1ms and the trace
waterfall would look uninteresting. The sleep makes the span durations meaningful
and shows how trace data helps you identify slow operations.

**Why `simulateError()` throws `RuntimeException`:**
An unchecked exception propagates up through Spring's exception handling, gets
recorded on the active span as an error, and triggers an ERROR log — giving you
all three signals (span status, log level, exception message) in one shot.

---

## Part 2: Observability Setup

### `application.yml` — Configuration

**What it is:**
All OpenObserve connection details are externalized here. The app reads these
at startup via `@Value` in `OpenTelemetryConfig`.

**Why externalize config:**
Hardcoding the endpoint or credentials in Java code would mean rebuilding the
app to switch between self-hosted and cloud OpenObserve. With `application.yml`
you change one line and restart.

**Why `organization: default`:**
OpenObserve is multi-tenant. Every API call must specify which organization's
data it belongs to. `default` is the organization created automatically on
first startup of self-hosted OpenObserve. On OpenObserve Cloud, this will be
your account's org name visible in the dashboard URL.

**Why `allow-circular-references: true`:**
Spring Boot 3.x ships with its own OpenTelemetry auto-configuration. When we
define a custom `OpenTelemetry` bean, Spring's auto-config tries to use it, but
our `@PostConstruct` also references the same bean — creating a dependency cycle.
Setting `allow-circular-references: true` tells Spring to resolve it rather than
fail. The cleaner long-term fix would be to use Spring Boot's native OTLP
properties instead of a custom config class, but for a self-contained demo this
keeps all OTel setup visible in one place.

**Why `sampling.probability: 1.0`:**
In production you'd sample 10-20% of traces to control storage costs. For a
demo you want every single request to appear in OpenObserve so nothing is missed
during the walkthrough.

---

### `OpenTelemetryConfig.java` — The OTel SDK

**What it is:**
The single class that wires the entire observability pipeline. It creates the
OpenTelemetry SDK configured to export traces and logs to OpenObserve.

**Why a custom config class instead of Spring Boot auto-config:**
Spring Boot 3.2 can auto-configure OTLP tracing via `management.otlp.*`
properties. We chose a custom config class because:
- It makes the full setup explicit and visible in one file
- It's easier to explain in a tutorial — no magic happening behind the scenes
- It handles both traces AND logs in one place (Spring Boot's auto-config
  doesn't cover log export out of the box)

The tradeoff is the circular dependency issue described above.

**Why `Resource`:**
A Resource is metadata that gets attached to every span and log record exported
from this app. Without it, you'd see telemetry in OpenObserve but wouldn't know
which service, version, or environment it came from. With it, you can filter
the entire dashboard to a single service.

**Why `BatchSpanProcessor` / `BatchLogRecordProcessor`:**
The alternative is `SimpleSpanProcessor` which sends each span synchronously,
one at a time, on the thread that created it. This would add network latency to
every request. The batch processor buffers signals and sends them in bulk on a
background thread — no impact on request latency.

**Why the same endpoint for traces and logs:**
OpenObserve exposes a single OTLP endpoint that accepts both. This is one of
its strengths — you don't need separate pipelines or sidecar collectors for
different signal types.

**Why `buildAndRegisterGlobal()`:**
The Logback OTel appender looks for the globally registered OpenTelemetry
instance at runtime. If we didn't register globally, the appender wouldn't
know which SDK instance to use and logs would never be exported.

---

### `logback-spring.xml` — The Log Pipeline

**What it is:**
Configures Logback (Spring Boot's default logging framework) with two appenders:
one for the console and one for OpenObserve.

**Why Logback instead of Log4j2 or JUL:**
Spring Boot defaults to Logback. Switching logging frameworks would add
unnecessary complexity. The OTel instrumentation library has a first-class
Logback appender (`opentelemetry-logback-appender-1.0`) so there's no benefit
to switching.

**Why the `traceId=%X{traceId} spanId=%X{spanId}` pattern in the console appender:**
The `%X{}` syntax reads from MDC (Mapped Diagnostic Context). Micrometer
automatically populates MDC with the active trace and span IDs. This means every
log line printed to your terminal carries the exact same `traceId` as the trace
in OpenObserve. You can copy a `traceId` from the terminal and paste it directly
into the Traces search.

**Why `captureMdcAttributes=*`:**
This tells the OTel appender to include all MDC fields in the exported log
record — including `traceId` and `spanId`. Without this, the log records would
arrive in OpenObserve without trace context, and log-to-trace correlation would
not work.

**Why `captureExperimentalAttributes=true`:**
Adds extra attributes to each log record: the thread name, logger name, and
code location. Useful for filtering in OpenObserve without having to parse the
log body text.

**Why `com.example.demo` logger at DEBUG:**
Framework logs (Spring, Tomcat, etc.) at DEBUG are very noisy. Setting only
the application package to DEBUG gives you detailed logs from your own code
without drowning in framework internals.

---

## Part 3: How Observability Is Wired Into the App

This is the key insight of the entire demo.

### Traces → automatic via HTTP filter + `@Observed`

When a request hits `GET /api/orders/123`:

1. Spring Boot's `WebMvcObservationFilter` (auto-configured via Micrometer)
   intercepts the request and starts a root span: `GET /api/orders/{orderId}`
2. The request reaches `OrderController.getOrder()`
3. The controller calls `orderService.getOrder(orderId)`
4. Because `getOrder` is annotated with `@Observed`, AOP intercepts the call
   and starts a child span: `fetching-order`
5. When the method returns, the child span is closed
6. When the HTTP response is sent, the root span is closed
7. Both spans are flushed to OpenObserve by `BatchSpanProcessor`

**Result:** A trace with two spans — the HTTP span and the service span —
showing the full request journey and duration breakdown.

### Logs → automatic via Logback appender

When `log.info("Fetching order from database: {}", orderId)` executes:

1. Logback routes the log event to all configured appenders
2. The `CONSOLE` appender prints it to terminal with `traceId` from MDC
3. The `OpenTelemetry` appender converts the log event to an OTel log record,
   attaches the active `traceId` and `spanId`, and hands it to the SDK
4. `BatchLogRecordProcessor` buffers it and sends it to OpenObserve

**Result:** Every log line in OpenObserve has a `traceId` field. Clicking it
takes you directly to the trace. No code changes needed in `OrderService` or
`OrderController` to make this happen.

### The key point

Neither `OrderController` nor `OrderService` imports anything from OpenTelemetry.
The entire observability pipeline is configured in three files:
- `OpenTelemetryConfig.java` — SDK setup
- `logback-spring.xml` — log forwarding
- `application.yml` — connection details

This is the correct pattern for production observability: instrumentation should
be a cross-cutting concern, not scattered through business logic.

---

## Tradeoffs Summary

| Decision | Chosen Approach | Alternative | Why |
|----------|----------------|-------------|-----|
| Span creation | `@Observed` annotation | Manual `Tracer` API | Keeps business code clean; sufficient for standard use cases |
| Log forwarding | Logback OTel appender | OTel Java agent | No agent process needed; simpler setup for demos |
| OTel config | Custom `@Configuration` class | Spring Boot OTLP auto-config | All setup visible in one file; easier to explain |
| Sampling | 100% (`probability: 1.0`) | 10-20% | Demo needs every request visible; lower in production |
| Response types | `Map<String, Object>` | Typed DTOs | Shorter code; easier to follow in a tutorial |
| Signal transport | gRPC (OTLP/gRPC) | HTTP/protobuf or HTTP/JSON | gRPC is more efficient; default for OTLP |

---

## Part 4: How This Would Look in Python or Node.js

The observability concepts are identical across languages — Resource, spans,
log correlation, OTLP export. What changes is the tooling and how much is
automatic vs. manual.

---

### Python (Flask / FastAPI)

**Traces:**
Python uses the `opentelemetry-sdk` package with framework-specific
instrumentation packages:

```python
# pip install opentelemetry-sdk opentelemetry-exporter-otlp opentelemetry-instrumentation-fastapi

from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor

provider = TracerProvider()
provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(
    endpoint="http://localhost:5081",
    headers={"Authorization": "Basic ...", "organization": "default"}
)))
trace.set_tracer_provider(provider)

# Auto-instrument FastAPI — equivalent to Spring's WebMvcObservationFilter
FastAPIInstrumentor.instrument_app(app)
```

**Logs:**
Python's standard `logging` module doesn't have an OTel appender equivalent.
You have two options:

Option A — OTel log handler (direct equivalent of our Logback appender):
```python
from opentelemetry.sdk._logs import LoggerProvider
from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
from opentelemetry.exporter.otlp.proto.grpc._log_exporter import OTLPLogExporter
from opentelemetry.instrumentation.logging import LoggingInstrumentor

# Inject traceId/spanId into Python log records
LoggingInstrumentor().instrument(set_logging_format=True)
```

Option B — structured logging with a library like `structlog` that emits JSON,
then ship logs via a collector (Fluentd, Vector) to OpenObserve. More moving
parts but common in production Python deployments.

**Equivalent of `@Observed`:**
Python doesn't have AOP. You use a context manager or decorator manually:
```python
tracer = trace.get_tracer(__name__)

def get_order(order_id: str):
    with tracer.start_as_current_span("order.fetch"):
        # your logic here
```
Or use the `opentelemetry-instrumentation-sqlalchemy` package if you want DB
spans automatically — similar in spirit to `@Observed` but library-specific.

**Key difference from Java:**
In Java, `@Observed` + AOP means zero OTel imports in business logic. In Python
you either import the tracer in every function or write a custom decorator — the
business code is slightly more aware of observability.

---

### Node.js (Express / Fastify)

**Traces:**
Node.js OTel setup is similar to Python but uses the `@opentelemetry` npm packages:

```javascript
// npm install @opentelemetry/sdk-node @opentelemetry/exporter-trace-otlp-grpc
// @opentelemetry/instrumentation-express @opentelemetry/auto-instrumentations-node

const { NodeSDK } = require('@opentelemetry/sdk-node');
const { OTLPTraceExporter } = require('@opentelemetry/exporter-trace-otlp-grpc');
const { getNodeAutoInstrumentations } = require('@opentelemetry/auto-instrumentations-node');

const sdk = new NodeSDK({
  traceExporter: new OTLPTraceExporter({
    url: 'http://localhost:5081',
    metadata: { authorization: 'Basic ...', organization: 'default' }
  }),
  instrumentations: [getNodeAutoInstrumentations()] // auto-instruments Express, HTTP, etc.
});

sdk.start(); // must run BEFORE requiring express
```

**Important:** The SDK must be initialized before any other `require()` calls.
This is usually done in a separate `tracing.js` file loaded with
`node -r ./tracing.js app.js`. This is more fragile than Java/Spring where
the `@Configuration` class is loaded in the right order automatically.

**Logs:**
Node.js has no standard logging framework with an OTel appender. Common
approaches:

Option A — Winston with OTel transport:
```javascript
const { WinstonInstrumentation } = require('@opentelemetry/instrumentation-winston');
// Automatically injects traceId/spanId into Winston log records
```

Option B — Pino logger with `pino-opentelemetry-transport`

Both inject `traceId` into log records, achieving the same correlation we get
from `captureMdcAttributes` in the Logback appender.

**Equivalent of `@Observed`:**
No decorator equivalent. You either use `getNodeAutoInstrumentations()` which
auto-instruments popular libraries (Express routes, HTTP calls, DB queries), or
create spans manually:
```javascript
const tracer = trace.getTracer('order-service');

async function getOrder(orderId) {
  return tracer.startActiveSpan('order.fetch', async (span) => {
    try {
      // your logic
    } finally {
      span.end();
    }
  });
}
```

---

### Side-by-Side Comparison

| | Java (Spring Boot) | Python (FastAPI) | Node.js (Express) |
|---|---|---|---|
| HTTP auto-instrumentation | `micrometer-tracing-bridge-otel` (auto-config) | `FastAPIInstrumentor.instrument_app()` | `getNodeAutoInstrumentations()` |
| Method-level spans | `@Observed` annotation | Manual `with tracer.start_as_current_span()` or custom decorator | Manual `startActiveSpan()` |
| Log forwarding | Logback OTel appender (zero code change) | `LoggingInstrumentor` or structlog | Winston/Pino OTel transport |
| traceId in logs | Automatic via MDC + `captureMdcAttributes` | `LoggingInstrumentor(set_logging_format=True)` | Winston instrumentation |
| SDK initialization | Spring `@Configuration` bean (lifecycle managed) | Module-level setup at app startup | Separate `tracing.js`, must load first |
| Business code awareness of OTel | None (`@Observed` is the only import) | Low-medium (tracer imports in service layer) | Low-medium (tracer imports in service layer) |
| Circular dependency risk | Yes (Spring auto-config conflict) | No | No |

**The core difference:** Spring's dependency injection and AOP make it possible
to keep all OTel code out of business logic entirely. In Python and Node.js,
auto-instrumentation handles the HTTP layer well, but method-level spans
require the service code to be OTel-aware. OpenObserve receives the same data
regardless — the difference is only in how clean the application code stays.
