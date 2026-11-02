package com.example.orderrisk.gateway;

import com.example.orderrisk.common.DownstreamException;
import com.example.orderrisk.common.ServiceApp;
import com.example.orderrisk.common.ServiceConfig;
import com.example.orderrisk.common.ServiceHttpClient;
import com.example.orderrisk.common.Telemetry;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.staticfiles.Location;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.ClasspathLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Gateway service: the only service users talk to. It serves the server-rendered GUI
 * ({@link WebController}) and the external REST API ({@link ApiController}) and forwards every
 * request to order-service or workflow-service. It contains no domain logic.
 */
public final class GatewayApp {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayApp.class);

    private static final PebbleEngine PEBBLE = createPebble();

    private GatewayApp() {
    }

    public static void main(String[] args) {
        ServiceConfig config = ServiceConfig.load();
        Telemetry.init("gateway-service", config);

        Duration timeout = Duration.ofMillis(config.getInt("http.client.timeoutMs"));
        ServiceHttpClient orderClient = new ServiceHttpClient("order-service", config.get("order.baseUrl"), timeout);
        ServiceHttpClient workflowClient = new ServiceHttpClient("workflow-service", config.get("workflow.baseUrl"), timeout);
        ServiceHttpClient pricingClient = new ServiceHttpClient("pricing-profile-service", config.get("pricing.baseUrl"), timeout);

        Javalin app = ServiceApp.create("gateway-service", config,
                javalin -> javalin.staticFiles.add("/public", Location.CLASSPATH));
        new ApiController(orderClient, workflowClient).registerRoutes(app);
        new WebController(orderClient, workflowClient, pricingClient).registerRoutes(app);
        registerErrorPages(app);
        app.start(config.getInt("server.port"));
        LOG.info("gateway-service started on port {}", config.get("server.port"));
    }

    /** REST clients (/api/...) get JSON errors from ServiceApp; browsers get an HTML error page. */
    static void registerErrorPages(Javalin app) {
        app.exception(DownstreamException.class, (e, ctx) ->
                error(ctx, ServiceApp.statusFor(e), e.getMessage()));
        app.exception(IllegalArgumentException.class, (e, ctx) -> error(ctx, 400, e.getMessage()));
        app.exception(HttpResponseException.class, (e, ctx) -> error(ctx, e.getStatus(), e.getMessage()));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("Unexpected error while handling {} {}", ctx.method(), ctx.path(), e);
            error(ctx, 500, "internal error: " + e.getClass().getSimpleName());
        });
        app.error(404, ctx -> {
            if (ctx.result() == null || ctx.result().isEmpty()) {
                error(ctx, 404, "page not found");
            }
        });
    }

    private static void error(Context ctx, int status, String message) {
        ctx.status(status);
        if (ctx.path().startsWith("/api/")) {
            ctx.json(Map.of("error", message));
        } else {
            Map<String, Object> model = new HashMap<>();
            model.put("status", status);
            model.put("message", message);
            ctx.html(render("error", model));
        }
    }

    static String render(String template, Map<String, Object> model) {
        StringWriter writer = new StringWriter();
        try {
            PEBBLE.getTemplate(template).evaluate(writer, model);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return writer.toString();
    }

    private static PebbleEngine createPebble() {
        ClasspathLoader loader = new ClasspathLoader();
        loader.setPrefix("templates");
        loader.setSuffix(".peb");
        return new PebbleEngine.Builder().loader(loader).build();
    }
}
