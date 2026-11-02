# Order Risk Analysis – a five-microservice teaching system

An online-retail **back-office** that accepts customer orders and asynchronously computes a
deterministic **order-risk score** before fulfilment. It is a teaching system for studying service architecture, functional tests,
JMH and k6 benchmarks, and distributed tracing. The bundled benchmarking lab compares
the same risk-scoring function at microbenchmark, component and end-to-end levels.

> The system does **not** process payments, reserve inventory or perform real fraud detection.
> All data is synthetic and every computation is deterministic.

---

## 1. Domain

| Concept                   | Description                                                                                  |
|---------------------------|----------------------------------------------------------------------------------------------|
| Customer order (primary)  | customer id (`C-1234`), shipping address, shipping country, one or more items, status        |
| Order item                | SKU and quantity (> 0); the unit price is recorded as a *price snapshot* during the analysis |
| Customer profile summary  | account age, previous orders, chargebacks, average order value, home country, loyalty tier   |
| Product price snapshot    | SKU, name, category, unit price, price-list version                                           |
| Risk result               | score 0–100 and category: **LOW** `< 35` ≤ **MEDIUM** `< 65` ≤ **HIGH**                       |

Business rules: an order needs at least one item and a positive quantity for every item; risk
processing is asynchronous; the same order and auxiliary data always give the same score.

Order states: `SUBMITTED → QUEUED → PROCESSING → COMPLETED`, or `FAILED` (error during the
analysis or message publication) or `REJECTED` (processing capacity exceeded). The order-service
refuses transitions that are not allowed (HTTP 409), so concurrent updates cannot move an order backwards.

## 2. The five microservices

| # | Service (role)                                   | Port | Responsibilities                                                                                                   | State                |
|---|--------------------------------------------------|------|--------------------------------------------------------------------------------------------------------------------|----------------------|
| 1 | **gateway-service** (gateway)                    | 8080 | Web GUI (Javalin + Pebble), external REST API, routes requests to downstream services. No domain logic.           | –                    |
| 2 | **order-service** (resource)                     | 8081 | Persists and retrieves orders, items and risk results; enforces the order state machine.                          | H2 + HikariCP        |
| 3 | **workflow-service** (workflow)                  | 8082 | Coordinates the quote workflow and starts the asynchronous risk analysis; the only RabbitMQ publisher.            | –                    |
| 4 | **risk-processing-service** (processing)         | 8083 | Consumes analysis messages, runs the CPU-bound risk scoring on a bounded worker pool, calls other services.       | –                    |
| 5 | **pricing-profile-service** (auxiliary)          | 8084 | Deterministic product price snapshots and customer profile summaries; configurable concurrency and latency.       | in-code catalog      |

Supporting (non-service) modules:

* `service-commons` – small shared library: configuration loading, the traced HTTP client,
  OpenTelemetry set-up and the common Javalin server set-up. It is a jar used by every service,
  not a microservice.
* `benchmark-tools` – JMH microbenchmarks only.

Infrastructure (not application services): RabbitMQ, OpenTelemetry Collector, Grafana Tempo,
Grafana, and k6 (on demand).

## 3. The three use cases

| Use case                                   | Kind             | Entry point (gateway)                                       | Services involved                                                    |
|--------------------------------------------|------------------|-------------------------------------------------------------|----------------------------------------------------------------------|
| **UC1 – View order details**               | **synchronous**  | `GET /api/orders/{id}` · GUI `/orders/{id}`                 | gateway → order-service → pricing-profile-service                    |
| **UC2 – Price quote**                      | **synchronous**  | `POST /api/quotes` · GUI `/quote`                           | gateway → workflow-service → pricing-profile-service, order-service  |
| **UC3 – Submit order for risk analysis**   | **asynchronous** | `POST /api/orders` (202) · GUI `/orders/new`; status: `GET /api/orders/{id}/status` · GUI `/orders/{id}/status` | gateway → workflow-service → order-service, **RabbitMQ** → risk-processing-service → pricing-profile-service, order-service |

