package com.rag2agent.bootstrap.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag2agent.framework.common.ApiResponse;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import com.rag2agent.framework.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionUsesErrorCodeHttpStatus() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.NOT_FOUND, "资源不存在"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(ErrorCode.NOT_FOUND.code(), response.getBody().code());
        assertEquals("资源不存在", response.getBody().message());
    }

    @Test
    void internalErrorsDoNotExposeImplementationMessage() {
        ResponseEntity<ApiResponse<Void>> businessResponse = handler.handleBusinessException(
                new BusinessException(ErrorCode.INTERNAL_ERROR, "database password=secret"));
        ApiResponse<Void> genericResponse = handler.handleException(new IllegalStateException("connection details"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, businessResponse.getStatusCode());
        assertEquals("服务内部错误，请稍后重试", businessResponse.getBody().message());
        assertEquals("服务内部错误，请稍后重试", genericResponse.message());
        assertTrue(!businessResponse.getBody().message().contains("secret"));
    }
}
