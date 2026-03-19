# Monitoring Spring Boot Applications: Logs and Traces with OpenObserve

Monitoring a microservice is easy when everything works. The challenge is what happens when it doesn't.

A user reports a checkout failure. You open your logs and see thousands of lines from a dozen services. The error is in there somewhere, but which log line? Which service? Which request? Without a way to connect a log entry to the exact request that triggered it, you're searching in the dark.

This tutorial shows you how to fix that. By the end, we'll have a Spring Boot application that sends both logs and traces to OpenObserve, with full correlation between them. We'll be able to take a trace ID from a request log, search for it in OpenObserve, and inspect the exact span waterfall for that request.

Here's what we'll build:
- A Spring Boot 3 REST API with realistic business logic, an order management service
- Traces flowing into OpenObserve via the OpenTelemetry SDK and OTLP/gRPC
- Logs forwarded via the OTel Logback appender, through the same OTLP endpoint
- `@Observed` annotation on service methods with zero OTel code in business logic
- A load test script to generate traffic and errors on demand

The full source code lives in the companion repository for this tutorial.

---

## What is OpenObserve?

OpenObserve is an open-source observability platform built for logs, metrics, and traces. Written in Rust, it's designed to be storage-efficient and operationally simple. A single binary gets you a working instance with no JVM and no external database. It accepts telemetry natively via OTLP, which means any OpenTelemetry-instrumented application can send data to it without vendor-specific SDKs.

What makes it compelling for this tutorial specifically is that logs and traces live in the same product, and they carry shared identifiers. When a log record contains the same trace ID as a trace, we can move from one signal to the other without guessing across timestamps.

---

## Architecture

Here's how signals flow from the application to OpenObserve:

```
Spring Boot App
├── HTTP requests -> Spring Web MVC
│   └── Spring Observations + micrometer-tracing-bridge-otel
│       └── spans -> SdkTracerProvider + OtlpGrpcSpanExporter -> OpenObserve :5081
│
└── log.info() / log.error() -> Logback
    └── OpenTelemetry Logback Appender
        └── log records -> SdkLoggerProvider + OtlpGrpcLogRecordExporter -> OpenObserve :5081
```

Both signals share the same OTLP endpoint and carry the same trace ID. That shared identifier is the key to correlation.

| Component | Technology |
|---|---|
| Traces | OpenTelemetry SDK → OTLP/gRPC → OpenObserve |
| Logs | Logback → OTel Logback Appender → OTLP/gRPC → OpenObserve |
| Span creation | Micrometer `@Observed` annotation, AOP-based |
| HTTP tracing | Spring Boot auto-configuration via `micrometer-tracing-bridge-otel` |

---

## Prerequisites

- Java 17+
- Maven 3.8+
- Docker, optional - OpenObserve can also run as a standalone binary

---

## Step 1: Set Up OpenObserve

Before we write any application code, we need somewhere to send telemetry. OpenObserve is the backend that will receive and store our spans and log records, and provide the UI we'll use to query and correlate them.

OpenObserve ships as a single self-contained binary. It stores data on local disk by default, which makes local development simple. Pick whichever option fits your machine.

**Option A: Binary (Linux/macOS)**
```bash
curl -L https://raw.githubusercontent.com/openobserve/openobserve/main/download.sh | sh
chmod +x openobserve

ZO_ROOT_USER_EMAIL="root@example.com" \
ZO_ROOT_USER_PASSWORD="Complexpass#123" \
./openobserve
```

**Option B: Binary (Windows)**

Download the latest release from the OpenObserve releases page, then run:
```powershell
$env:ZO_ROOT_USER_EMAIL="root@example.com"
$env:ZO_ROOT_USER_PASSWORD="Complexpass#123"
.\openobserve.exe
```

If you're on older Linux distributions and see a GLIBC compatibility error, use the musl binary from the releases page instead.

**Option C: Docker**
```bash
docker run -d \
  --name openobserve \
  -p 5080:5080 \
  -p 5081:5081 \
  -e ZO_ROOT_USER_EMAIL=root@example.com \
  -e ZO_ROOT_USER_PASSWORD=Complexpass#123 \
  public.ecr.aws/zinclabs/openobserve:latest
```

