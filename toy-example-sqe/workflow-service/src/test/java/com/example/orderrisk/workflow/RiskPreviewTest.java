package com.example.orderrisk.workflow;

import com.example.orderrisk.common.ServiceHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskPreviewTest {
    @Test
    void previewBuildsTheSharedInputAndWaitsForRiskEvaluation() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonNode> evaluated = new AtomicReference<>();
        server.createContext("/", exchange -> {
            String response = switch (exchange.getRequestURI().getPath()) {
                case "/price-snapshots" -> """
                    [{"sku":"SKU-1001","name":"Book","category":"BOOKS","unitPrice":12.90,"priceListVersion":"PL"},
                     {"sku":"SKU-4002","name":"Phone","category":"ELECTRONICS","unitPrice":699,"priceListVersion":"PL"}]
                    """;
                case "/customers/C-2026/order-summary" -> """
                    {"customerId":"C-2026","orderCount":0,"completedOrders":0,"highRiskOrders":0,"completedAmount":0}
                    """;
                case "/customers/C-2026/profile" -> """
                    {"customerId":"C-2026","accountAgeDays":316,"previousOrders":22,"chargebacks":0,"averageOrderValue":377,"homeCountry":"SE","loyaltyTier":"GOLD"}
                    """;
                case "/risk-evaluations" -> {
                    evaluated.set(ServiceHttpClient.JSON.readTree(exchange.getRequestBody()));
                    yield """
                        {"implementation":"optimized","ruleCount":100000,"risk":{"score":42.12,"category":"MEDIUM","totalAmount":724.80}}
                        """;
                }
                default -> throw new AssertionError("Unexpected request: " + exchange.getRequestURI());
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ServiceHttpClient client = new ServiceHttpClient("fixture", base, Duration.ofSeconds(2));
            OrderWorkflow workflow = new OrderWorkflow(client, client, null, client);
            OrderWorkflow.RiskPreview result = workflow.riskPreview(new OrderWorkflow.RiskPreviewRequest("C-2026",
                    "Main Street 1, Berlin", "DE", List.of(new OrderWorkflow.Item("SKU-1001", 2),
                    new OrderWorkflow.Item("SKU-4002", 1)), 100000, "optimized"));
            assertThat(result.quote().total()).isEqualByComparingTo("724.80");
            assertThat(result.evaluation().path("risk").path("score").asDouble()).isEqualTo(42.12);
            JsonNode request = evaluated.get();
            assertThat(request.path("implementation").asText()).isEqualTo("optimized");
            assertThat(request.path("ruleCount").asInt()).isEqualTo(100000);
            assertThat(request.at("/input/items/0/quantity").asInt()).isEqualTo(2);
            assertThat(request.at("/input/items/1/unitPrice").decimalValue()).isEqualByComparingTo("699");
            assertThat(request.at("/input/customer/accountAgeDays").asInt()).isEqualTo(316);
            assertThat(request.at("/input/customer/previousOrders").asInt()).isEqualTo(22);
            assertThat(request.at("/input/customer/homeCountry").asText()).isEqualTo("SE");
            assertThat(request.at("/input/shippingAddress").asText()).isEqualTo("Main Street 1, Berlin");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidImplementationIsRejectedBeforeCallingDownstreamServices() {
        OrderWorkflow workflow = new OrderWorkflow(null, null, null, null);
        assertThatThrownBy(() -> workflow.riskPreview(new OrderWorkflow.RiskPreviewRequest("C-2026",
                "Address", "DE", List.of(new OrderWorkflow.Item("SKU-1001", 1)), 100000, "unknown")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("implementation");
    }
}
