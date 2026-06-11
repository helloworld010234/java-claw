package com.tinyclaw.ports.reporter;

import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.message.Usage;

/**
 * Port for reporting agent run lifecycle events.
 *
 * <p>Allows CLI, ChatOps, and test adapters to observe progress without
 * coupling to the engine internals.</p>
 */
public interface Reporter {

    void onThinkingStarted(String runId);

    void onAssistantMessage(String runId, String content);

    void onToolCall(String runId, ToolCall toolCall);

    void onToolResult(String runId, ToolResult toolResult);

    void onRunCompleted(String runId, AgentRunResult result);

    void onRunFailed(String runId, String reason);

    void onUsage(String runId, String sessionId, Usage usage, String model);
}
