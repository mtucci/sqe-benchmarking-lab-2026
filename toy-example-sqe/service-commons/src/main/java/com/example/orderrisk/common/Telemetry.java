package com.example.orderrisk.common;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenTelemetry set-up shared by all services (tracing only, no metrics, no logs export).
 * <p>
 * Spans are exported with OTLP/HTTP to the OpenTelemetry Collector, which forwards them to Tempo.
 * Trace context is propagated with the W3C {@code traceparent} header, both in HTTP requests
 * and in RabbitMQ message headers.
 */
public final class Telemetry {

    private static final Logger LOG = LoggerFactory.getLogger(Telemetry.class);
    private static final String SPAN_ATTRIBUTE = "otel.server.span";
    private static final String SCOPE_ATTRIBUTE = "otel.server.scope";

    private static OpenTelemetry openTelemetry = OpenTelemetry.noop();

    private Telemetry() {
    }

    /** Initialises the tracing SDK. Must be called once, at service start-up. */
    public static synchronized void init(String serviceName, ServiceConfig config) {
        if (!config.getBoolean("tracing.enabled")) {
            LOG.info("OpenTelemetry tracing disabled");
            openTelemetry = OpenTelemetry.propagating(ContextPropagators.create(W3CTraceContextPropagator.getInstance()));
            return;
        }
        String endpoint = config.get("tracing.otlpEndpoint") + "/v1/traces";
        Resource resource = Resource.getDefault().merge(Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"), serviceName)));
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(
                        OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build()).build())
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(sdk::close));
        openTelemetry = sdk;
        LOG.info("OpenTelemetry tracing enabled, exporting spans for '{}' to {}", serviceName, endpoint);
    }

    public static Tracer tracer() {
        return openTelemetry.getTracer("com.example.orderrisk");
    }

    public static TextMapPropagator propagator() {
        return openTelemetry.getPropagators().getTextMapPropagator();
    }

    /**
     * Creates one SERVER span per incoming HTTP request. The parent context is extracted from
     * the {@code traceparent} header so the span joins the caller's trace.
     * Health checks and static files are not traced to keep the trace volume low.
     */
    public static void instrumentServer(Javalin app) {
        app.before(ctx -> {
            if (!isTraced(ctx)) {
                return;
            }
            io.opentelemetry.context.Context parent = propagator()
                    .extract(io.opentelemetry.context.Context.current(), ctx, HEADER_GETTER);
            Span span = tracer().spanBuilder(ctx.method() + " " + ctx.path())
                    .setParent(parent)
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("http.request.method", ctx.method().name())
                    .setAttribute("url.path", ctx.path())
                    .startSpan();
            ctx.attribute(SPAN_ATTRIBUTE, span);
            ctx.attribute(SCOPE_ATTRIBUTE, span.makeCurrent());
        });
        app.after(ctx -> {
            Span span = ctx.attribute(SPAN_ATTRIBUTE);
            Scope scope = ctx.attribute(SCOPE_ATTRIBUTE);
            if (span == null || scope == null) {
                return;
            }
            String route = ctx.endpointHandlerPath();
            if (route != null && !route.isEmpty() && !"*".equals(route)) {
                span.updateName(ctx.method() + " " + route);
                span.setAttribute("http.route", route);
            }
            int status = ctx.statusCode();
            span.setAttribute("http.response.status_code", status);
            if (status >= 500) {
                span.setStatus(StatusCode.ERROR);
            }
            scope.close();
            span.end();
        });
    }

    private static boolean isTraced(Context ctx) {
        String path = ctx.path();
        return !path.equals("/health") && !path.startsWith("/css/") && !path.equals("/favicon.ico");
    }

    private static final TextMapGetter<Context> HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Context ctx) {
            return ctx.headerMap().keySet();
        }

        @Override
        public String get(Context ctx, String key) {
            return ctx == null ? null : ctx.header(key);
        }
    };
}
