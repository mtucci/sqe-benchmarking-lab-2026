# Grafana k6 – end-to-end performance tests

`example.js` is a deliberately **small** starting point. It load-tests one synchronous use case
(Use Case 2, `POST /api/quotes`) through the gateway service with a fixed number of concurrent
virtual users (a closed workload). Extend it for your own experiments.

k6 runs in a Docker container (Compose profile `performance`), so nothing has to be installed
locally. The container reaches the gateway via the Compose network at `http://gateway-service:8080`.

## Run

Start the system first (`docker compose up --build`), then, from the repository root:

```bash
# defaults: 5 VUs for 30 s
docker compose --profile performance run --rm k6 run /scripts/example.js

# 10 concurrent users for 60 s
docker compose --profile performance run --rm -e VUS=10 -e DURATION=60s k6 run /scripts/example.js

# 20 users, each pausing 0.5 s between requests
docker compose --profile performance run --rm -e VUS=20 -e THINK_TIME=0.5 k6 run /scripts/example.js
```

## Parameters (environment variables)

| Variable     | Default                       | Meaning                                         |
|--------------|-------------------------------|-------------------------------------------------|
| `BASE_URL`   | `http://gateway-service:8080` | Gateway base URL                                |
| `VUS`        | `5`                           | Number of concurrent virtual users              |
| `DURATION`   | `30s`                         | Test duration                                   |
| `THINK_TIME` | `0`                           | Seconds each VU sleeps between two requests     |

## Reading the output

The end-of-test summary shows, among others:

* `http_reqs` – number of requests and **throughput** (requests/s);
* `http_req_failed` – **error rate**;
* `http_req_duration` – **response time** (avg, min, median, max, p90, p95);
* `checks` – share of successful correctness checks;
* ✓ / ✗ next to the **thresholds** (`p(95) < 1000 ms`, `< 1 %` failures). k6 exits with a
  non-zero code when a threshold is violated.

## Ideas for extensions (not provided)

* other endpoints/use cases, e.g. `GET /api/orders/{id}` (Use Case 1);
* open workloads with a fixed arrival rate (`constant-arrival-rate` / `ramping-arrival-rate` executors);
* stages that increase the load step by step to find the saturation point;
* the asynchronous use case: measure the **submission** latency of `POST /api/orders` *separately*
  from the time until `GET /api/orders/{id}/status` reports `COMPLETED` (e.g. with a custom `Trend` metric).
