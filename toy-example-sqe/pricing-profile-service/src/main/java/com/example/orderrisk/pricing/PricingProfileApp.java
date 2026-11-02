package com.example.orderrisk.pricing;

import com.example.orderrisk.common.ServiceApp;
import com.example.orderrisk.common.ServiceConfig;
import com.example.orderrisk.common.Telemetry;
import io.javalin.Javalin;
import io.javalin.http.HttpResponseException;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auxiliary service: deterministic product price snapshots and customer profile summaries.
 * <p>
 * Concurrency model: each request arrives on a Jetty thread, which hands the lookup to a
 * fixed-size worker pool ({@code pricing.threadPoolSize} threads, bounded queue of
 * {@code pricing.queueCapacity}) and waits for the result. Each lookup holds a worker for
 * {@code pricing.processingDelayMs} milliseconds. When the queue is full the request is
 * rejected with HTTP 503.
 */
public final class PricingProfileApp {

    private static final Logger LOG = LoggerFactory.getLogger(PricingProfileApp.class);

    public record PriceRequest(List<String> skus) {
    }

    private final ThreadPoolExecutor workers;
    private final long processingDelayMs;

    PricingProfileApp(int threadPoolSize, int queueCapacity, long processingDelayMs) {
        AtomicInteger counter = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(threadPoolSize, threadPoolSize, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> new Thread(r, "pricing-worker-" + counter.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
        this.processingDelayMs = processingDelayMs;
    }

    public static void main(String[] args) {
        ServiceConfig config = ServiceConfig.load();
        Telemetry.init("pricing-profile-service", config);
        PricingProfileApp service = new PricingProfileApp(
                config.getInt("pricing.threadPoolSize"),
                config.getInt("pricing.queueCapacity"),
                config.getInt("pricing.processingDelayMs"));
        Javalin app = ServiceApp.create("pricing-profile-service", config, javalin -> { });
        service.registerRoutes(app);
        app.start(config.getInt("server.port"));
        LOG.info("pricing-profile-service started: threadPoolSize={}, queueCapacity={}, processingDelayMs={}",
                config.get("pricing.threadPoolSize"), config.get("pricing.queueCapacity"),
                config.get("pricing.processingDelayMs"));
    }

    void registerRoutes(Javalin app) {
        // Product catalog for the GUI forms (supporting endpoint, not delayed)
        app.get("/products", ctx -> ctx.json(PricingCatalog.products()));

        app.post("/price-snapshots", ctx -> {
            PriceRequest request = ctx.bodyAsClass(PriceRequest.class);
            ctx.json(runOnWorker(() -> PricingCatalog.priceSnapshots(request.skus())));
        });

        app.get("/customers/{customerId}/profile", ctx -> {
            String customerId = ctx.pathParam("customerId");
            ctx.json(runOnWorker(() -> PricingCatalog.customerProfile(customerId)));
        });

        app.exception(PricingCatalog.UnknownSkuException.class, (e, ctx) ->
                ctx.status(422).json(Map.of("error", e.getMessage())));
    }

    /** Executes a lookup on the bounded worker pool and waits for its result. */
    private <T> T runOnWorker(Callable<T> lookup) throws Exception {
        Callable<T> task = () -> {
            Thread.sleep(processingDelayMs); // simulated lookup latency
            return lookup.call();
        };
        Future<T> future;
        try {
            // Context.wrap keeps the current trace context on the worker thread
            future = workers.submit(Context.current().wrap(task));
        } catch (RejectedExecutionException e) {
            LOG.warn("Worker pool saturated (active={}, queued={}), rejecting request",
                    workers.getActiveCount(), workers.getQueue().size());
            throw new HttpResponseException(503, "pricing-profile-service is overloaded, try again later");
        }
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }
}
