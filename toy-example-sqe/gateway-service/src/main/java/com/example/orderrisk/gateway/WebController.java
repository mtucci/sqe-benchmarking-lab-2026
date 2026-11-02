package com.example.orderrisk.gateway;

import com.example.orderrisk.common.DownstreamException;
import com.example.orderrisk.common.ServiceHttpClient;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-rendered web GUI (Pebble templates in src/main/resources/templates).
 * Pages call the same downstream services as the REST API.
 */
public class WebController {

    /** Number of item rows offered by the order and quote forms. */
    static final int FORM_ROWS = 4;

    private final ServiceHttpClient orderClient;
    private final ServiceHttpClient workflowClient;
    private final ServiceHttpClient pricingClient;

    public WebController(ServiceHttpClient orderClient, ServiceHttpClient workflowClient, ServiceHttpClient pricingClient) {
        this.orderClient = orderClient;
        this.workflowClient = workflowClient;
        this.pricingClient = pricingClient;
    }

    public void registerRoutes(Javalin app) {
        app.get("/", ctx -> ctx.html(GatewayApp.render("home", new HashMap<>())));
        app.get("/orders", this::orderList);
        app.get("/orders/new", ctx -> ctx.html(GatewayApp.render("order-form", formModel(ctx, null))));
        app.post("/orders", this::submitOrder);
        app.get("/orders/{id}", this::orderDetails);
        app.get("/orders/{id}/status", this::orderStatus);
        app.get("/quote", ctx -> ctx.html(GatewayApp.render("quote", formModel(ctx, null))));
        app.post("/quote", this::quote);
    }

    private void orderList(Context ctx) {
        Map<String, Object> model = new HashMap<>();
        model.put("orders", orderClient.get("/orders", Object.class));
        ctx.html(GatewayApp.render("orders", model));
    }

    /** Use Case 1: order details including the customer profile. */
    private void orderDetails(Context ctx) {
        Map<String, Object> model = new HashMap<>();
        model.put("details", orderClient.get("/orders/" + ApiController.orderId(ctx), Object.class));
        ctx.html(GatewayApp.render("order-detail", model));
    }

    /** Use Case 3: status page, refreshed manually by the user. */
    private void orderStatus(Context ctx) {
        Map<String, Object> model = new HashMap<>();
        model.put("order", orderClient.get("/orders/" + ApiController.orderId(ctx) + "/status", Object.class));
        ctx.html(GatewayApp.render("order-status", model));
    }

    /** Use Case 3: submit the order and redirect to its status page right away. */
    private void submitOrder(Context ctx) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("customerId", ctx.formParam("customerId"));
        request.put("shippingAddress", ctx.formParam("shippingAddress"));
        request.put("shippingCountry", ctx.formParam("shippingCountry"));
        try {
            request.put("items", items(ctx));
            Map<?, ?> submission = workflowClient.post("/orders", request, Map.class);
            ctx.redirect("/orders/" + submission.get("orderId") + "/status", io.javalin.http.HttpStatus.SEE_OTHER);
        } catch (IllegalArgumentException | DownstreamException e) {
            if (e instanceof DownstreamException d && !d.isClientError()) {
                throw d;
            }
            ctx.status(400).html(GatewayApp.render("order-form", formModel(ctx, e.getMessage())));
        }
    }

    /** Use Case 2: price quote. */
    private void quote(Context ctx) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("customerId", ctx.formParam("customerId"));
        try {
            request.put("items", items(ctx));
            Map<String, Object> model = formModel(ctx, null);
            model.put("quote", workflowClient.post("/quotes", request, Object.class));
            ctx.html(GatewayApp.render("quote", model));
        } catch (IllegalArgumentException | DownstreamException e) {
            if (e instanceof DownstreamException d && !d.isClientError()) {
                throw d;
            }
            ctx.status(400).html(GatewayApp.render("quote", formModel(ctx, e.getMessage())));
        }
    }

    /** Reads the item rows sku1/qty1 ... skuN/qtyN of a form; empty rows are skipped. */
    static List<Map<String, Object>> items(Context ctx) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int row = 1; row <= FORM_ROWS; row++) {
            String sku = ctx.formParam("sku" + row);
            if (sku == null || sku.isBlank()) {
                continue;
            }
            String quantity = ctx.formParam("qty" + row);
            int parsed;
            try {
                parsed = Integer.parseInt(quantity == null ? "" : quantity.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("quantity of " + sku + " must be a whole number");
            }
            items.add(Map.of("sku", sku, "quantity", parsed));
        }
        return items;
    }

    /** Model for the forms: product catalog, previously entered values and an optional error. */
    private Map<String, Object> formModel(Context ctx, String error) {
        Map<String, Object> model = new HashMap<>();
        model.put("products", pricingClient.get("/products", Object.class));
        model.put("rows", List.of(1, 2, 3, 4).subList(0, FORM_ROWS));
        model.put("form", ctx.formParamMap().isEmpty() ? Map.of() : flatten(ctx.formParamMap()));
        model.put("error", error);
        return model;
    }

    private static Map<String, String> flatten(Map<String, List<String>> params) {
        Map<String, String> result = new HashMap<>();
        params.forEach((key, values) -> result.put(key, values.isEmpty() ? "" : values.get(0)));
        return result;
    }
}
