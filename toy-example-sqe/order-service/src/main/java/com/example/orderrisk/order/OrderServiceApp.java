package com.example.orderrisk.order;

import com.example.orderrisk.common.ServiceApp;
import com.example.orderrisk.common.ServiceConfig;
import com.example.orderrisk.common.ServiceHttpClient;
import com.example.orderrisk.common.Telemetry;
import com.example.orderrisk.order.OrderModels.NewOrder;
import com.example.orderrisk.order.OrderModels.Order;
import com.example.orderrisk.order.OrderModels.OrderDetails;
import com.example.orderrisk.order.OrderModels.RiskResultUpdate;
import com.example.orderrisk.order.OrderModels.StatusUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/**
 * Resource service: persistence of customer orders, order items and risk results.
 * <p>
 * Used by the gateway (Use Case 1, order details; order list and status),
 * by the workflow-service (create order, Use Case 2 customer summary, status updates)
 * and by the risk-processing-service (status updates, risk results).
 */
public final class OrderServiceApp {

    private static final Logger LOG = LoggerFactory.getLogger(OrderServiceApp.class);

    private final OrderRepository repository;
    private final ServiceHttpClient pricingClient;

    OrderServiceApp(OrderRepository repository, ServiceHttpClient pricingClient) {
        this.repository = repository;
        this.pricingClient = pricingClient;
    }

    public static void main(String[] args) {
        ServiceConfig config = ServiceConfig.load();
        Telemetry.init("order-service", config);

        HikariConfig db = new HikariConfig();
        db.setJdbcUrl(config.get("order.jdbcUrl"));
        db.setUsername("sa");
        db.setPassword("");
        db.setMaximumPoolSize(config.getInt("order.databasePoolSize"));
        db.setMinimumIdle(config.getInt("order.databasePoolSize")); // fixed-size pool
        db.setConnectionTimeout(config.getInt("order.databaseTimeoutMs"));
        db.setPoolName("order-db-pool");
        OrderRepository repository = new OrderRepository(new HikariDataSource(db));
        repository.createSchema();

        ServiceHttpClient pricing = new ServiceHttpClient("pricing-profile-service", config.get("pricing.baseUrl"),
                Duration.ofMillis(config.getInt("http.client.timeoutMs")));

        Javalin app = ServiceApp.create("order-service", config, javalin -> { });
        new OrderServiceApp(repository, pricing).registerRoutes(app);
        app.start(config.getInt("server.port"));
        LOG.info("order-service started: databasePoolSize={}", config.get("order.databasePoolSize"));
    }

    void registerRoutes(Javalin app) {
        app.post("/orders", this::createOrder);
        app.get("/orders", ctx -> ctx.json(repository.listRecent(limit(ctx))));
        app.get("/orders/{id}", this::orderDetails);
        app.get("/orders/{id}/status", ctx -> ctx.json(repository.find(orderId(ctx))));
        app.put("/orders/{id}/status", this::updateStatus);
        app.put("/orders/{id}/risk-result", this::saveRiskResult);
        app.get("/customers/{customerId}/order-summary",
                ctx -> ctx.json(repository.customerSummary(ctx.pathParam("customerId"))));

        app.exception(OrderModels.OrderNotFoundException.class, (e, ctx) ->
                ctx.status(404).json(Map.of("error", e.getMessage())));
        app.exception(OrderModels.InvalidTransitionException.class, (e, ctx) ->
                ctx.status(409).json(Map.of("error", e.getMessage())));
        app.exception(OrderRepository.DatabaseException.class, (e, ctx) -> {
            LOG.error("Database failure", e);
            ctx.status(503).json(Map.of("error", e.getMessage()));
        });
    }

    /** Called by workflow-service (Use Case 3): stores the order in state SUBMITTED. */
    private void createOrder(Context ctx) {
        NewOrder order = ctx.bodyAsClass(NewOrder.class);
        order.validate();
        Order created = repository.create(order);
        LOG.info("Order {} created for customer {}", created.id(), created.customerId());
        ctx.status(201).json(created);
    }

    /** Use Case 1: order from the database plus the customer profile from pricing-profile-service. */
    private void orderDetails(Context ctx) {
        Order order = repository.find(orderId(ctx));
        JsonNode profile = pricingClient.get("/customers/" + order.customerId() + "/profile", JsonNode.class);
        ctx.json(new OrderDetails(order, profile));
    }

    private void updateStatus(Context ctx) {
        long id = orderId(ctx);
        StatusUpdate update = ctx.bodyAsClass(StatusUpdate.class);
        if (update.status() == null) {
            throw new IllegalArgumentException("status is required");
        }
        Order order = repository.updateStatus(id, update.status(), update.reason());
        LOG.info("Order {} -> {}{}", id, update.status(), update.reason() == null ? "" : " (" + update.reason() + ")");
        ctx.json(order);
    }

    private void saveRiskResult(Context ctx) {
        long id = orderId(ctx);
        RiskResultUpdate result = ctx.bodyAsClass(RiskResultUpdate.class);
        if (result.category() == null || result.totalAmount() == null || result.itemPrices() == null) {
            throw new IllegalArgumentException("category, totalAmount and itemPrices are required");
        }
        Order order = repository.saveRiskResult(id, result);
        LOG.info("Order {} -> COMPLETED (score {}, {})", id, result.score(), result.category());
        ctx.json(order);
    }

    private static long orderId(Context ctx) {
        try {
            return Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("order id must be a number");
        }
    }

    private static int limit(Context ctx) {
        String value = ctx.queryParam("limit");
        int limit = value == null ? 50 : Integer.parseInt(value);
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        return limit;
    }
}