* **UC1** returns the stored order (items, status, risk result if available) together with the
  customer profile summary.
* **UC2** returns priced quote lines, a subtotal, and a loyalty discount derived from the
  customer's order history. It does not use RabbitMQ.
* **UC3** stores the order, publishes one message to RabbitMQ and answers **202 Accepted before the
  analysis has run**. The risk score is computed later in the background; the status
  endpoint/page shows the progress and the final result.

The order list (`GET /api/orders`, GUI `/orders`) is a navigation aid. The forms load the product
catalog from pricing-profile-service (`GET /products`). Neither counts as a use case.

## 4. Prerequisites

* **Docker** with **Docker Compose v2** (Docker Desktop, or Docker Engine + compose plugin).
  Approx. 3 GB of free RAM.
* Only for building/testing outside Docker: **Java 17+** and **Maven 3.9+**.

## 5. Start the complete system

```bash
docker compose up --build
```

The first build downloads Maven dependencies and images and takes a few minutes. Wait until
all services are healthy (`docker compose ps`). Stop with `Ctrl+C` / `docker compose down`;
add `-v` to also delete the order database volume.

| What                    | URL                                                                     |
|-------------------------|-------------------------------------------------------------------------|
| **Web GUI**             | http://localhost:8080                                                   |
| **Grafana** (traces)    | http://localhost:3000 (no login; Tempo is pre-configured)              |
| **RabbitMQ management** | http://localhost:15672 (user `guest`, password `guest`)                 |
| Tempo HTTP API          | http://localhost:3200                                                   |
| Internal services       | http://localhost:8081 … 8084 (exposed for exploration; `/health` each)  |

## 6. REST examples

```bash
# UC3 – submit an order (returns 202 immediately, with a Location header)
curl -i -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"C-1001","shippingAddress":"Main Street 1, Berlin","shippingCountry":"DE",
       "items":[{"sku":"SKU-1001","quantity":2},{"sku":"SKU-4002","quantity":1}]}'

# UC3 – poll the status / final result
curl http://localhost:8080/api/orders/1/status

# UC1 – order details with customer profile
curl http://localhost:8080/api/orders/1

# UC2 – price quote
curl -X POST http://localhost:8080/api/quotes \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"C-1001","items":[{"sku":"SKU-1001","quantity":2},{"sku":"SKU-4001","quantity":1}]}'

# order list (most recent 50)
curl http://localhost:8080/api/orders
```

SKUs: `SKU-1001`, `SKU-1002` (books), `SKU-2001`, `SKU-2002` (home), `SKU-3001`, `SKU-3002`
(clothing), `SKU-4001`, `SKU-4002`, `SKU-4003` (electronics), `SKU-9001`, `SKU-9002` (gift cards).
Customer ids: `C-` followed by 4–8 digits.

Status codes: `200`/`202` success · `400` invalid input · `404` unknown order · `409` invalid
state transition (internal API) · `422` unknown SKU · `502` downstream error · `503` downstream
service unreachable/overloaded, database unavailable, or message publication failed. Errors are
returned as `{"error": "..."}`. An unknown SKU in an *asynchronous* submission is detected during
the analysis and ends in state `FAILED`.

## 7. Configuration and concurrency settings

Each service reads `src/main/resources/application.properties`; every key can be overridden by
an environment variable (used in `compose.yaml`): `processing.workerThreads` →
`PROCESSING_WORKER_THREADS`. Settings are fixed at start-up and never resized at runtime.

