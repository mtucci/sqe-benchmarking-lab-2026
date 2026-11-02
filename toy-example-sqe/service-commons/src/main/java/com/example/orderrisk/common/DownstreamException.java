package com.example.orderrisk.common;

/**
 * Thrown by {@link ServiceHttpClient} when a call to another microservice fails.
 * {@code status} is the HTTP status returned by the downstream service, or 0 when the
 * service could not be reached at all (connection refused, timeout).
 */
public class DownstreamException extends RuntimeException {

    private final String service;
    private final int status;

    public DownstreamException(String service, int status, String message) {
        super(message);
        this.service = service;
        this.status = status;
    }

    public String service() {
        return service;
    }

    public int status() {
        return status;
    }

    /** True for 4xx answers, i.e. the downstream service rejected the request content. */
    public boolean isClientError() {
        return status >= 400 && status < 500;
    }
}
