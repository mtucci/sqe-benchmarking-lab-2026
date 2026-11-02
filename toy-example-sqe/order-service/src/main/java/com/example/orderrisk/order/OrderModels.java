package com.example.orderrisk.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Data transfer objects of the order-service API and the order state machine. */
public final class OrderModels {

    private OrderModels() {
    }

    public enum OrderStatus { SUBMITTED, QUEUED, PROCESSING, COMPLETED, FAILED, REJECTED }

    /**
     * Allowed state transitions: target status -> statuses it may be reached from.
     * An update that does not respect this table is refused with HTTP 409.
     */
    public static Set<OrderStatus> allowedPredecessors(OrderStatus target) {
        return switch (target) {
            case SUBMITTED -> Set.of();
            case QUEUED -> Set.of(OrderStatus.SUBMITTED);
            case PROCESSING -> Set.of(OrderStatus.SUBMITTED, OrderStatus.QUEUED);
            case COMPLETED -> Set.of(OrderStatus.PROCESSING);
            case FAILED -> Set.of(OrderStatus.SUBMITTED, OrderStatus.QUEUED, OrderStatus.PROCESSING);
            case REJECTED -> Set.of(OrderStatus.SUBMITTED, OrderStatus.QUEUED);
        };
    }

    public record NewOrderItem(String sku, int quantity) {
    }

    public record NewOrder(String customerId, String shippingAddress, String shippingCountry, List<NewOrderItem> items) {

        private static final Pattern CUSTOMER_ID = Pattern.compile("C-\\d{4,8}");
        private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

        /** Business rules: at least one item, and a positive quantity for every item. */
        public void validate() {
            if (customerId == null || !CUSTOMER_ID.matcher(customerId).matches()) {
                throw new IllegalArgumentException("customerId must look like C-1234");
            }
            if (shippingAddress == null || shippingAddress.isBlank() || shippingAddress.length() > 200) {
                throw new IllegalArgumentException("shippingAddress is required (max 200 characters)");
            }
            if (shippingCountry == null || !COUNTRY.matcher(shippingCountry).matches()) {
                throw new IllegalArgumentException("shippingCountry must be a two-letter country code, e.g. DE");
            }
            if (items == null || items.isEmpty()) {
                throw new IllegalArgumentException("an order must contain at least one item");
            }
            if (items.size() > 20) {
                throw new IllegalArgumentException("an order may contain at most 20 items");
            }
            for (NewOrderItem item : items) {
                if (item == null || item.sku() == null || item.sku().isBlank()) {
                    throw new IllegalArgumentException("every item needs a sku");
                }
                if (item.quantity() <= 0 || item.quantity() > 1000) {
                    throw new IllegalArgumentException("quantity of " + item.sku() + " must be between 1 and 1000");
                }
            }
        }
    }

    public record OrderItem(int lineNo, String sku, int quantity, BigDecimal unitPrice) {
    }

    public record RiskResult(double score, String category, int ruleCount, String evaluatedAt) {
    }

    public record Order(long id, String customerId, String shippingAddress, String shippingCountry,
                        OrderStatus status, String statusReason, BigDecimal totalAmount,
                        String createdAt, String updatedAt, List<OrderItem> items, RiskResult riskResult) {
    }

    public record OrderSummary(long id, String customerId, OrderStatus status, BigDecimal totalAmount,
                               String riskCategory, String createdAt) {
    }

    /** Use Case 1 response: the stored order plus the customer profile from pricing-profile-service. */
    public record OrderDetails(Order order, Object customerProfile) {
    }

    public record StatusUpdate(OrderStatus status, String reason) {
    }

    public record ItemPrice(String sku, BigDecimal unitPrice) {
    }

    public record RiskResultUpdate(double score, String category, int ruleCount,
                                   BigDecimal totalAmount, List<ItemPrice> itemPrices) {
    }

    public record CustomerOrderSummary(String customerId, int orderCount, int completedOrders,
                                       int highRiskOrders, BigDecimal completedAmount) {
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(long id) {
            super("order " + id + " not found");
        }
    }

    public static class InvalidTransitionException extends RuntimeException {
        public InvalidTransitionException(long id, OrderStatus current, OrderStatus target) {
            super("order " + id + " cannot change from " + current + " to " + target);
        }
    }
}
