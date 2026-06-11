package com.tinyclaw.adapters.web.dto;

import java.time.Instant;

/**
 * GET /api/v1/runs/{runId} 响应体。
 *
 * <p>查询 Agent Run 的当前状态。</p>
 */
public record RunStatusResponse(
    String runId,
    String status,
    int turnCount,
    String errorReason,
    Instant createdAt,
    Instant completedAt
) {
}
