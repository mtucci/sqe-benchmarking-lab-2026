package com.example.orderrisk.gateway.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Seed end-to-end smoke tests: one per use case, all through the gateway REST API.
 * Requires the complete system to be running (docker compose up --build).
 */
class UseCasesE2ETest {

    private static final String ORDER = """
            {"customerId": "C-1001", "shippingAddress": "Main Street 1, Berlin", "shippingCountry": "DE",
             "items": [{"sku": "SKU-1001", "quantity": 2}, {"sku": "SKU-2001", "quantity": 1}]}""";

    @BeforeAll
    static void configure() {
        RestAssured.baseURI = System.getProperty("e2e.baseUrl", "http://localhost:8080");
    }

    /** Submits an order (Use Case 3) and returns its id; used to have an order to look at. */
    private static long submitOrder() {
        return given().contentType(ContentType.JSON).body(ORDER)
                .when().post("/api/orders")
                .then().statusCode(202)
                .extract().jsonPath().getLong("orderId");
    }

    @Test
    void useCase1_orderDetailsIncludeCustomerProfile() {
        long orderId = submitOrder();

        given().when().get("/api/orders/{id}", orderId)
                .then().statusCode(200)
                .body("order.id", equalTo((int) orderId))
                .body("order.items.size()", equalTo(2))
                .body("customerProfile.customerId", equalTo("C-1001"));
    }

    @Test
    void useCase2_quoteIsPricedSynchronously() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"customerId": "C-1001", "items": [{"sku": "SKU-1001", "quantity": 2}]}""")
                .when().post("/api/quotes")
                .then().statusCode(200)
                .body("lines[0].unitPrice", equalTo(12.90f))
                .body("subtotal", equalTo(25.80f))
                .body("customerHistory", notNullValue());
    }

    @Test
    void useCase3_submissionReturnsBeforeAnalysisAndCompletesLater() throws InterruptedException {
        long orderId = submitOrder();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        String status;
        do {
            Thread.sleep(250);
            status = given().when().get("/api/orders/{id}/status", orderId)
                    .then().statusCode(200).extract().jsonPath().getString("status");
        } while (!status.equals("COMPLETED") && !status.equals("FAILED") && !status.equals("REJECTED")
                && Instant.now().isBefore(deadline));

        assertThat(status).isEqualTo("COMPLETED");
        given().when().get("/api/orders/{id}/status", orderId)
                .then().body("riskResult.category", notNullValue())
                .body("totalAmount", equalTo(49.80f));
    }
}
