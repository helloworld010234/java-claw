package com.tinyclaw.adapters.web;

import com.tinyclaw.adapters.web.dto.ApiErrorResponse;
import com.tinyclaw.domain.common.TinyClawDomainException;
import com.tinyclaw.ports.llm.LlmException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;

/**
 * 统一全局异常处理。
 *
 * <p>将所有异常转换为标准 {@link ApiErrorResponse} JSON 响应，
 * 避免堆栈信息泄露到客户端。</p>
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TinyClawDomainException.class)
    public ResponseEntity<ApiErrorResponse> handleDomainException(TinyClawDomainException e) {
        log.warn("Domain error: {}", e.getMessage());
        return ResponseEntity.badRequest()
            .body(ApiErrorResponse.of("DOMAIN_ERROR", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .findFirst()
            .orElse("Validation failed");
        return ResponseEntity.badRequest()
            .body(ApiErrorResponse.of("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
            .findFirst()
            .orElse("Constraint violation");
        return ResponseEntity.badRequest()
            .body(ApiErrorResponse.of("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<ApiErrorResponse> handleLlmException(LlmException e) {
        log.error("LLM error: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ApiErrorResponse.of("LLM_ERROR", "LLM service unavailable: " + e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        return ResponseEntity.badRequest()
            .body(ApiErrorResponse.of("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
