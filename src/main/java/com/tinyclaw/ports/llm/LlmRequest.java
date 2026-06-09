package com.tinyclaw.ports.llm;

import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.ToolDefinition;

import java.util.List;
import java.util.Objects;

/**
 * Request payload for {@link LlmGateway#generate(LlmRequest)}.
 *
 * @param model   the model identifier (e.g., "gpt-4o")
 * @param messages conversation history including system, user, assistant, and tool observations
 * @param tools   available tool definitions for this turn
 * @param options generation options
 */
public record LlmRequest(String model, List<Message> messages, List<ToolDefinition> tools, LlmRequestOptions options) {

    public LlmRequest {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(tools, "tools must not be null");
        Objects.requireNonNull(options, "options must not be null");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }
}
