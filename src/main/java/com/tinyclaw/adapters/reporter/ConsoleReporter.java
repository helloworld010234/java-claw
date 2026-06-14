package com.tinyclaw.adapters.reporter;

import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.reporter.RunReportResult;

/**
 * Reporter that prints events to standard output for CLI usage.
 */
public class ConsoleReporter implements Reporter {

    @Override
    public void onThinkingStarted(String runId) {
        System.out.println("[run " + runId + "] Thinking...");
    }

    @Override
    public void onAssistantMessage(String runId, String content) {
        if (!content.isBlank()) {
            System.out.println("[run " + runId + "] Assistant: " + content);
        }
    }

    @Override
    public void onToolCall(String runId, ToolCall toolCall) {
        System.out.println("[run " + runId + "] Tool call: " + toolCall.name() + " (" + toolCall.id() + ")");
    }

    @Override
    public void onToolResult(String runId, ToolResult toolResult) {
        String status = toolResult.error() ? "FAILED" : "OK";
        System.out.println("[run " + runId + "] Tool result [" + status + "]: " + toolResult.output());
    }

    @Override
    public void onRunCompleted(String runId, RunReportResult result) {
        System.out.println("[run " + runId + "] Completed in " + result.turnCount() + " turn(s).");
    }

    @Override
    public void onRunFailed(String runId, String reason) {
        System.out.println("[run " + runId + "] Failed: " + reason);
    }

    @Override
    public void onRunWaitingForApproval(String runId, String approvalId, String toolName, String argsPreview) {
        System.out.println("[run " + runId + "] Waiting for approval " + approvalId
            + " (tool: " + toolName + ")");
    }

    @Override
    public void onUsage(String runId, String sessionId, Usage usage, String model) {
        System.out.println("[run " + runId + "] Usage: " + usage.promptTokens()
            + " prompt / " + usage.completionTokens() + " completion tokens"
            + (model != null && !model.isBlank() ? " (model: " + model + ")" : ""));
    }
}
