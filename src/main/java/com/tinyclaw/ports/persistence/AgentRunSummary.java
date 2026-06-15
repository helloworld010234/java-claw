package com.tinyclaw.ports.persistence;

import com.tinyclaw.domain.run.AgentRunStatus;

import java.time.Instant;

/**
 * DTO for querying an agent run summary.
 */
public record AgentRunSummary(
    String id,
    String sessionId,
    String mode,
    AgentRunStatus status,
    int turnCount,
    String prompt,
    String errorReason,
    Instant startedAt,
    Instant completedAt,
    String approvalId
) {}
