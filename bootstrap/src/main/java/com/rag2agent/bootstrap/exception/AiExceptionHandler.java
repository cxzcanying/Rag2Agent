package com.rag2agent.bootstrap.exception;

import com.rag2agent.framework.common.ApiResponse;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.infra.ai.exception.AiClientException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiExceptionHandler {

    @ExceptionHandler(AiClientException.class)
    public ResponseEntity<ApiResponse<Void>> handle(AiClientException exception) {
        ErrorCode code = switch (exception.kind()) {
            case RATE_LIMITED -> ErrorCode.RATE_LIMITED;
            case CLIENT_ERROR -> ErrorCode.BAD_REQUEST;
            default -> ErrorCode.UPSTREAM_UNAVAILABLE;
        };
        HttpStatus status = switch (exception.kind()) {
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case CLIENT_ERROR -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        HttpHeaders headers = new HttpHeaders();
        if (exception.retryAfterSeconds() > 0) {
            headers.set("Retry-After", String.valueOf(exception.retryAfterSeconds()));
        }
        String message = exception.kind() == AiClientException.Kind.TIMEOUT
                ? "模型服务响应超时，请稍后重试"
                : exception.kind() == AiClientException.Kind.RATE_LIMITED
                        ? "模型服务限流，请稍后重试"
                        : exception.kind() == AiClientException.Kind.UPSTREAM_ERROR
                                ? "模型服务暂时不可用，请稍后重试"
                                : exception.kind() == AiClientException.Kind.CIRCUIT_OPEN
                                        ? "模型服务熔断中，请稍后重试"
                                        : exception.kind() == AiClientException.Kind.BULKHEAD_REJECTED
                                                ? "模型并发已达上限，请稍后重试"
                                                : "模型请求参数无效";
        return new ResponseEntity<>(ApiResponse.failure(code, message), headers, status);
    }
}
