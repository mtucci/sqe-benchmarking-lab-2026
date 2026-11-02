package com.example.orderrisk.processing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskScorerTest {

    private static final RiskScorer.Customer LOYAL_CUSTOMER =
            new RiskScorer.Customer(1500, 30, 0, new BigDecimal("60.00"), "DE");
    private static final RiskScorer.Customer NEW_CUSTOMER =
            new RiskScorer.Customer(3, 0, 0, new BigDecimal("40.00"), "DE");

    private static RiskScorer.Input bookOrder() {
        return new RiskScorer.Input(List.of(new RiskScorer.Item("SKU-1001", "BOOKS", 2, new BigDecimal("12.90"))),
                "Main Street 1, Berlin", "DE", LOYAL_CUSTOMER);
    }

    private static RiskScorer.Input electronicsOrder() {
        return new RiskScorer.Input(List.of(
                new RiskScorer.Item("SKU-4003", "ELECTRONICS", 2, new BigDecimal("1299.00")),
                new RiskScorer.Item("SKU-9002", "GIFT_CARD", 5, new BigDecimal("200.00"))),
                "PO Box 42, Lagos", "NG", NEW_CUSTOMER);
    }

    @Test
    void sameInputGivesSameResult() {
        assertThat(RiskScorer.evaluate(electronicsOrder(), 1000))
                .isEqualTo(RiskScorer.evaluate(electronicsOrder(), 1000));
    }

    @Test
    void smallOrderOfAnEstablishedCustomerIsLowRisk() {
        RiskScorer.Result result = RiskScorer.evaluate(bookOrder(), 1000);

        assertThat(result.category()).isEqualTo("LOW");
        assertThat(result.totalAmount()).isEqualByComparingTo("25.80");
    }

    @Test
    void largeOrderOfANewCustomerIsHighRisk() {
        assertThat(RiskScorer.evaluate(electronicsOrder(), 1000).category()).isEqualTo("HIGH");
    }

    @Test
    void categoriesUseFixedThresholds() {
        assertThat(RiskScorer.category(34.99)).isEqualTo("LOW");
        assertThat(RiskScorer.category(35.0)).isEqualTo("MEDIUM");
        assertThat(RiskScorer.category(65.0)).isEqualTo("HIGH");
    }
    @Test
    void indexedAndFullScanScoresAgreeAcrossCountriesCatalogSizesAndInputs() {
        Random random = new Random(2026);
        for (int count : new int[]{1, 19, 20, 21, 99, 1000, 100000, 1000000}) {
            RiskScorer.RuleBook rules = RiskScorer.rules(count);
            for (String country : List.of("DE", "FR", "NG", "CH", "ZZ")) {
                for (int sample = 0; sample < 4; sample++) {
                    RiskScorer.Input input = new RiskScorer.Input(List.of(new RiskScorer.Item(
                            "SKU-1001", "BOOKS", 1, BigDecimal.valueOf(random.nextInt(1000000), 2))),
                            "Address", country, new RiskScorer.Customer(random.nextInt(2000), 0, 0,
                            BigDecimal.ZERO, "DE"));
                    assertThat(RiskScorer.evaluate(input, rules, RiskScorer.Implementation.OPTIMIZED))
                            .as("country %s, rule count %s", country, count)
                            .isEqualTo(RiskScorer.evaluate(input, rules, RiskScorer.Implementation.BASELINE));
                }
            }
        }
    }

    @Test
    void rulesHaveMeaningfulInclusiveAmountAndExclusiveAgeConditions() {
        RiskScorer.Rule rule = new RiskScorer.Rule(1, "DE", 500, 365, 3);
        assertThat(rule.matches(500, 364)).isTrue();
        assertThat(rule.matches(499.99, 364)).isFalse();
        assertThat(rule.matches(500, 365)).isFalse();
    }

    @Test
    void indexContainsExactlyTheRelevantRulesAndConfigurationsStayImmutable() {
        RiskScorer.RuleBook rules = RiskScorer.rules(100000);
        assertThat(rules.allRules()).hasSize(100000);
        assertThat(rules.allRules().stream().map(RiskScorer.Rule::id).distinct().count()).isEqualTo(100000);
        assertThat(rules.forCountry("DE")).hasSize(5000)
                .containsExactlyElementsOf(rules.allRules().stream().filter(r -> r.country().equals("DE")).toList());
        assertThat(rules.forCountry("ZZ")).isEmpty();
        assertThatThrownBy(() -> rules.allRules().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> rules.forCountry("DE").clear()).isInstanceOf(UnsupportedOperationException.class);
        RiskScorer.rules(20);
        assertThat(rules.allRules()).hasSize(100000);
    }

    @Test
    void scoringUsesOnlyRulesForTheOrdersCountryAndScalesPointsTo100() {
        RiskScorer.RuleBook rules = RiskScorer.rules(1); // One DE rule: >= 50, age < 30, +1.
        for (RiskScorer.Implementation implementation : RiskScorer.Implementation.values()) {
            RiskScorer.Input match = new RiskScorer.Input(List.of(new RiskScorer.Item(
                    "SKU-1001", "BOOKS", 1, new BigDecimal("50.00"))), "Address", "DE", NEW_CUSTOMER);
            assertThat(RiskScorer.evaluate(match, rules, implementation).score()).isEqualTo(100.0);
            RiskScorer.Input otherCountry = new RiskScorer.Input(match.items(), "Address", "FR", NEW_CUSTOMER);
            assertThat(RiskScorer.evaluate(otherCountry, rules, implementation).score()).isZero();
            assertThat(RiskScorer.evaluate(bookOrder(), rules, implementation).score()).isZero();
        }
    }

    @Test
    void invalidCatalogSizesAndImplementationNamesAreRejected() {
        for (int count : new int[]{-1, 0, 1000001}) {
            assertThatThrownBy(() -> RiskScorer.rules(count)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> RiskScorer.Implementation.parse("other")).isInstanceOf(IllegalArgumentException.class);
    }
}
