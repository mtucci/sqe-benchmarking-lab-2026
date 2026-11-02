package com.example.orderrisk.workflow;

import com.example.orderrisk.common.DownstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.orderrisk.common.ServiceHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The two workflows coordinated by this service.
 * <ul>
 *   <li>{@link #quote}: Use Case 2, synchronous. Calls pricing-profile-service and order-service
 *       over HTTP and returns the complete quote.</li>
 *   <li>{@link #submitForRiskAnalysis}: Use Case 3, asynchronous. Stores the order (order-service),
 *       publishes a message to RabbitMQ and returns <em>before</em> the risk analysis runs.</li>
 * </ul>
 */
public class OrderWorkflow {

    private static final Logger LOG = LoggerFactory.getLogger(OrderWorkflow.class);
    private static final Pattern CUSTOMER_ID = Pattern.compile("C-\\d{4,8}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    public record Item(String sku, int quantity) {
    }

    public record OrderRequest(String customerId, String shippingAddress, String shippingCountry, List<Item> items) {
    }

    public record QuoteRequest(String customerId, List<Item> items) {
    }

    public record RiskPreviewRequest(String customerId, String shippingAddress, String shippingCountry,
                                     List<Item> items, int ruleCount, String implementation) { }

    public record CustomerProfile(String customerId, int accountAgeDays, int previousOrders, int chargebacks,
                                  BigDecimal averageOrderValue, String homeCountry, String loyaltyTier) { }

    public record RiskPreview(Quote quote, JsonNode evaluation) { }

    public record RiskAnalysisMessage(long orderId, String customerId, String shippingAddress,
                                      String shippingCountry, List<Item> items) {
    }

    public record Submission(long orderId, String status, String statusUrl) {
    }

    public record PriceSnapshot(String sku, String name, String category, BigDecimal unitPrice, String priceListVersion) {
    }

    public record CustomerOrderSummary(String customerId, int orderCount, int completedOrders,
                                       int highRiskOrders, BigDecimal completedAmount) {
    }

    public record QuoteLine(String sku, String name, String category, int quantity,
                            BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    public record Quote(String customerId, List<QuoteLine> lines, BigDecimal subtotal, int discountPercent,
                        BigDecimal discount, BigDecimal total, String priceListVersion,
                        CustomerOrderSummary customerHistory) {
    }

    record CreatedOrder(long id, String status) {
    }

    record StatusUpdate(String status, String reason) {
    }

    private final ServiceHttpClient orderClient;
    private final ServiceHttpClient pricingClient;
    private final RiskMessagePublisher publisher;
    private final ServiceHttpClient riskClient;

    public OrderWorkflow(ServiceHttpClient orderClient, ServiceHttpClient pricingClient, RiskMessagePublisher publisher) {
        this(orderClient, pricingClient, publisher, null);
    }

    public OrderWorkflow(ServiceHttpClient orderClient, ServiceHttpClient pricingClient,
                         RiskMessagePublisher publisher, ServiceHttpClient riskClient) {
        this.orderClient = orderClient;
        this.pricingClient = pricingClient;
        this.publisher = publisher;
        this.riskClient = riskClient;
    }

    /** Use Case 2 (synchronous): price the items and apply a loyalty discount based on the order history. */
    public Quote quote(QuoteRequest request) {
        validateCustomer(request.customerId());
        validateItems(request.items());
        List<String> skus = request.items().stream().map(Item::sku).toList();
        PriceSnapshot[] prices = pricingClient.post("/price-snapshots", Map.of("skus", skus), PriceSnapshot[].class);
        CustomerOrderSummary history = orderClient.get(
                "/customers/" + request.customerId() + "/order-summary", CustomerOrderSummary.class);
        return buildQuote(request, prices, history);
    }

    /** Synchronous read-only preview: quote/history + profile lookup + risk evaluation. No order is stored. */
    public RiskPreview riskPreview(RiskPreviewRequest request) {
        if (request == null || request.ruleCount() < 1 || request.ruleCount() > 1_000_000) {
            throw new IllegalArgumentException("ruleCount must be between 1 and 1000000");
        }
        if (!"baseline".equals(request.implementation()) && !"optimized".equals(request.implementation())) {
            throw new IllegalArgumentException("implementation must be baseline or optimized");
        }
        if (request.shippingAddress() == null || request.shippingAddress().isBlank()
                || request.shippingCountry() == null || !COUNTRY.matcher(request.shippingCountry()).matches()) {
            throw new IllegalArgumentException("shipping address and two-letter shipping country are required");
        }
        Quote quote = quote(new QuoteRequest(request.customerId(), request.items()));
        CustomerProfile customer = pricingClient.get("/customers/" + request.customerId() + "/profile", CustomerProfile.class);
        List<Map<String, Object>> scoringItems = quote.lines().stream().map(line -> Map.<String, Object>of(
                "sku", line.sku(), "category", line.category(), "quantity", line.quantity(), "unitPrice", line.unitPrice())).toList();
        Map<String, Object> input = Map.of("items", scoringItems, "shippingAddress", request.shippingAddress(),
                "shippingCountry", request.shippingCountry(), "customer", customer);
        JsonNode evaluation = riskClient.post("/risk-evaluations", Map.of("input", input,
                "ruleCount", request.ruleCount(), "implementation", request.implementation()), JsonNode.class);
        return new RiskPreview(quote, evaluation);
    }

    static Quote buildQuote(QuoteRequest request, PriceSnapshot[] prices, CustomerOrderSummary history) {
        List<QuoteLine> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (int i = 0; i < request.items().size(); i++) {
            Item item = request.items().get(i);
            PriceSnapshot price = prices[i];
            BigDecimal lineTotal = price.unitPrice().multiply(BigDecimal.valueOf(item.quantity()));
            lines.add(new QuoteLine(item.sku(), price.name(), price.category(), item.quantity(), price.unitPrice(), lineTotal));
            subtotal = subtotal.add(lineTotal);
        }
        int discountPercent = discountPercent(history);
        BigDecimal discount = subtotal.multiply(BigDecimal.valueOf(discountPercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        String version = prices.length > 0 ? prices[0].priceListVersion() : null;
        return new Quote(request.customerId(), lines, subtotal.setScale(2, RoundingMode.HALF_UP), discountPercent,
                discount, subtotal.subtract(discount).setScale(2, RoundingMode.HALF_UP), version, history);
    }

    /** Loyalty discount: 10% from 10 completed orders, 5% from 3; none if any order was HIGH risk. */
    static int discountPercent(CustomerOrderSummary history) {
        if (history.highRiskOrders() > 0) {
            return 0;
        }
        if (history.completedOrders() >= 10) {
            return 10;
        }
        return history.completedOrders() >= 3 ? 5 : 0;
    }

    /** Use Case 3 (asynchronous): store the order, enqueue the analysis, return immediately. */
    public Submission submitForRiskAnalysis(OrderRequest request) {
        validateCustomer(request.customerId());
        if (request.shippingAddress() == null || request.shippingAddress().isBlank()) {
            throw new IllegalArgumentException("shippingAddress is required");
        }
        if (request.shippingCountry() == null || !COUNTRY.matcher(request.shippingCountry()).matches()) {
            throw new IllegalArgumentException("shippingCountry must be a two-letter country code, e.g. DE");
        }
        validateItems(request.items());

        CreatedOrder order = orderClient.post("/orders", request, CreatedOrder.class); // status SUBMITTED
        RiskAnalysisMessage message = new RiskAnalysisMessage(order.id(), request.customerId(),
                request.shippingAddress(), request.shippingCountry(), request.items());
        try {
            publisher.publish(message);
        } catch (RiskMessagePublisher.PublishException e) {
            LOG.error("Publishing risk analysis for order {} failed: {}", order.id(), e.getMessage());
            setStatus(order.id(), "FAILED", e.getMessage());
            throw e;
        }
        // The message is in RabbitMQ now. If the processing service already picked it up,
        // the order is PROCESSING and this transition is refused (409), which is fine.
        setStatus(order.id(), "QUEUED", null);
        LOG.info("Order {} queued for risk analysis", order.id());
        return new Submission(order.id(), "QUEUED", "/api/orders/" + order.id() + "/status");
    }

    private void setStatus(long orderId, String status, String reason) {
        try {
            orderClient.put("/orders/" + orderId + "/status", new StatusUpdate(status, reason), Void.class);
        } catch (DownstreamException e) {
            LOG.info("Status of order {} not changed to {}: {}", orderId, status, e.getMessage());
        }
    }

    private static void validateCustomer(String customerId) {
        if (customerId == null || !CUSTOMER_ID.matcher(customerId).matches()) {
            throw new IllegalArgumentException("customerId must look like C-1234");
        }
    }

    /** Business rule: at least one item and a positive quantity for every item. */
    static void validateItems(List<Item> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("an order must contain at least one item");
        }
        if (items.size() > 20) {
            throw new IllegalArgumentException("an order may contain at most 20 items");
        }
        for (Item item : items) {
            if (item == null || item.sku() == null || item.sku().isBlank()) {
                throw new IllegalArgumentException("every item needs a sku");
            }
            if (item.quantity() <= 0 || item.quantity() > 1000) {
                throw new IllegalArgumentException("quantity of " + item.sku() + " must be between 1 and 1000");
            }
        }
    }
}