Whichever option you chose, OpenObserve is now running on two ports:
- **Port 5080:** the dashboard UI and REST API
- **Port 5081:** OTLP ingestion over gRPC - where our Spring Boot app will send data

Open http://localhost:5080 and log in with `root@example.com` / `Complexpass#123`.

![OpenObserve dashboard home page after logging in, showing the Logs, Traces, Metrics and other navigation options in the sidebar](<Screenshot 2026-03-19 at 11.04.34 PM-1-1.png>)
*OpenObserve running locally on port 5080 - the home dashboard after first login*


**Get your auth header.**

In the OpenObserve UI, click **Data Sources** in the left sidebar, then go to **Recommended > Traces (OpenTelemetry)**. The OTLP gRPC section shows a ready-to-use config snippet with the `Authorization` header value already filled in. Copy that value - it's a Base64-encoded `email:password` string - and keep it handy for `application.yml` in Step 4.

![OpenObserve Data Sources page showing Traces (OpenTelemetry) selected, with OTLP gRPC config displaying the Authorization header and organization fields](<Screenshot 2026-03-20 at 12.58.28 AM.png>)
*OpenObserve local instance - Data Sources > Recommended > Traces (OpenTelemetry). Copy the Authorization value from the OTLP gRPC section into application.yml*

---

## Step 2: Clone the Project

Now that OpenObserve is running, we need the Spring Boot application that will send it data.

```bash
git clone https://github.com/gorakhnathy7/openobserve-tryout
cd springboot-openobserve-demo
```

### What the app does

The demo is a Spring Boot REST API simulating an order management service. The business logic is intentionally simple because the point is not the domain model - it's to generate clean, explainable logs and traces we can inspect end to end.

| Method | Path | What it does |
|---|---|---|
| GET | `/api/orders/{orderId}` | Fetches an order with a simulated 50ms read |
| POST | `/api/orders` | Creates an order with a simulated 100ms write |
| GET | `/api/orders/simulate-error` | Deliberately throws a runtime exception |
| GET | `/api/orders/health` | Simple health check |
| GET | `/actuator/health` | Spring Boot Actuator health endpoint |

The simulated delays in the fetch and create flows are deliberate. They make the spans visible and easy to interpret in the trace waterfall instead of collapsing into near-instant operations. The `simulate-error` endpoint serves a similar purpose for failures: it gives us a repeatable way to generate an error and inspect its logs, trace, and correlation flow end to end.

### Project structure

```
springboot-openobserve-demo/
├── pom.xml
├── load-test.sh
└── src/main/
    ├── java/com/example/demo/
    │   ├── DemoApplication.java
    │   ├── config/
    │   │   ├── OpenTelemetryConfig.java
    │   │   └── TraceMdcFilter.java
    │   ├── controller/
    │   │   └── OrderController.java
    │   └── service/
    │       └── OrderService.java
    └── resources/
        ├── application.yml
        └── logback-spring.xml
```

The split between `OpenTelemetryConfig.java` and the rest of the application is intentional. All OpenTelemetry setup lives in configuration, while `OrderController` and `OrderService` remain ordinary Spring components using standard SLF4J logging. That separation is a core idea in this demo: observability is added around the business logic, not mixed into it.

---

## Step 3: Maven Dependencies

Before the app can send telemetry, it needs the right libraries on the classpath. The stack is layered by design. Spring Boot doesn't directly know about OpenObserve, OpenTelemetry doesn't directly know about Logback, and `@Observed` needs AOP support to create child spans around service methods.

Here are the important dependency groups in this project:

