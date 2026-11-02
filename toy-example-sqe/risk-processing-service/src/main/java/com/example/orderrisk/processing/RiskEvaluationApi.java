package com.example.orderrisk.processing;

import io.javalin.Javalin;

/** Read-only HTTP boundary around the same function measured by RiskScorerBenchmark. */
public final class RiskEvaluationApi {
    public record Request(RiskScorer.Input input, int ruleCount, String implementation) { }
    public record Evaluation(String implementation, int ruleCount, RiskScorer.Result risk) { }

    private RiskEvaluationApi() { }

    public static void registerRoutes(Javalin app) {
        app.post("/risk-evaluations", ctx -> ctx.json(evaluate(ctx.bodyAsClass(Request.class))));
    }

    public static Evaluation evaluate(Request request) {
        if (request == null || request.ruleCount() < 1 || request.ruleCount() > 1_000_000) {
            throw new IllegalArgumentException("ruleCount must be between 1 and 1000000");
        }
        RiskScorer.Implementation implementation = RiskScorer.Implementation.parse(request.implementation());
        RiskScorer.Input input = request.input();
        if (input == null || input.items() == null || input.items().isEmpty() || input.items().size() > 20
                || input.shippingAddress() == null || input.shippingAddress().isBlank()
                || input.shippingCountry() == null || !input.shippingCountry().matches("[A-Z]{2}")
                || input.customer() == null || input.customer().homeCountry() == null
                || input.customer().averageOrderValue() == null || input.customer().averageOrderValue().signum() < 0
                || input.customer().accountAgeDays() < 0 || input.customer().previousOrders() < 0 || input.customer().chargebacks() < 0) {
            throw new IllegalArgumentException("a complete risk input with items, shipping and customer profile is required");
        }
        for (RiskScorer.Item item : input.items()) {
            if (item == null || item.sku() == null || item.category() == null || item.quantity() < 1
                    || item.quantity() > 1000 || item.unitPrice() == null || item.unitPrice().signum() < 0) {
                throw new IllegalArgumentException("invalid risk input item");
            }
        }
        return new Evaluation(implementation.parameter(), request.ruleCount(),
                RiskScorer.evaluate(input, RiskScorer.rules(request.ruleCount()), implementation));
    }
}
