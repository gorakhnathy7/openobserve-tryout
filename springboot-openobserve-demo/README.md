# SpringBoot + OpenObserve Demo

A minimal Spring Boot application demonstrating how to ship **structured logs** and **distributed traces** to [OpenObserve](https://openobserve.ai/) using OpenTelemetry — with zero instrumentation code in your business logic.

## Prerequisites

- Java 17+
- Maven 3.6+
- OpenObserve running (self-hosted or cloud)

## Setting Up OpenObserve

**Option A — Self-hosted (Docker):**
```bash
docker run -d \
  -p 5080:5080 \
  -p 5081:5081 \
  -e ZO_ROOT_USER_EMAIL=root@example.com \
  -e ZO_ROOT_USER_PASSWORD=Complexpass#123 \
  public.ecr.aws/zinclabs/openobserve:latest
```
Dashboard → http://localhost:5080
Default credentials: `root@example.com` / `Complexpass#123`

**Option B — OpenObserve Cloud:**
Sign up at https://cloud.openobserve.ai (free tier available)

## Configuration

Edit `src/main/resources/application.yml`:

```yaml
openobserve:
  otlp:
    endpoint: http://localhost:5081        # or https://api.openobserve.ai for cloud
    auth-header: "Basic <base64-token>"    # see below
    organization: default                  # your org name
```

Generate the auth header:
```bash
echo -n "your@email.com:your_token" | base64
```

Then set it as:
```yaml
auth-header: "Basic <output-from-above>"
```

## Running the App

```bash
mvn clean spring-boot:run
```

The app starts on **http://localhost:8080**

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/orders/{orderId}` | Fetch an order — creates a trace with a child span |
| POST | `/api/orders` | Create an order — simulates a DB write with latency |
| GET | `/api/orders/simulate-error` | Triggers an error — useful for testing error traces |
| GET | `/api/orders/health` | Health check |
| GET | `/actuator/health` | Spring Boot actuator health |

### Quick test:
```bash
curl http://localhost:8080/api/orders/ORD-001
curl http://localhost:8080/api/orders/simulate-error
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"productId": "PROD-001", "quantity": 2, "customerId": "CUST-100"}'
```

## Generating Load

Run the load test script to generate a continuous stream of logs and traces:

```bash
bash load-test.sh
```

This hits the API every 2 seconds and triggers an error every 5th iteration — giving you a realistic mix of traces to explore in OpenObserve.

## Viewing in OpenObserve

1. **Logs** → Select the `springboot-openobserve-demo` stream → search for `level=ERROR` to find errors
2. **Traces** → Find a trace → click to see the span waterfall
3. **Correlation** → Copy a `traceId` from a log line → search for it in Traces to see the full request journey

## Project Structure

```
src/main/java/com/example/demo/
├── DemoApplication.java          # Spring Boot entry point
├── config/
│   └── OpenTelemetryConfig.java  # OTel SDK setup — traces + logs to OpenObserve
├── controller/
│   └── OrderController.java      # REST endpoints
└── service/
    └── OrderService.java         # Business logic with @Observed tracing

src/main/resources/
├── application.yml               # App config and OpenObserve credentials
└── logback-spring.xml            # Logback → OpenTelemetry appender config

load-test.sh                      # Traffic generator script
```

## How It Works

```
Your Code (log.info / log.error)
        │
        ▼
logback-spring.xml (OpenTelemetryAppender)
        │
        ▼
OpenTelemetryConfig.java (OTLP gRPC Exporter)
        │
        ▼
OpenObserve (Logs + Traces)
```

- **Traces** are created automatically for every HTTP request via Micrometer + OTel bridge
- **Child spans** are created per service method using the `@Observed` annotation
- **Logs** are forwarded via the Logback OTel appender — each log record carries the active `traceId` and `spanId`, enabling log-to-trace correlation in the dashboard
