package com.tinyclaw.ports.persistence;

import java.time.Instant;

/**
 * DTO for a tool execution audit record.
 */
public record ToolExecutionRecord(
    String id,
    String runId,
    String sessionId,
    String stepId,
    String toolName,
    String argumentsJson,
    String output,
    boolean isError,
    Instant startedAt,
    Instant completedAt
) {}