```xml
<!-- 1. Spring Boot Web + Actuator -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<!-- 2. Micrometer -> OTel bridge -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>

<!-- 3. OTel SDK + OTLP exporter -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>

<!-- 4. Semantic conventions used by the log appender -->
<dependency>
    <groupId>io.opentelemetry.semconv</groupId>
    <artifactId>opentelemetry-semconv-incubating</artifactId>
    <version>1.25.0-alpha</version>
</dependency>

<!-- 5. OTel Logback appender -->
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-logback-appender-1.0</artifactId>
    <version>2.3.0-alpha</version>
</dependency>

<!-- 6. AOP support for @Observed -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

The important thing to notice is the division of labor:
- Spring Boot gives us the web app and Actuator
- Micrometer bridges Spring's Observation API to OpenTelemetry
- The OTel SDK and OTLP exporter send traces and logs to OpenObserve
- The Logback appender forwards structured log records into the OTel log pipeline
- AOP makes `@Observed` actually create method-level spans

---

## Step 4: Configure application.yml

With dependencies in place, we now need to tell the application where OpenObserve is and how to authenticate with it.

```yaml
spring:
  application:
    name: springboot-openobserve-demo

server:
  port: 8080

openobserve:
  otlp:
    endpoint: http://localhost:5081
    auth-header: "Basic YOUR_BASE64_TOKEN_HERE"
    organization: default

management:
  tracing:
    sampling:
      probability: 1.0

logging:
  level:
    root: INFO
    com.example.demo: DEBUG
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - traceId=%X{traceId} spanId=%X{spanId} - %msg%n"
```

Three settings matter most here:
- **`endpoint: http://localhost:5081`** - this is the OTLP gRPC ingestion port, not the UI port
- **`auth-header`** - the Basic auth header OpenObserve expects on OTLP requests
- **`probability: 1.0`** - sample every request while we're building and validating the setup

> **Don't have the auth token yet?** Go to OpenObserve at http://localhost:5080, click **Data Sources** in the left sidebar, then **Recommended > Traces (OpenTelemetry)**. Copy the `Authorization` value from the OTLP gRPC section and paste it here.

**Why 5081 and not 5080?** Port 5080 serves the dashboard UI. Port 5081 is the OTLP gRPC ingestion port. If telemetry goes to the UI port, it won't show up in Logs or Traces.

**Why `probability: 1.0`?** In a local tutorial, partial sampling makes verification harder. We want every request to produce a trace while we're wiring the system up.

---

## Step 5: The OpenTelemetry Configuration

This is the most important file in the project. `OpenTelemetryConfig.java` is where we construct the exporters for traces and logs, wire them into the SDK, set resource metadata, and attach the Logback appender to the SDK instance. This is the one file where observability becomes real. Everything else in the app just logs and handles requests normally.

The reason we need a custom configuration class rather than relying entirely on Spring Boot's auto-configuration is control. Spring Boot can auto-configure an OTel exporter, but it has no knowledge of OpenObserve's required `Authorization` and `organization` headers, and no way to install the Logback appender.

By defining our own `SdkTracerProvider` bean, Spring Boot's `@ConditionalOnMissingBean` backs off and uses ours instead - so we get full control without disabling auto-configuration entirely.

```java
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

    @Bean
    public Resource otelResource() {
        return Resource.getDefault()
                .merge(Resource.create(Attributes.of(
                        ResourceAttributes.SERVICE_NAME, serviceName,
                        ResourceAttributes.SERVICE_VERSION, "1.0.0",
                        ResourceAttributes.DEPLOYMENT_ENVIRONMENT, "demo"
                )));
    }

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
```

Two design choices here are worth calling out:

`BatchSpanProcessor` and `BatchLogRecordProcessor` buffer signals and export on a background thread. That keeps telemetry export off the request thread.

The app uses `build()` and installs the Logback appender directly against the SDK instance. In this project, that turned out to be the stable path.

---

## Step 6: Logback Configuration

The previous step built the OTel SDK and attached the exporter pipelines. Now we need Logback to send application log records into that SDK.

`logback-spring.xml` sets up two appenders: one that prints to the console for local development, and one that forwards every log record to OpenObserve through the OTel appender.

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - traceId=%X{traceId} spanId=%X{spanId} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="OpenTelemetry"
              class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
        <captureExperimentalAttributes>true</captureExperimentalAttributes>
        <captureKeyValuePairAttributes>true</captureKeyValuePairAttributes>
        <captureLoggerContext>true</captureLoggerContext>
        <captureMdcAttributes>*</captureMdcAttributes>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="OpenTelemetry"/>
    </root>

    <logger name="com.example.demo" level="DEBUG" additivity="false">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="OpenTelemetry"/>
    </logger>
