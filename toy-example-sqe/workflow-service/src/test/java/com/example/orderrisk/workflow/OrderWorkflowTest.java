package com.example.orderrisk.workflow;

import com.example.orderrisk.workflow.OrderWorkflow.CustomerOrderSummary;
import com.example.orderrisk.workflow.OrderWorkflow.Item;
import com.example.orderrisk.workflow.OrderWorkflow.PriceSnapshot;
import com.example.orderrisk.workflow.OrderWorkflow.Quote;
import com.example.orderrisk.workflow.OrderWorkflow.QuoteRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderWorkflowTest {

    private static CustomerOrderSummary history(int completed, int highRisk) {
        return new CustomerOrderSummary("C-1001", completed, completed, highRisk, BigDecimal.ZERO);
    }

    @Test
    void quoteSumsLinesAndAppliesLoyaltyDiscount() {
        QuoteRequest request = new QuoteRequest("C-1001", List.of(new Item("SKU-1001", 2), new Item("SKU-2001", 1)));
        PriceSnapshot[] prices = {
                new PriceSnapshot("SKU-1001", "Paperback novel", "BOOKS", new BigDecimal("12.90"), "PL"),
                new PriceSnapshot("SKU-2001", "Ceramic mug set", "HOME", new BigDecimal("24.00"), "PL")};

        Quote quote = OrderWorkflow.buildQuote(request, prices, history(3, 0));

        assertThat(quote.subtotal()).isEqualByComparingTo("49.80");
        assertThat(quote.discountPercent()).isEqualTo(5);
        assertThat(quote.total()).isEqualByComparingTo("47.31");
    }

    @Test
    void highRiskHistoryCancelsDiscount() {
        assertThat(OrderWorkflow.discountPercent(history(12, 1))).isZero();
    }

    @Test
    void nonPositiveQuantityIsRejected() {
        assertThatThrownBy(() -> OrderWorkflow.validateItems(List.of(new Item("SKU-1001", 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
    }
}
