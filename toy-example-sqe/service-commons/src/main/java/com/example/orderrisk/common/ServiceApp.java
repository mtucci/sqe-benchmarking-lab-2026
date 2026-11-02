package com.example.orderrisk.common;

import com.fasterxml.jackson.core.JacksonException;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.HttpResponseException;
import io.javalin.json.JavalinJackson;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Creates a Javalin HTTP server with the settings shared by all five services:
 * a bounded Jetty request thread pool, JSON (Jackson), tracing, a {@code /health} endpoint
 * and uniform JSON error responses of the form {"error": "..."}.
 */
public final class ServiceApp {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceApp.class);

    private ServiceApp() {
    }

    public static Javalin create(String serviceName, ServiceConfig config, Consumer<JavalinConfig> extraConfig) {
        int maxThreads = config.getInt("server.maxThreads");
        Javalin app = Javalin.create(javalin -> {
            javalin.showJavalinBanner = false;
            // Jetty worker threads that execute request handlers (one request = one thread).
            // Jetty itself reserves a few of them for accepting/selecting connections.
            javalin.jetty.threadPool = new QueuedThreadPool(maxThreads, Math.min(8, maxThreads), 60_000);
            javalin.jsonMapper(new JavalinJackson(ServiceHttpClient.JSON, false));
            extraConfig.accept(javalin);
        });
        Telemetry.instrumentServer(app);

        app.get("/health", ctx -> ctx.json(Map.of("status", "UP", "service", serviceName)));

        app.exception(IllegalArgumentException.class, (e, ctx) ->
                ctx.status(400).json(Map.of("error", e.getMessage())));
        app.exception(JacksonException.class, (e, ctx) ->
                ctx.status(400).json(Map.of("error", "malformed JSON request body: " + e.getOriginalMessage())));
        app.exception(DownstreamException.class, (e, ctx) -> {
            LOG.warn("Downstream call to {} failed with status {}: {}", e.service(), e.status(), e.getMessage());
            ctx.status(statusFor(e)).json(Map.of("error", e.getMessage()));
        });
        app.exception(HttpResponseException.class, (e, ctx) ->
                ctx.status(e.getStatus()).json(Map.of("error", e.getMessage())));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("Unexpected error while handling {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).json(Map.of("error", "internal error: " + e.getClass().getSimpleName()));
        });
        return app;
    }

    /**
     * Maps a failed downstream call to the status returned to our own caller:
     * 4xx answers are passed through (e.g. 404 unknown order, 400 invalid input),
     * an overloaded or unreachable dependency gives 503, any other server error gives 502.
     */
    public static int statusFor(DownstreamException e) {
        if (e.isClientError()) {
            return e.status();
        }
        if (e.status() == 0 || e.status() == 503) {
            return 503;
        }
        return 502;
    }
}
