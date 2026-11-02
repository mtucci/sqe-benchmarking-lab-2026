# Build the lab image from the repository root.
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY toy-example-sqe/ ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp clean package

FROM grafana/k6:0.54.0 AS k6

FROM python:3.12-slim-bookworm
RUN printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d && chmod +x /usr/sbin/policy-rc.d \
    && apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless rabbitmq-server supervisor tini bash curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=k6 /usr/bin/k6 /usr/local/bin/k6
COPY benchmarking/requirements.txt /opt/lab/requirements.txt
RUN pip install --no-cache-dir --only-binary=:all: -r /opt/lab/requirements.txt && pip check
COPY --from=build /build/benchmark-tools/target/benchmarks.jar /opt/sqe/benchmarks.jar
COPY --from=build /build/pricing-profile-service/target/app.jar /opt/sqe/pricing.jar
COPY --from=build /build/order-service/target/app.jar /opt/sqe/order.jar
COPY --from=build /build/workflow-service/target/app.jar /opt/sqe/workflow.jar
COPY --from=build /build/gateway-service/target/app.jar /opt/sqe/gateway.jar
COPY --from=build /build/risk-processing-service/target/app.jar /opt/sqe/risk.jar
# Lab runtime files.
COPY benchmarking/container/start.sh benchmarking/container/run-service.sh benchmarking/container/healthcheck.py benchmarking/container/results_server.py benchmarking/container/supervisord.conf /opt/lab/
COPY benchmarking/analyze.py benchmarking/http.js /lab/
COPY benchmarking/README.md benchmarking/benchmark-scopes.puml /lab/
COPY benchmarking/benchmark-architecture.puml /lab/
COPY toy-example-sqe/risk-processing-service/src/main/java/com/example/orderrisk/processing/RiskScorer.java /lab/source/RiskScorer.java
COPY toy-example-sqe/benchmark-tools/src/main/java/com/example/orderrisk/benchmark/RiskScorerBenchmark.java /lab/source/RiskScorerBenchmark.java
COPY toy-example-sqe/risk-processing-service/src/main/java/com/example/orderrisk/processing/RiskEvaluationApi.java /lab/source/RiskEvaluationApi.java
COPY toy-example-sqe/workflow-service/src/main/java/com/example/orderrisk/workflow/OrderWorkflow.java /lab/source/OrderWorkflow.java
RUN chmod +x /opt/lab/start.sh /opt/lab/run-service.sh /opt/lab/healthcheck.py \
    && ln -s /opt/lab/healthcheck.py /usr/local/bin/lab-ready \
    && ln -s /opt/sqe/benchmarks.jar /lab/benchmarks.jar \
    && ln -s data/results /lab/results && ln -s data/analysis /lab/analysis \
    && mkdir -p /var/log/lab \
    && printf '\ncd /lab\necho "Lab shell. Run lab-ready, then follow README.md. Results: http://localhost:8080"\n' >> /root/.bashrc
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 MPLCONFIGDIR=/tmp/matplotlib \
    TRACING_ENABLED=false SERVER_MAX_THREADS=32 \
    PRICING_BASE_URL=http://127.0.0.1:8084 ORDER_BASE_URL=http://127.0.0.1:8081 \
    WORKFLOW_BASE_URL=http://127.0.0.1:8082 RISK_BASE_URL=http://127.0.0.1:8083 RABBITMQ_HOST=127.0.0.1 \
    ORDER_JDBC_URL="jdbc:h2:mem:orders;DB_CLOSE_DELAY=-1" \
    RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS="+S 2:2" \
    PRICING_WORKERS=2 PRICING_DELAY_MS=20 PRICING_QUEUE=50
WORKDIR /lab
VOLUME ["/lab/data"]
EXPOSE 8090
HEALTHCHECK --interval=5s --timeout=5s --start-period=90s --retries=6 \
    CMD python3 /opt/lab/healthcheck.py --timeout 0 --services-only
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/lab/start.sh"]
