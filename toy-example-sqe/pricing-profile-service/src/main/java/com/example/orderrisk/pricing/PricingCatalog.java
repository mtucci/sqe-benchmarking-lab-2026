package com.example.orderrisk.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic auxiliary data: a small fixed product catalog and customer profile summaries
 * that are derived from the customer identifier with a hash function.
 * The same input always produces the same output; there is no database and no randomness.
 */
public final class PricingCatalog {

    public static final String PRICE_LIST_VERSION = "PL-2026-01";
    public static final Pattern CUSTOMER_ID = Pattern.compile("C-\\d{4,8}");

    private static final String[] COUNTRIES = {"DE", "FR", "IT", "ES", "NL", "AT", "PL", "SE"};

    public record Product(String sku, String name, String category, BigDecimal unitPrice) {
    }

    public record PriceSnapshot(String sku, String name, String category, BigDecimal unitPrice, String priceListVersion) {
    }

    public record CustomerProfile(String customerId, int accountAgeDays, int previousOrders, int chargebacks,
                                  BigDecimal averageOrderValue, String homeCountry, String loyaltyTier) {
    }

    private static final Map<String, Product> PRODUCTS = new LinkedHashMap<>();

    static {
        add("SKU-1001", "Paperback novel", "BOOKS", "12.90");
        add("SKU-1002", "Cookbook", "BOOKS", "29.50");
        add("SKU-2001", "Ceramic mug set", "HOME", "24.00");
        add("SKU-2002", "Desk lamp", "HOME", "49.99");
        add("SKU-3001", "Cotton T-shirt", "CLOTHING", "19.95");
        add("SKU-3002", "Winter jacket", "CLOTHING", "149.00");
        add("SKU-4001", "Wireless headphones", "ELECTRONICS", "199.00");
        add("SKU-4002", "Smartphone", "ELECTRONICS", "699.00");
        add("SKU-4003", "Laptop", "ELECTRONICS", "1299.00");
        add("SKU-9001", "Gift card 50", "GIFT_CARD", "50.00");
        add("SKU-9002", "Gift card 200", "GIFT_CARD", "200.00");
    }

    private static void add(String sku, String name, String category, String price) {
        PRODUCTS.put(sku, new Product(sku, name, category, new BigDecimal(price)));
    }

    private PricingCatalog() {
    }

    public static List<Product> products() {
        return List.copyOf(PRODUCTS.values());
    }

    /** Returns one price snapshot per requested SKU (in request order). */
    public static List<PriceSnapshot> priceSnapshots(List<String> skus) {
        if (skus == null || skus.isEmpty()) {
            throw new IllegalArgumentException("at least one SKU is required");
        }
        List<PriceSnapshot> result = new ArrayList<>();
        for (String sku : skus) {
            Product p = PRODUCTS.get(sku);
            if (p == null) {
                throw new UnknownSkuException(sku);
            }
            result.add(new PriceSnapshot(p.sku(), p.name(), p.category(), p.unitPrice(), PRICE_LIST_VERSION));
        }
        return result;
    }

    /** Derives a coarse, deterministic customer-history summary from the customer id. */
    public static CustomerProfile customerProfile(String customerId) {
        if (customerId == null || !CUSTOMER_ID.matcher(customerId).matches()) {
            throw new IllegalArgumentException("customerId must look like C-1234");
        }
        int h = fnv1a(customerId);
        int accountAgeDays = 1 + (h & 0x7FF);                 // 1 .. 2048 days
        int previousOrders = (h >>> 11) % 40;                 // 0 .. 39
        int chargebacks = previousOrders == 0 ? 0 : ((h >>> 17) % 7 == 0 ? 1 + (h >>> 20) % 3 : 0);
        BigDecimal averageOrderValue = BigDecimal.valueOf(20 + (h >>> 5) % 380).setScale(2);
        String homeCountry = COUNTRIES[(h >>> 24) % COUNTRIES.length];
        String tier = previousOrders >= 20 ? "GOLD" : previousOrders >= 5 ? "SILVER" : "BRONZE";
        return new CustomerProfile(customerId, accountAgeDays, previousOrders, chargebacks,
                averageOrderValue, homeCountry, tier);
    }

    /** 32-bit FNV-1a hash, returned as a non-negative int. */
    static int fnv1a(String text) {
        int hash = 0x811C9DC5;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x01000193;
        }
        return hash & 0x7FFFFFFF;
    }

    public static class UnknownSkuException extends RuntimeException {
        public UnknownSkuException(String sku) {
            super("unknown SKU: " + sku);
        }
    }
}
