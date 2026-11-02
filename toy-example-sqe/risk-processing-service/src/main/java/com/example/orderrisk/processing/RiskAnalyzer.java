package com.example.orderrisk.processing;

import com.example.orderrisk.common.DownstreamException;
import com.example.orderrisk.common.ServiceHttpClient;
import com.example.orderrisk.common.Telemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes one risk analysis job (runs on a worker thread of {@link RiskMessageConsumer}):
 * <ol>
 *   <li>order-service: mark the order PROCESSING</li>
 *   <li>pricing-profile-service: fetch price snapshots and the customer profile</li>
 *   <li>CPU-bound scoring with {@link RiskScorer}</li>
 *   <li>order-service: store the risk result (order becomes COMPLETED)</li>
 * </ol>
 * Any failure marks the order FAILED.
 */
public class RiskAnalyzer {

    private static final Logger LOG = LoggerFactory.getLogger(RiskAnalyzer.class);

    /** Message body published by workflow-service. */
    public record RiskAnalysisMessage(long orderId, String customerId, String shippingAddress,
                                      String shippingCountry, List<MessageItem> items) {
    }

    public record MessageItem(String sku, int quantity) {
    }

    // Responses of pricing-profile-service
    public record PriceSnapshot(String sku, String name, String category, BigDecimal unitPrice, String priceListVersion) {
    }

    public record CustomerProfile(String customerId, int accountAgeDays, int previousOrders, int chargebacks,
                                  BigDecimal averageOrderValue, String homeCountry, String loyaltyTier) {
    }

    // Requests sent to order-service
    public record StatusUpdate(String status, String reason) {
    }

    public record ItemPrice(String sku, BigDecimal unitPrice) {
    }

    public record RiskResultUpdate(double score, String category, int ruleCount,
                                   BigDecimal totalAmount, List<ItemPrice> itemPrices) {
    }

    private final ServiceHttpClient orderClient;
    private final ServiceHttpClient pricingClient;
    private final int ruleCount;

    public RiskAnalyzer(ServiceHttpClient orderClient, ServiceHttpClient pricingClient, int ruleCount) {
        this.orderClient = orderClient;
        this.pricingClient = pricingClient;
        this.ruleCount = ruleCount;
    }

    public void analyze(RiskAnalysisMessage message) {
        long orderId = message.orderId();
        try {
            orderClient.put("/orders/" + orderId + "/status", new StatusUpdate("PROCESSING", null), Void.class);
        } catch (DownstreamException e) {
            if (e.status() == 409) {
                LOG.warn("Order {} is no longer waiting for analysis ({}), message ignored", orderId, e.getMessage());
                return;
            }
            throw e;
        }
        try {
            List<String> skus = message.items().stream().map(MessageItem::sku).toList();
            PriceSnapshot[] prices = pricingClient.post("/price-snapshots", Map.of("skus", skus), PriceSnapshot[].class);
            CustomerProfile profile = pricingClient.get("/customers/" + message.customerId() + "/profile",
                    CustomerProfile.class);

            RiskScorer.Input input = toScorerInput(message, prices, profile);
            RiskScorer.Result result = computeScore(input);

            List<ItemPrice> itemPrices = new ArrayList<>();
            for (PriceSnapshot price : prices) {
                itemPrices.add(new ItemPrice(price.sku(), price.unitPrice()));
            }
            orderClient.put("/orders/" + orderId + "/risk-result", new RiskResultUpdate(result.score(),
                    result.category(), ruleCount, result.totalAmount(), itemPrices), Void.class);
            LOG.info("Order {} analysed: score={} category={}", orderId, result.score(), result.category());
        } catch (RuntimeException e) {
            LOG.warn("Risk analysis of order {} failed: {}", orderId, e.getMessage());
            markFailed(orderId, "risk analysis failed: " + e.getMessage());
        }
    }

    /** The CPU-intensive step, traced as its own span. */
    private RiskScorer.Result computeScore(RiskScorer.Input input) {
        Span span = Telemetry.tracer().spanBuilder("compute risk score")
                .setAttribute("risk.rule_count", (long) ruleCount)
                .setAttribute("risk.item_count", (long) input.items().size())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            RiskScorer.Result result = RiskScorer.evaluate(input, ruleCount);
            span.setAttribute("risk.score", result.score());
            span.setAttribute("risk.category", result.category());
            return result;
        } finally {
            span.end();
        }
    }

    static RiskScorer.Input toScorerInput(RiskAnalysisMessage message, PriceSnapshot[] prices, CustomerProfile profile) {
        Map<String, PriceSnapshot> bySku = new HashMap<>();
        for (PriceSnapshot price : prices) {
            bySku.put(price.sku(), price);
        }
        List<RiskScorer.Item> items = new ArrayList<>();
        for (MessageItem item : message.items()) {
            PriceSnapshot price = bySku.get(item.sku());
            if (price == null) {
                throw new IllegalStateException("no price snapshot for " + item.sku());
            }
            items.add(new RiskScorer.Item(item.sku(), price.category(), item.quantity(), price.unitPrice()));
        }
        RiskScorer.Customer customer = new RiskScorer.Customer(profile.accountAgeDays(), profile.previousOrders(),
                profile.chargebacks(), profile.averageOrderValue(), profile.homeCountry());
        return new RiskScorer.Input(items, message.shippingAddress(), message.shippingCountry(), customer);
    }

    /** Overload handling: the worker pool was full, so the order is REJECTED without analysis. */
    public void reject(long orderId, String reason) {
        try {
            orderClient.put("/orders/" + orderId + "/status", new StatusUpdate("REJECTED", reason), Void.class);
        } catch (DownstreamException e) {
            LOG.error("Could not mark order {} as REJECTED: {}", orderId, e.getMessage());
        }
    }

    private void markFailed(long orderId, String reason) {
        try {
            orderClient.put("/orders/" + orderId + "/status", new StatusUpdate("FAILED", reason), Void.class);
        } catch (DownstreamException e) {
            LOG.error("Could not mark order {} as FAILED: {}", orderId, e.getMessage());
        }
    }
}
