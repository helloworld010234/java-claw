package com.tinyclaw.adapters.web.dto;

import java.time.Instant;

/**
 * 统一 API 错误响应体。
 *
 * <p>由 {@link com.tinyclaw.adapters.web.GlobalExceptionHandler} 统一包装返回。</p>
 */
public record ApiErrorResponse(
    String code,
    String message,
    Instant timestamp
) {

    /**
     * 创建错误响应，时间戳自动设置为当前时间。
     */
    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(code, message, Instant.now());
    }
}