| Setting (env variable)                                   | Default  | Meaning                                                                    |
|----------------------------------------------------------|----------|----------------------------------------------------------------------------|
| `server.maxThreads` (`SERVER_MAX_THREADS`)               | 32 (16)  | Jetty request threads per service (16 in risk-processing-service)          |
| `order.databasePoolSize` (`ORDER_DATABASE_POOL_SIZE`)    | 4        | HikariCP connections of order-service                                      |
| `order.databaseTimeoutMs`                                | 2000     | Max. wait for a DB connection, then HTTP 503                               |
| `processing.workerThreads` (`PROCESSING_WORKER_THREADS`) | 2        | Fixed worker pool size of risk-processing-service                          |
| `processing.queueCapacity` (`PROCESSING_QUEUE_CAPACITY`) | 8        | Bounded job queue in front of the workers; overflow → order `REJECTED`     |
| `processing.prefetchCount` (`PROCESSING_PREFETCH_COUNT`) | 10       | Unacknowledged RabbitMQ messages held by the consumer (`basic.qos`)        |
| `processing.ruleCount` (`PROCESSING_RULE_COUNT`)       | 500000   | Rules in the loaded risk catalog                       |
| `pricing.threadPoolSize` (`PRICING_THREAD_POOL_SIZE`)    | 2        | Fixed worker pool of pricing-profile-service                               |
| `pricing.queueCapacity` (`PRICING_QUEUE_CAPACITY`)       | 50       | Bounded request queue; overflow → HTTP 503                                 |
| `pricing.processingDelayMs` (`PRICING_PROCESSING_DELAY_MS`) | 20    | Simulated lookup latency per request (holds a worker, not the CPU)         |
| `http.client.timeoutMs`                                  | 5000/10000 | Timeout of service-to-service HTTP calls                                 |
| `rabbitmq.confirmTimeoutMs`                              | 5000     | Max. wait for the broker's publisher confirm                               |

With `prefetchCount ≤ workerThreads + queueCapacity` (default) surplus messages wait in RabbitMQ
(visible in the management UI). With a larger prefetch count, bursts overflow the worker pool
and orders are `REJECTED`. JVM heap sizes are set with `JAVA_OPTS` (default `-Xmx192m`).

## 8. Build and functional tests

```bash
# build everything and run the unit tests
mvn clean verify

# unit tests of one service
mvn -pl risk-processing-service -am test

# end-to-end smoke tests (one per use case) against the running system
docker compose up --build -d
mvn -pl gateway-service -am verify -Pe2e -De2e.baseUrl=http://localhost:8080
```

Unit tests use JUnit 5 and AssertJ; the end-to-end tests (`gateway-service/src/test/java/.../e2e`)
use REST Assured and only talk to the gateway. The UC3 test submits an order, receives `202`,
then polls the status endpoint until the order is `COMPLETED` (30 s timeout). The seed suite is
intentionally small.

## 9. JMH microbenchmarks

The CPU-bound kernel is `RiskScorer` (`risk-processing-service`), a plain class without HTTP,
messaging or tracing dependencies. The example benchmark is
`benchmark-tools/src/main/java/com/example/orderrisk/benchmark/RiskScorerBenchmark.java`.

```bash
mvn -pl benchmark-tools -am package -DskipTests
java -jar benchmark-tools/target/benchmarks.jar RiskScorerBenchmark

# quicker run, one parameter value, list options with -h
java -jar benchmark-tools/target/benchmarks.jar RiskScorerBenchmark -p ruleCount=100000 -wi 2 -i 3
```

The benchmark has `implementation=baseline|optimized` and `ruleCount` parameters. The baseline scans every country-specific risk rule. The optimized scorer uses a prebuilt country index to select only relevant rules, preserving the exact score, category and amount. Each rule checks order amount and account age and adds points when it matches; the score is the percentage of possible points for the shipping country. The immutable catalog and index are prepared before benchmark measurement. For example, select one implementation with `-p implementation=optimized`.

For the benchmarking lab, the same `RiskScorer.evaluate` kernel is also exposed by the read-only risk-service endpoint `POST /risk-evaluations` (port 8083). The gateway’s `POST /api/risk-previews` synchronously coordinates quote/history and customer-profile reads, then calls that endpoint and returns `{quote, evaluation}` after scoring finishes. It accepts the usual customer, shipping and item fields plus `ruleCount` and `implementation`. This preview creates no order. See [the Docker benchmarking lab](../benchmarking/README.md) for the matching JMH, component and end-to-end workloads and fixed input.

## 10. k6 end-to-end benchmark

k6 runs as an on-demand container (Compose profile `performance`) and reaches the gateway
through the Compose network. The example benchmarks Use Case 2 (`POST /api/quotes`).

