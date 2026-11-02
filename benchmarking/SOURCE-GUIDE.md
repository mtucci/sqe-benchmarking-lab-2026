# Source guide

The same `RiskScorer.evaluate` method is included in every benchmark. Begin with the scorer and follow the progressively wider measurement boundaries.

| File | What to look for |
| --- | --- |
| [RiskScorer.java](../toy-example-sqe/risk-processing-service/src/main/java/com/example/orderrisk/processing/RiskScorer.java) | A rule's country, amount and account-age conditions; the immutable rule catalog; `scoreBaseline` scanning every rule; `scoreOptimized` selecting rules by country. |
| [RiskScorerBenchmark.java](../toy-example-sqe/benchmark-tools/src/main/java/com/example/orderrisk/benchmark/RiskScorerBenchmark.java) | `@Setup` prepares the order, rules and index; `@Benchmark` times the shared evaluation method. |
| [RiskEvaluationApi.java](../toy-example-sqe/risk-processing-service/src/main/java/com/example/orderrisk/processing/RiskEvaluationApi.java) | The component endpoint `POST /risk-evaluations` accepts a complete order/profile and calls the scorer. |
| [ApiController.java](../toy-example-sqe/gateway-service/src/main/java/com/example/orderrisk/gateway/ApiController.java) | The end-to-end entry point `POST /api/risk-previews` forwards the request to the workflow service. |
| [OrderWorkflow.java](../toy-example-sqe/workflow-service/src/main/java/com/example/orderrisk/workflow/OrderWorkflow.java) | `riskPreview` reads prices, history and profile data, then calls the risk service and waits for the result. |
| [http.js](http.js) | Supplied k6 scenario, virtual users, parameters, response validation and latency samples for both HTTP boundaries. |
| [analyze.py](analyze.py) | Raw sample loading, warm-up selection, descriptive/inferential statistics and comparison plots. |
| [results_server.py](container/results_server.py) | The dashboard's experiment list, analysis actions and saved reports. |

A rule adds points when its conditions match. The score is earned points as a percentage of possible points for the shipping country. The country index is prepared before measurement and reused; each order is still scored anew. Increasing `ruleCount` changes the rule collection and may change the score, so implementation comparisons keep it fixed.

The [component diagram](benchmark-architecture.puml) shows the architecture. The [sequence diagram](benchmark-scopes.puml) shows which path each benchmark times. The preview is synchronous and creates no order. The application's ordinary order-submission path uses RabbitMQ for background processing; see the [SUT documentation](../toy-example-sqe/README.md).

All Java modules and their tests are included under `../toy-example-sqe/`. The [shared library](../toy-example-sqe/service-commons/) provides configuration, HTTP clients and server setup. The lab tests are in [tests/](tests/).

For classroom experiments, use [the student instructions](README.md) and this lab's `http.js`. The SUT's separate `performance/k6/example.js` and Compose deployment illustrate the original application use cases and are optional for exploration. The root [Dockerfile](../Dockerfile) shows how the complete lab environment is assembled.
