package com.tinyclaw.adapters.reporter;

import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.ports.reporter.Reporter;

/**
 * No-op reporter for tests that do not care about progress events.
 */
public class NoOpReporter implements Reporter {

    @Override
    public void onThinkingStarted(String runId) {
        // no-op
    }

    @Override
    public void onAssistantMessage(String runId, String content) {
        // no-op
    }

    @Override
    public void onToolCall(String runId, ToolCall toolCall) {
        // no-op
    }

    @Override
    public void onToolResult(String runId, ToolResult toolResult) {
        // no-op
    }

    @Override
    public void onRunCompleted(String runId, AgentRunResult result) {
        // no-op
    }

    @Override
    public void onRunFailed(String runId, String reason) {
        // no-op
    }

    @Override
    public void onUsage(String runId, String sessionId, Usage usage, String model) {
        // no-op
    }
}
