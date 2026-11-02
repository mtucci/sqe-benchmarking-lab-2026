package com.example.orderrisk.order;

import com.example.orderrisk.order.OrderModels.ItemPrice;
import com.example.orderrisk.order.OrderModels.NewOrder;
import com.example.orderrisk.order.OrderModels.NewOrderItem;
import com.example.orderrisk.order.OrderModels.Order;
import com.example.orderrisk.order.OrderModels.OrderStatus;
import com.example.orderrisk.order.OrderModels.RiskResultUpdate;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderRepositoryTest {

    private OrderRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:test-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        repository = new OrderRepository(dataSource);
        repository.createSchema();
    }

    private static NewOrder sampleOrder() {
        return new NewOrder("C-1001", "Main Street 1, Berlin", "DE", List.of(new NewOrderItem("SKU-1001", 2)));
    }

    @Test
    void newOrderStartsInSubmittedState() {
        Order order = repository.create(sampleOrder());

        assertThat(order.status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(order.items()).hasSize(1);
    }

    @Test
    void completedOrderStoresRiskResult() {
        long id = repository.create(sampleOrder()).id();
        repository.updateStatus(id, OrderStatus.PROCESSING, null);

        Order order = repository.saveRiskResult(id, new RiskResultUpdate(12.5, "LOW", 10,
                new BigDecimal("25.80"), List.of(new ItemPrice("SKU-1001", new BigDecimal("12.90")))));

        assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.riskResult().category()).isEqualTo("LOW");
        assertThat(order.items().get(0).unitPrice()).isEqualByComparingTo("12.90");
    }

    @Test
    void stateMachineRefusesBackwardTransition() {
        long id = repository.create(sampleOrder()).id();
        repository.updateStatus(id, OrderStatus.PROCESSING, null);

        assertThatThrownBy(() -> repository.updateStatus(id, OrderStatus.QUEUED, null))
                .isInstanceOf(OrderModels.InvalidTransitionException.class);
    }

    @Test
    void orderWithoutItemsIsInvalid() {
        NewOrder empty = new NewOrder("C-1001", "Main Street 1", "DE", List.of());

        assertThatThrownBy(empty::validate).hasMessageContaining("at least one item");
    }
}
