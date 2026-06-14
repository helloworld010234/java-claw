package com.tinyclaw.ports.reporter;

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

    void onRunCompleted(String runId, RunReportResult result);

    void onRunFailed(String runId, String reason);

    /**
     * Called when a run is paused because a tool call requires human approval.
     *
     * @param runId          the run identifier
     * @param approvalId     the approval request identifier
     * @param toolName       the tool that was intercepted
     * @param argsPreview    sanitized preview of the tool arguments
     */
    default void onRunWaitingForApproval(String runId, String approvalId, String toolName, String argsPreview) {
        // no-op by default; reporters that care can override
    }

    void onUsage(String runId, String sessionId, Usage usage, String model);
}