```bash
# defaults: 5 virtual users, 30 s
docker compose --profile performance run --rm k6 run /scripts/example.js

# 10 virtual users for 60 s
docker compose --profile performance run --rm -e VUS=10 -e DURATION=60s k6 run /scripts/example.js
```

| Parameter    | Default                       | Meaning                                   |
|--------------|-------------------------------|-------------------------------------------|
| `BASE_URL`   | `http://gateway-service:8080` | Gateway base URL                          |
| `VUS`        | `5`                           | Concurrent virtual users                  |
| `DURATION`   | `30s`                         | Test duration                             |
| `THINK_TIME` | `0`                           | Seconds of pause between two iterations   |

The summary reports throughput (`http_reqs`), error rate (`http_req_failed`), response times
(`http_req_duration` avg/median/p90/p95/max) and the threshold results. See
[`performance/k6/README.md`](performance/k6/README.md).

## 11. Inspecting a distributed trace in Grafana

All five services create OpenTelemetry spans and send them (OTLP/HTTP) to the OpenTelemetry
Collector, which forwards them to Tempo. Trace context travels in the W3C `traceparent` header
of every HTTP request **and of every RabbitMQ message**, so one trace covers a complete use case,
including the asynchronous part.

1. Run a use case (e.g. submit an order in the GUI and wait until it is `COMPLETED`).
2. Open http://localhost:3000 → **Explore** (compass icon). The data source **Tempo** is
   already selected.
3. Query type **Search**: choose *Service Name* `gateway-service` and *Span Name*
   `POST /api/orders` — or use **TraceQL**, e.g.
   `{ resource.service.name = "gateway-service" && name = "POST /api/orders" }` — and press *Run query*.
4. Click a trace ID. The waterfall view shows one row per span with its service, duration and
   attributes; the *Node graph* shows the services involved.

Spans you will find: one `SERVER` span per incoming request (named after the route, e.g.
`GET /orders/{id}`), one `CLIENT` span per outgoing call (e.g. `GET pricing-profile-service`),
H2 operations in order-service (e.g. `SELECT orders`), `order-risk publish` (PRODUCER,
workflow-service), `order-risk-analysis receive` (CONSUMER), `risk analysis job` (worker thread;
attribute `job.wait_ms` = time spent in the worker queue) and `compute risk score` (CPU work,
attributes `risk.rule_count`, `risk.score`). Health checks and static files are not traced.

Logs: `docker compose logs -f risk-processing-service` (or any other service).

## 12. Repository layout

```
├── pom.xml                      parent POM (Java 17, dependency versions)
├── compose.yaml                 complete runtime environment
├── service-commons/             shared helper library (not a service)
├── gateway-service/             GUI (templates in src/main/resources/templates) + REST API, e2e tests
├── order-service/               resource service (H2 / JDBC / HikariCP)
├── workflow-service/            workflow service (RabbitMQ publisher)
├── risk-processing-service/     processing service (RabbitMQ consumer, worker pool, RiskScorer)
├── pricing-profile-service/     auxiliary service
├── benchmark-tools/             JMH benchmarks
├── performance/k6/              k6 example script + README
└── observability/               Collector, Tempo and Grafana provisioning configuration
```

Each service has its own `Dockerfile` (multi-stage: Maven build → `eclipse-temurin:17-jre`);
the build context is the repository root. Only Docker is needed at runtime — no local database,
broker, observability tool or k6 installation.

## 13. Technology

Java 17 · Maven · Javalin 6 · Jackson · Pebble · H2 (plain JDBC) · HikariCP · RabbitMQ
(amqp-client) · OpenTelemetry SDK (tracing only) · OpenTelemetry Collector · Grafana Tempo ·
Grafana · JUnit 5 · AssertJ · REST Assured · JMH · Grafana k6 · Docker / Docker Compose.

No application metrics are collected: there is no `/metrics` endpoint, no Prometheus and no
metrics dashboards. Performance is studied with traces, logs, JMH, k6 and tests.
