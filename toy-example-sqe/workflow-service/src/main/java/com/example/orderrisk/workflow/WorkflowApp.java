package com.example.orderrisk.workflow;

import com.example.orderrisk.common.ServiceApp;
import com.example.orderrisk.common.ServiceConfig;
import com.example.orderrisk.common.ServiceHttpClient;
import com.example.orderrisk.common.Telemetry;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/** Workflow service: HTTP API for Use Case 2 (quotes) and the start of Use Case 3 (risk analysis). */
public final class WorkflowApp {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowApp.class);

    private WorkflowApp() {
    }

    public static void main(String[] args) throws Exception {
        ServiceConfig config = ServiceConfig.load();
        Telemetry.init("workflow-service", config);

        Duration timeout = Duration.ofMillis(config.getInt("http.client.timeoutMs"));
        RiskMessagePublisher publisher = new RiskMessagePublisher(connect(config),
                config.get("rabbitmq.exchange"), config.get("rabbitmq.queue"), config.get("rabbitmq.routingKey"),
                config.getInt("rabbitmq.confirmTimeoutMs"));
        OrderWorkflow workflow = new OrderWorkflow(
                new ServiceHttpClient("order-service", config.get("order.baseUrl"), timeout),
                new ServiceHttpClient("pricing-profile-service", config.get("pricing.baseUrl"), timeout),
                publisher, new ServiceHttpClient("risk-processing-service", config.get("risk.baseUrl"), timeout));

        Javalin app = ServiceApp.create("workflow-service", config, javalin -> { });
        app.post("/risk-previews", ctx -> ctx.json(workflow.riskPreview(ctx.bodyAsClass(OrderWorkflow.RiskPreviewRequest.class))));
        app.post("/quotes", ctx -> ctx.json(workflow.quote(ctx.bodyAsClass(OrderWorkflow.QuoteRequest.class))));
        app.post("/orders", ctx -> ctx.status(202)
                .json(workflow.submitForRiskAnalysis(ctx.bodyAsClass(OrderWorkflow.OrderRequest.class))));
        app.exception(RiskMessagePublisher.PublishException.class, (e, ctx) ->
                ctx.status(503).json(Map.of("error", e.getMessage())));
        app.start(config.getInt("server.port"));
        LOG.info("workflow-service started");
    }

    /** Connects to RabbitMQ, retrying for about a minute while the broker is starting. */
    static Connection connect(ServiceConfig config) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.get("rabbitmq.host"));
        factory.setPort(config.getInt("rabbitmq.port"));
        factory.setUsername(config.get("rabbitmq.username"));
        factory.setPassword(config.get("rabbitmq.password"));
        for (int attempt = 1; ; attempt++) {
            try {
                return factory.newConnection("workflow-service");
            } catch (Exception e) {
                if (attempt >= 30) {
                    throw e;
                }
                LOG.info("RabbitMQ not reachable yet (attempt {}): {}", attempt, e.getMessage());
                Thread.sleep(2000);
            }
        }
    }
}
