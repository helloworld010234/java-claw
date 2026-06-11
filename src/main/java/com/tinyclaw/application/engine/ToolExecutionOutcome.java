package com.tinyclaw.application.engine;

import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;

import java.time.Instant;

/**
 * Structured outcome of a single tool execution within the agent loop.
 *
 * <p>Captures the exact execution window ({@code startedAt}..{@code completedAt})
 * so that audit records are accurate, not approximated.</p>
 *
 * @param toolCall    the original tool call
 * @param toolResult  the result (success or failure)
 * @param startedAt   instant when the tool actually started executing
 * @param completedAt instant when the tool finished executing
 */
public record ToolExecutionOutcome(
    ToolCall toolCall,
    ToolResult toolResult,
    Instant startedAt,
    Instant completedAt
) {}
