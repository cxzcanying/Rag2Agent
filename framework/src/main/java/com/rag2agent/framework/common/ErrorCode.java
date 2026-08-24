package com.rag2agent.framework.common;

public enum ErrorCode {
    SUCCESS("0", "success"),
    BAD_REQUEST("400", "bad request"),
    UNAUTHORIZED("401", "unauthorized"),
    FORBIDDEN("403", "forbidden"),
    NOT_FOUND("404", "not found"),
    RATE_LIMITED("429", "too many requests"),
    UPSTREAM_UNAVAILABLE("503", "service unavailable"),
    INTERNAL_ERROR("500", "internal server error");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
