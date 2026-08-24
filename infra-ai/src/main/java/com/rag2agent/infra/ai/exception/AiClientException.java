package com.rag2agent.infra.ai.exception;

public class AiClientException extends RuntimeException {

    public enum Kind {
        TIMEOUT,
        RATE_LIMITED,
        UPSTREAM_ERROR,
        CLIENT_ERROR,
        CIRCUIT_OPEN,
        BULKHEAD_REJECTED
    }

    private final Kind kind;
    private final int statusCode;
    private final long retryAfterSeconds;

    public AiClientException(String message) {
        this(message, null, Kind.CLIENT_ERROR, 0, 0);
    }

    public AiClientException(String message, Throwable cause) {
        this(message, cause, Kind.CLIENT_ERROR, 0, 0);
    }

    public AiClientException(String message, Kind kind, int statusCode, long retryAfterSeconds) {
        this(message, null, kind, statusCode, retryAfterSeconds);
    }

    public AiClientException(String message, Throwable cause, Kind kind, int statusCode, long retryAfterSeconds) {
        super(message, cause);
        this.kind = kind;
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Kind kind() {
        return kind;
    }

    public int statusCode() {
        return statusCode;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
