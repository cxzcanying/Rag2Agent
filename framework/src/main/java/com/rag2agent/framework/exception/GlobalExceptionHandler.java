package com.rag2agent.framework.exception;

import com.rag2agent.framework.common.ApiResponse;
import com.rag2agent.framework.common.ErrorCode;
import cn.dev33.satoken.exception.NotLoginException;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * @author 21311
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.errorCode() == null ? ErrorCode.INTERNAL_ERROR : exception.errorCode();
        if (errorCode == ErrorCode.INTERNAL_ERROR) {
            log.error("业务处理失败，错误码={}", errorCode.code(), exception);
        }
        String message = errorCode == ErrorCode.INTERNAL_ERROR
                ? "服务内部错误，请稍后重试"
                : (exception.getMessage() == null || exception.getMessage().isBlank()
                        ? errorCode.message()
                        : exception.getMessage());
        return ResponseEntity.status(httpStatus(errorCode)).body(ApiResponse.failure(errorCode, message));
    }

    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleNotLoginException(NotLoginException exception) {
        return ApiResponse.failure(ErrorCode.UNAUTHORIZED, "未登录或登录已过期");
    }

    @ExceptionHandler({
        BindException.class,
        ConstraintViolationException.class,
        MethodArgumentNotValidException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidationException(Exception exception) {
        return ApiResponse.failure(ErrorCode.BAD_REQUEST, validationMessage(exception));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception exception) {
        log.error("未处理的服务异常", exception);
        return ApiResponse.failure(ErrorCode.INTERNAL_ERROR, "服务内部错误，请稍后重试");
    }

    private HttpStatus httpStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case SUCCESS, BAD_REQUEST -> HttpStatus.BAD_REQUEST;
        };
    }

    private String validationMessage(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
            return methodArgumentNotValidException.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + " " + error.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        }
        if (exception instanceof BindException bindException) {
            return bindException.getFieldErrors().stream()
                    .map(error -> error.getField() + " " + error.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        }
        return exception.getMessage();
    }
}
