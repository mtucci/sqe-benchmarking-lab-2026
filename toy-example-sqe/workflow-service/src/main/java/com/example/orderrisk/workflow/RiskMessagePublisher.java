package com.example.orderrisk.workflow;

import com.example.orderrisk.common.ServiceHttpClient;
import com.example.orderrisk.common.Telemetry;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Publishes risk-analysis messages to RabbitMQ: one direct exchange, one routing key, one queue,
 * one message type. Messages are persistent and every publish waits for the broker's
 * publisher confirm.
 * <p>
 * A single AMQP channel is shared by all request threads, so {@link #publish} is
 * {@code synchronized}: publishing is a serialised step.
 */
public class RiskMessagePublisher {

    private final Channel channel;
    private final String exchange;
    private final String routingKey;
    private final long confirmTimeoutMs;

    public RiskMessagePublisher(Connection connection, String exchange, String queue, String routingKey,
                                long confirmTimeoutMs) throws IOException {
        this.channel = connection.createChannel();
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.confirmTimeoutMs = confirmTimeoutMs;
        channel.exchangeDeclare(exchange, BuiltinExchangeType.DIRECT, true);
        channel.queueDeclare(queue, true, false, false, null);
        channel.queueBind(queue, exchange, routingKey);
        channel.confirmSelect();
    }

    public synchronized void publish(OrderWorkflow.RiskAnalysisMessage message) {
        Span span = Telemetry.tracer().spanBuilder(exchange + " publish")
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute("messaging.system", "rabbitmq")
                .setAttribute("messaging.operation.type", "publish")
                .setAttribute("messaging.destination.name", exchange)
                .setAttribute("messaging.rabbitmq.destination.routing_key", routingKey)
                .setAttribute("order.id", message.orderId())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            // The trace context travels to risk-processing-service in the message headers ("traceparent").
            Map<String, Object> headers = new HashMap<>();
            Telemetry.propagator().inject(Context.current(), headers, Map::put);
            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(2) // persistent
                    .messageId("order-" + message.orderId())
                    .type("OrderRiskAnalysisRequested")
                    .headers(headers)
                    .build();
            channel.basicPublish(exchange, routingKey, properties, ServiceHttpClient.JSON.writeValueAsBytes(message));
            if (!channel.waitForConfirms(confirmTimeoutMs)) {
                throw new PublishException("RabbitMQ did not accept the message (nack)", null);
            }
        } catch (PublishException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw new PublishException("message publication failed: " + e.getClass().getSimpleName(), e);
        } finally {
            span.end();
        }
    }

    public static class PublishException extends RuntimeException {
        public PublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