</configuration>
```

`captureMdcAttributes=*` is the critical setting here. It tells the appender to attach MDC values - including `traceId` and `spanId` - to each exported log record. That is what makes log-trace correlation possible in OpenObserve.

---

## Step 7: The Application Code

With the telemetry pipeline in place, we can now look at the service and controller. The key thing to notice is what's absent: neither file imports the OTel tracer API or manually manages span lifecycle.

### OrderService.java

The service uses `@Observed` from Micrometer to declare which methods should become child spans. Logging is plain SLF4J.

```java
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    @Observed(name = "order.fetch", contextualName = "fetching-order")
    public Map<String, Object> getOrder(String orderId) {
        log.info("Fetching order from database: {}", orderId);
        simulateProcessing(50);

        Map<String, Object> order = new HashMap<>();
        order.put("orderId", orderId);
        order.put("productId", "PROD-001");
        order.put("quantity", 2);
        order.put("status", "CONFIRMED");
        order.put("totalAmount", 99.99);

        log.debug("Order details: {}", order);
        return order;
    }

    @Observed(name = "order.create", contextualName = "creating-order")
    public Map<String, Object> createOrder(Map<String, Object> request) {
        String newOrderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("Creating new order with id: {}", newOrderId);
        simulateProcessing(100);

        Map<String, Object> order = new HashMap<>(request);
        order.put("orderId", newOrderId);
        order.put("status", "PENDING");
        order.put("createdAt", System.currentTimeMillis());

        log.info("Order {} created and saved to database", newOrderId);
        return order;
    }

    @Observed(name = "order.error", contextualName = "simulating-order-error")
    public void simulateError() {
        log.warn("About to throw a simulated exception");
        simulateProcessing(30);
        throw new RuntimeException("Simulated database connection timeout");
    }
}
```

That last method matters. `simulateError()` is observed and throws uncaught, which means the failed request shows up as a real error path in tracing.

### OrderController.java

The controller is pure Spring REST. It delegates to the service, logs at useful points, and lets Spring handle the uncaught error path for `/simulate-error`.

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Map<String, Object>> getOrder(@PathVariable String orderId) {
        log.info("Received request to fetch order: {}", orderId);
        Map<String, Object> order = orderService.getOrder(orderId);
        log.info("Successfully fetched order: {}", orderId);
        return ResponseEntity.ok(order);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody Map<String, Object> request) {
        log.info("Received request to create order for product: {}", request.get("productId"));
        Map<String, Object> order = orderService.createOrder(request);
        log.info("Order created successfully with id: {}", order.get("orderId"));
        return ResponseEntity.ok(order);
    }

    @GetMapping("/simulate-error")
    public ResponseEntity<String> simulateError() {
        log.warn("Simulating an error scenario for demonstration");
        orderService.simulateError();
        return ResponseEntity.ok("No error");
    }
}
```

The important point is that the controller doesn't catch and convert the exception anymore. Letting it propagate is what makes the request trace show up as a real failure instead of a handled success. In practice, the final `return` is never reached because `simulateError()` throws and Spring converts that uncaught exception into a 500.

---

## Step 8: Run the Application

Everything is wired up. Start the application:

```bash
mvn clean spring-boot:run
```

Watch the startup output. Once the app is ready, make your first request:

```bash
curl http://localhost:8080/api/orders/ORD-001
```

Check the console — you should see populated `traceId` and `spanId` values in the log lines for that request:

```
2026-03-19 23:12:24.516 [http-nio-8080-exec-1] INFO  c.e.demo.controller.OrderController - traceId=3c9271ea42e8dfd04009e0552f60cc39 spanId=255ebb948f91765e - Received request to fetch order: ORD-17455
```

![Terminal showing Spring Boot console output with populated traceId and spanId on a live request log line](<Screenshot 2026-03-20 at 1.38.37 AM-1.png>)
*Terminal output after the first curl request - traceId and spanId are populated in every request-thread log line*

