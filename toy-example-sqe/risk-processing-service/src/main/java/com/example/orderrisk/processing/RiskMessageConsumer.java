package com.example.orderrisk.processing;

import com.example.orderrisk.common.ServiceHttpClient;
import com.example.orderrisk.common.Telemetry;
import com.example.orderrisk.processing.RiskAnalyzer.RiskAnalysisMessage;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RabbitMQ consumer of the {@code order-risk-analysis} queue.
 * <p>
 * Concurrency model: RabbitMQ delivers messages on its own delivery thread; this consumer only
 * hands each message to a fixed-size {@link ThreadPoolExecutor} ({@code workerThreads} threads,
 * bounded queue of {@code queueCapacity} jobs). When the pool and its queue are full the job is
 * rejected and the order becomes REJECTED. A message is acknowledged only after its job has
 * finished (or was rejected), so {@code prefetchCount} limits the number of messages held by
 * this service; further messages wait in RabbitMQ.
 */
public class RiskMessageConsumer extends DefaultConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(RiskMessageConsumer.class);

    private final RiskAnalyzer analyzer;
    private final ThreadPoolExecutor workers;
    private final String queue;

    public RiskMessageConsumer(Channel channel, RiskAnalyzer analyzer, String queue, int workerThreads, int queueCapacity) {
        super(channel);
        this.analyzer = analyzer;
        this.queue = queue;
        AtomicInteger counter = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(workerThreads, workerThreads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> new Thread(r, "risk-worker-" + counter.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)
            throws IOException {
        // Continue the trace started by workflow-service: the trace context travels in the message headers.
        Context parent = Telemetry.propagator().extract(Context.root(), properties.getHeaders(), HEADER_GETTER);
        Span receiveSpan = Telemetry.tracer().spanBuilder(queue + " receive")
                .setParent(parent)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("messaging.system", "rabbitmq")
                .setAttribute("messaging.operation.type", "receive")
                .setAttribute("messaging.destination.name", queue)
                .setAttribute("messaging.message.id", String.valueOf(properties.getMessageId()))
                .startSpan();
        try (Scope ignored = receiveSpan.makeCurrent()) {
            RiskAnalysisMessage message = ServiceHttpClient.JSON.readValue(body, RiskAnalysisMessage.class);
            receiveSpan.setAttribute("order.id", message.orderId());
            long receivedAt = System.nanoTime();
            try {
                // Context.wrap carries the trace context over to the worker thread
                workers.execute(Context.current().wrap(() -> runJob(message, receivedAt, envelope.getDeliveryTag())));
            } catch (RejectedExecutionException e) {
                String reason = "processing capacity exceeded (" + workers.getActiveCount() + " workers busy, "
                        + workers.getQueue().size() + " jobs queued)";
                LOG.warn("Order {} rejected: {}", message.orderId(), reason);
                receiveSpan.setStatus(StatusCode.ERROR, "worker pool saturated");
                analyzer.reject(message.orderId(), reason);
                acknowledge(envelope.getDeliveryTag());
            }
        } catch (IOException e) {
            // Malformed message: it can never be processed, so drop it instead of redelivering it forever.
            LOG.error("Discarding malformed message: {}", e.getMessage());
            receiveSpan.recordException(e);
            receiveSpan.setStatus(StatusCode.ERROR);
            acknowledge(envelope.getDeliveryTag());
        } finally {
            receiveSpan.end();
        }
    }

    private void runJob(RiskAnalysisMessage message, long receivedAt, long deliveryTag) {
        Span span = Telemetry.tracer().spanBuilder("risk analysis job")
                .setAttribute("order.id", message.orderId())
                .setAttribute("job.wait_ms", (System.nanoTime() - receivedAt) / 1_000_000)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            analyzer.analyze(message);
        } catch (RuntimeException e) {
            LOG.error("Unexpected error in risk analysis of order {}", message.orderId(), e);
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
        } finally {
            span.end();
            acknowledge(deliveryTag);
        }
    }

    /** Channels are not meant to be shared between threads, so acknowledgements are serialised. */
    private void acknowledge(long deliveryTag) {
        synchronized (getChannel()) {
            try {
                getChannel().basicAck(deliveryTag, false);
            } catch (IOException e) {
                LOG.error("Could not acknowledge message {}: {}", deliveryTag, e.getMessage());
            }
        }
    }

    private static final TextMapGetter<Map<String, Object>> HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, Object> headers) {
            return headers.keySet();
        }

        @Override
        public String get(Map<String, Object> headers, String key) {
            Object value = headers == null ? null : headers.get(key);
            return value == null ? null : value.toString(); // RabbitMQ delivers strings as LongString
        }
    };
}
