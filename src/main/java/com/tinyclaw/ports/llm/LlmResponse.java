package com.tinyclaw.ports.llm;

import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.Usage;

import java.util.List;
import java.util.Objects;

/**
 * Response from an LLM generation call.
 *
 * @param content   assistant text content (may be empty, never null)
 * @param toolCalls tool calls requested by the assistant (empty if none)
 * @param usage     token usage statistics (null if provider does not report)
 */
public record LlmResponse(String content, List<ToolCall> toolCalls, Usage usage) {

    public LlmResponse {
        Objects.requireNonNull(content, "content must not be null");
        toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
    }

    /**
     * Returns true if the assistant requested at least one tool call.
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