If `traceId=` still shows up blank in request logs, the most likely issues are:
- `management.tracing.sampling.probability=1.0` is missing
- `micrometer-tracing-bridge-otel` is missing from the classpath
- MDC is not being populated for request-thread logs

This project solves the third problem with a small servlet filter:

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceMdcFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
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
```

---

## Step 9: Generate Traffic

The application is running, but OpenObserve won't show anything until it actually receives telemetry. We need to make requests.

Fetch an order:
```bash
curl http://localhost:8080/api/orders/ORD-001
```

Create an order:
```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"productId": "PROD-001", "quantity": 2, "customerId": "CUST-100"}'
```

Trigger a deliberate error:
```bash
curl http://localhost:8080/api/orders/simulate-error
```

Or use the load test script for sustained traffic:
```bash
bash load-test.sh
```

That gives us a realistic mix of successful and failed traces instead of a handful of isolated requests.

The batch processors hold signals briefly before exporting, so wait a few seconds after your first requests before switching over to OpenObserve.

---

## Step 10: View Logs in OpenObserve

Open http://localhost:5080 and click **Logs** in the sidebar.

This is where your application's log records are landing. Each log record has been enriched by the OTel Logback appender with fields beyond the raw message. Because we set `<captureMdcAttributes>*</captureMdcAttributes>` in `logback-spring.xml`, every record arrives with the trace context of the request that produced it, plus the resource attributes from `OpenTelemetryConfig.java`.

You should see records with fields like:
- `body`: the log message text
- `severity`: INFO, WARN, ERROR
- `trace_id`
- `service_name`, `service_version`, `deployment_environment`

To isolate a single request, use the Query Editor and search by trace ID:
```sql
trace_id = 'your_trace_id_here'
```

In this demo, search using the `trace_id` field exposed in OpenObserve's query editor. For the error path, searching by trace ID is more reliable than relying on a severity-only filter.

![OpenObserve Logs view with a trace_id filter applied, showing all structured log records for a single request](<Screenshot 2026-03-20 at 1.53.10 AM-1.png>)
*Logs view filtered by trace_id - every log line emitted during that request, with severity, body, and trace context fields visible*

---

## Step 11: View Traces in OpenObserve

Click **Traces** in the sidebar.

The Trace Explorer lists incoming traces. Each row represents one complete request, from the moment it arrived at the HTTP layer to the moment the response was sent. You can see the root operation name, which service handled it, the total duration, how many spans it contains, and whether it succeeded or failed.

Click into a trace from a `GET /api/orders/{orderId}` request. We should see a waterfall like this:

```
http get /api/orders/{orderId}    [~55ms]
  └── order.fetch                 [~50ms]
