package com.example.orderrisk.gateway;

import com.example.orderrisk.common.ServiceHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.IOException;

/**
 * External REST API. Every endpoint forwards the request to one downstream service with a
 * synchronous HTTP call and returns the downstream answer.
 *
 * <pre>
 * GET  /api/orders              order list                      -> order-service
 * GET  /api/orders/{id}         Use Case 1: order details        -> order-service (-> pricing-profile-service)
 * POST /api/quotes              Use Case 2: price quote          -> workflow-service (-> pricing, order-service)
 * POST /api/orders              Use Case 3: submit for analysis  -> workflow-service (-> RabbitMQ)
 * GET  /api/orders/{id}/status  Use Case 3: status / result      -> order-service
 * </pre>
 */
public class ApiController {

    private final ServiceHttpClient orderClient;
    private final ServiceHttpClient workflowClient;

    public ApiController(ServiceHttpClient orderClient, ServiceHttpClient workflowClient) {
        this.orderClient = orderClient;
        this.workflowClient = workflowClient;
    }

    public void registerRoutes(Javalin app) {
        app.get("/api/orders", ctx -> ctx.json(orderClient.get("/orders", JsonNode.class)));
        app.get("/api/orders/{id}", ctx -> ctx.json(orderClient.get("/orders/" + orderId(ctx), JsonNode.class)));
        app.get("/api/orders/{id}/status",
                ctx -> ctx.json(orderClient.get("/orders/" + orderId(ctx) + "/status", JsonNode.class)));
        app.post("/api/risk-previews", ctx -> ctx.json(workflowClient.post("/risk-previews", jsonBody(ctx), JsonNode.class)));
        app.post("/api/quotes", ctx -> ctx.json(workflowClient.post("/quotes", jsonBody(ctx), JsonNode.class)));
        app.post("/api/orders", ctx -> {
            JsonNode submission = workflowClient.post("/orders", jsonBody(ctx), JsonNode.class);
            ctx.status(202)
                    .header("Location", "/api/orders/" + submission.path("orderId").asLong() + "/status")
                    .json(submission);
        });
    }

    static String orderId(Context ctx) {
        String id = ctx.pathParam("id");
        if (!id.matches("\\d{1,18}")) {
            throw new IllegalArgumentException("order id must be a number");
        }
        return id;
    }

    private static JsonNode jsonBody(Context ctx) {
        try {
            JsonNode body = ServiceHttpClient.JSON.readTree(ctx.body());
            if (body == null || !body.isObject()) {
                throw new IllegalArgumentException("request body must be a JSON object");
            }
            return body;
        } catch (IOException e) {
            throw new IllegalArgumentException("request body is not valid JSON");
        }
    }
}
