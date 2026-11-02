package com.example.orderrisk.processing;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskEvaluationApiTest {
    private static RiskScorer.Input input() {
        return new RiskScorer.Input(List.of(
                new RiskScorer.Item("SKU-1001", "BOOKS", 2, new BigDecimal("12.90")),
                new RiskScorer.Item("SKU-4002", "ELECTRONICS", 1, new BigDecimal("699.00"))),
                "Main Street 1, Berlin", "DE", new RiskScorer.Customer(316, 22, 0, new BigDecimal("377.00"), "SE"));
    }

    @Test
    void componentEvaluatesExactlyTheSharedKernelForBothImplementations() {
        for (String implementation : List.of("baseline", "optimized")) {
            RiskEvaluationApi.Evaluation actual = RiskEvaluationApi.evaluate(new RiskEvaluationApi.Request(input(), 100000, implementation));
            assertThat(actual.implementation()).isEqualTo(implementation);
            assertThat(actual.risk()).isEqualTo(RiskScorer.evaluate(input(), 100000));
            assertThat(actual.risk().totalAmount()).isEqualByComparingTo("724.80");
        }
    }

    @Test
    void invalidEvaluationRequestsAreRejected() {
        for (int ruleCount : new int[]{0, -1, 1000001}) {
            assertThatThrownBy(() -> RiskEvaluationApi.evaluate(new RiskEvaluationApi.Request(input(), ruleCount, "baseline")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> RiskEvaluationApi.evaluate(new RiskEvaluationApi.Request(input(), 1, "unknown")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiskEvaluationApi.evaluate(new RiskEvaluationApi.Request(null, 1, "baseline")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