```

The indentation shows the parent-child relationship. The HTTP span is the root, created automatically for the inbound request. `order.fetch` is the child span, created by `@Observed`.

Now click into a trace from `/simulate-error`. In the working implementation, that request now returns a real uncaught 500, and the trace should show the failure rather than a handled success. If you generated traces before making this code change, older `/simulate-error` traces may still show up as success, so inspect the newest traces only.

![OpenObserve trace waterfall showing the root HTTP span and the nested order.fetch child span for a GET request](image-1.png)
*Trace waterfall for GET /api/orders/{orderId} - the HTTP root span wraps the order.fetch child span created by @Observed*

![OpenObserve trace waterfall for /api/orders/simulate-error showing a failed trace with error status on the root span](image-2.png)
*Failed trace for /api/orders/simulate-error - the uncaught exception surfaces as an error status on the span, visible in the waterfall*

---

## Step 12: Log-Trace Correlation

This is where the two signals come together and the setup pays off.

Until now, logs and traces have been two separate things you look at independently. Correlation is what makes them a single unified view of a request.

**From a log to its trace**
1. In the Logs view, copy a `trace_id` from a log record
2. Switch to Traces
3. Search or filter for that same trace ID
4. Open the matching trace and inspect the waterfall

**From a trace to its logs**
1. In the Traces view, open a trace and copy its trace ID
2. Switch to Logs
3. Search: `trace_id = 'the_trace_id_you_copied'`
4. Every log line emitted during that request appears in chronological order

That second direction is the one that changes how you debug. The trace tells you which request failed and where. The correlated logs tell you what your code was doing around that request, in sequence, under the same identifier.

![Side-by-side view of OpenObserve Logs filtered by trace_id and the matching trace waterfall open in the Traces view](<Screenshot 2026-03-20 at 2.00.30 AM-1.png>)

![Side-by-side view of OpenObserve Logs filtered by trace_id and the matching trace waterfall open in the Traces view](<Screenshot 2026-03-20 at 2.01.27 AM-1.png>)*Log-trace correlation in action - the same trace_id connects a log record in the Logs view to the full request waterfall in the Traces view*



---

## What Spring Boot Instruments Automatically

Because we're using `micrometer-tracing-bridge-otel` with Spring Boot 3's observation infrastructure, the following produce spans with zero manual tracer code:

| What | How it appears in traces |
|---|---|
| Every inbound HTTP request | Root HTTP server span |
| `@Observed` methods | Child spans with the name you specify |
| Uncaught request failures | Failed request traces and error-related exception data |
| Actuator health endpoint | Request traces for `/actuator/health` |

Things that need additional instrumentation if you add them later include outbound HTTP clients, JDBC calls, messaging clients, and async boundaries.

---

## Common Pitfalls

These are bugs encountered during development of this demo, documented here so you don't spend hours on issues that have a one-line fix.

**1. `./mvnw: No such file or directory`**

The Maven wrapper isn't generated in every template. Use `mvn` directly.

**2. `Please specify organization id with header key 'organization'`**

OpenObserve expects the `organization` header on OTLP requests. Add it to both the span exporter and the log exporter.

**3. `traceId` is empty in terminal logs**

Three things need to be right:
- `micrometer-tracing-bridge-otel` must be on the classpath
- `management.tracing.sampling.probability=1.0` must be set
- request-thread logs must have MDC populated

In this project, the MDC part is handled explicitly by `TraceMdcFilter`.

**4. App starts, but logs crash with `ClassNotFoundException: io.opentelemetry.semconv.ExceptionAttributes`**

The Logback appender version used here still expects an older semantic-conventions class. In this demo, that incompatibility is handled with a small compatibility shim specific to this appender and semconv version combination:

```java
public final class ExceptionAttributes {
    public static final AttributeKey<String> EXCEPTION_TYPE =
            AttributeKey.stringKey("exception.type");
    public static final AttributeKey<String> EXCEPTION_MESSAGE =
            AttributeKey.stringKey("exception.message");
    public static final AttributeKey<String> EXCEPTION_STACKTRACE =
            AttributeKey.stringKey("exception.stacktrace");
}
```

**5. No data appears in OpenObserve after startup**

Batch processors buffer briefly before export, so idle startup doesn't generate telemetry. Make a few real HTTP requests first, then wait a few seconds. Also confirm that the endpoint in `application.yml` is port 5081, not 5080.

---

## Conclusion

Observability doesn't have to mean rewriting your application. The setup in this tutorial adds full log and trace visibility to a Spring Boot service without touching a single line of business logic. The `@Observed` annotation, the OTel Logback appender, and the Micrometer bridge do the heavy lifting — your controllers and services stay exactly as they would be without any monitoring at all.

The payoff is concrete. When something breaks, you're not grepping through flat log files hoping to find a matching timestamp. You have a trace ID in every log line, a waterfall showing exactly where time was spent, and a direct path from an error log to the span that produced it.

OpenObserve makes this practical to run. One binary, one OTLP endpoint for both logs and traces, and a query interface that lets you move between signals without switching tools. The full source code for this tutorial is available at [github.com/gorakhnathy7/openobserve-tryout](https://github.com/gorakhnathy7/openobserve-tryout).

---

## Further Reading

- [OpenObserve Documentation](https://openobserve.ai/docs/)
- [OpenObserve OTLP Ingestion Documentation](https://openobserve.ai/docs/ingestion/traces/otlp/)
- [OpenTelemetry Java SDK Documentation](https://opentelemetry.io/docs/languages/java/)
- [Micrometer Tracing Documentation](https://micrometer.io/docs/tracing)
