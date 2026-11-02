package com.example.orderrisk.processing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A deterministic teaching model: apply country-specific risk rules to one order. */
public final class RiskScorer {
    public static final double MEDIUM_THRESHOLD = 35.0;
    public static final double HIGH_THRESHOLD = 65.0;
    public static final int MAX_RULE_COUNT = 1_000_000;

    private static final List<String> COUNTRIES = List.of(
            "DE", "FR", "IT", "ES", "NL", "AT", "PL", "SE", "BE", "DK",
            "US", "GB", "NG", "JP", "CA", "BR", "IN", "AU", "ZA", "CH");
    private static volatile RuleBook loadedRules;

    public record Item(String sku, String category, int quantity, BigDecimal unitPrice) { }
    public record Customer(int accountAgeDays, int previousOrders, int chargebacks,
                           BigDecimal averageOrderValue, String homeCountry) { }
    public record Input(List<Item> items, String shippingAddress, String shippingCountry, Customer customer) { }
    public record Result(double score, String category, BigDecimal totalAmount) { }

    /** Example: DE, amount >= 500, account age < 365 days -> add 3 points. */
    public record Rule(int id, String country, double minimumAmount, int youngerThanDays, int points) {
        public boolean matches(double amount, int accountAgeDays) {
            return amount >= minimumAmount && accountAgeDays < youngerThanDays;
        }
    }

    /** Immutable configuration shared by requests; contains rules, never cached order results. */
    public static final class RuleBook {
        private final List<Rule> allRules;
        private final Map<String, List<Rule>> byCountry;
        private final Map<String, Integer> possiblePoints;

        private RuleBook(List<Rule> rules) {
            allRules = List.copyOf(rules);
            Map<String, List<Rule>> index = new HashMap<>();
            Map<String, Integer> totals = new HashMap<>();
            for (Rule rule : rules) {
                index.computeIfAbsent(rule.country(), ignored -> new ArrayList<>()).add(rule);
                totals.merge(rule.country(), rule.points(), Integer::sum);
            }
            index.replaceAll((country, entries) -> List.copyOf(entries));
            byCountry = Map.copyOf(index);
            possiblePoints = Map.copyOf(totals);
        }

        public List<Rule> allRules() { return allRules; }
        public List<Rule> forCountry(String country) { return byCountry.getOrDefault(country, List.of()); }

        private double toScore(String country, int points) {
            int maximum = possiblePoints.getOrDefault(country, 0);
            return maximum == 0 ? 0 : 100.0 * points / maximum;
        }
    }

    public enum Implementation {
        BASELINE, OPTIMIZED;
        public static Implementation parse(String name) {
            if ("baseline".equals(name)) return BASELINE;
            if ("optimized".equals(name)) return OPTIMIZED;
            throw new IllegalArgumentException("implementation must be baseline or optimized");
        }
        public String parameter() { return name().toLowerCase(Locale.ROOT); }
    }

    private RiskScorer() { }

    /**
     * Load a synthetic rule collection and build its country index before benchmarking.
     * Every rule has a distinct ID and country/amount condition. Twenty countries are
     * interleaved, so a DE order uses about 1/20 of the rules. The rule collection is deterministic.
     * Keep only the most recently requested configuration to bound retained memory.
     */
    public static RuleBook rules(int ruleCount) {
        if (ruleCount < 1 || ruleCount > MAX_RULE_COUNT) {
            throw new IllegalArgumentException("ruleCount must be between 1 and " + MAX_RULE_COUNT);
        }
        RuleBook current = loadedRules;
        if (current != null && current.allRules().size() == ruleCount) return current;
        synchronized (RiskScorer.class) {
            current = loadedRules;
            if (current == null || current.allRules().size() != ruleCount) {
                List<Rule> entries = new ArrayList<>(ruleCount);
                for (int id = 0; id < ruleCount; id++) {
                    int group = id / COUNTRIES.size();
                    entries.add(new Rule(id, COUNTRIES.get(id % COUNTRIES.size()),
                            50.0 + group / 10.0, 30 + (group * 37) % 700, 1 + group % 5));
                }
                current = new RuleBook(entries);
                loadedRules = current;
            }
        }
        return current;
    }

    /** Convenience entry point for background order processing. */
    public static Result evaluate(Input input, int ruleCount) {
        return evaluate(input, rules(ruleCount), Implementation.BASELINE);
    }

    public static Result evaluate(Input input, int ruleCount, Implementation implementation) {
        return evaluate(input, rules(ruleCount), implementation);
    }

    /** Shared target at all three benchmark levels; rules and their index are already loaded. */
    public static Result evaluate(Input input, RuleBook rules, Implementation implementation) {
        double score = implementation == Implementation.BASELINE
                ? scoreBaseline(input, rules) : scoreOptimized(input, rules);
        double rounded = Math.round(score * 100.0) / 100.0;
        return new Result(rounded, category(rounded), totalAmount(input.items()));
    }

    public static double scoreBaseline(Input input, RuleBook rules) {
        double amount = totalAmount(input.items()).doubleValue();
        int points = 0;
        for (Rule rule : rules.allRules()) {
            if (rule.country().equals(input.shippingCountry())
                    && rule.matches(amount, input.customer().accountAgeDays())) {
                points += rule.points();
            }
        }
        return rules.toScore(input.shippingCountry(), points);
    }

    public static double scoreOptimized(Input input, RuleBook rules) {
        double amount = totalAmount(input.items()).doubleValue();
        int points = 0;
        for (Rule rule : rules.forCountry(input.shippingCountry())) {
            if (rule.matches(amount, input.customer().accountAgeDays())) {
                points += rule.points();
            }
        }
        return rules.toScore(input.shippingCountry(), points);
    }

    public static String category(double score) {
        if (score >= HIGH_THRESHOLD) return "HIGH";
        return score >= MEDIUM_THRESHOLD ? "MEDIUM" : "LOW";
    }

    public static BigDecimal totalAmount(List<Item> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (Item item : items) total = total.add(item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())));
        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
