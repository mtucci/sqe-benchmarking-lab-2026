package com.example.orderrisk.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Small synchronous JSON-over-HTTP client used for all service-to-service calls.
 * <p>
 * Every call is blocking: the calling thread waits for the response. Each call creates a
 * CLIENT span and injects the W3C {@code traceparent} header, so the downstream service's
 * SERVER span becomes a child of this span.
 */
public final class ServiceHttpClient {

    public static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true) // exact money amounts
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final String serviceName;
    private final String baseUrl;
    private final Duration timeout;

    public ServiceHttpClient(String serviceName, String baseUrl, Duration timeout) {
        this.serviceName = serviceName;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
    }

    public <T> T get(String path, Class<T> responseType) {
        return send("GET", path, null, responseType);
    }

    public <T> T post(String path, Object body, Class<T> responseType) {
        return send("POST", path, body, responseType);
    }

    public <T> T put(String path, Object body, Class<T> responseType) {
        return send("PUT", path, body, responseType);
    }

    private <T> T send(String method, String path, Object body, Class<T> responseType) {
        URI uri = URI.create(baseUrl + path);
        Span span = Telemetry.tracer().spanBuilder(method + " " + serviceName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.request.method", method)
                .setAttribute("url.full", uri.toString())
                .setAttribute("server.address", uri.getHost())
                .setAttribute("server.port", (long) uri.getPort())
                .setAttribute("peer.service", serviceName)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", "application/json");
            if (body != null) {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            } else {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            }
            // put the current trace context into the "traceparent" request header
            Telemetry.propagator().inject(Context.current(), request, (builder, key, value) -> builder.header(key, value));

            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            span.setAttribute("http.response.status_code", (long) response.statusCode());
            if (response.statusCode() >= 400) {
                span.setStatus(StatusCode.ERROR);
                throw new DownstreamException(serviceName, response.statusCode(), errorMessage(response.body()));
            }
            if (responseType == Void.class || response.body().isEmpty()) {
                return null;
            }
            return JSON.readValue(response.body(), responseType);
        } catch (HttpTimeoutException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw new DownstreamException(serviceName, 0, serviceName + " did not answer within " + timeout.toMillis() + " ms");
        } catch (IOException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw new DownstreamException(serviceName, 0, serviceName + " is unreachable: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            span.setStatus(StatusCode.ERROR);
            throw new DownstreamException(serviceName, 0, "interrupted while calling " + serviceName);
        } finally {
            span.end();
        }
    }

    /** Services answer errors as {"error": "..."}; fall back to the raw body otherwise. */
    private static String errorMessage(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            if (node != null && node.hasNonNull("error")) {
                return node.get("error").asText();
            }
        } catch (IOException ignored) {
            // not JSON
        }
        return body == null || body.isBlank() ? "no details" : body;
    }
}
