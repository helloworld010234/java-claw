package com.tinyclaw.ports.reporter;

import com.tinyclaw.domain.message.Usage;

/**
 * Boundary model for reporting the completion of an agent run.
 *
 * <p>Deliberately lives in the {@code ports} layer so that {@link Reporter}
 * implementations do not need to depend on {@code application.engine.AgentRunResult}.</p>
 *
 * @param success      true if the run completed normally
 * @param finalMessage the last assistant message content
 * @param turnCount    number of turns executed
 * @param errorReason  failure reason (non-null only when success is false)
 * @param totalUsage   aggregated token usage across all LLM calls in this run (null if none reported)
 */
public record RunReportResult(boolean success, String finalMessage, int turnCount, String errorReason, Usage totalUsage) {

    public RunReportResult(boolean success, String finalMessage, int turnCount, String errorReason) {
        this(success, finalMessage, turnCount, errorReason, null);
    }

    public RunReportResult {
        if (turnCount < 0) {
            throw new IllegalArgumentException("turnCount must not be negative");
        }
        finalMessage = finalMessage != null ? finalMessage : "";
    }
}
