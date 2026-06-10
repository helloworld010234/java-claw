package com.tinyclaw.application.engine;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.Usage;

/**
 * Result of a single agent run.
 *
 * @param success     true if the run completed normally
 * @param finalMessage the last assistant message content
 * @param turnCount   number of turns executed
 * @param errorReason failure reason (non-null only when success is false)
 * @param totalUsage  aggregated token usage across all LLM calls in this run (null if none reported)
 */
public record AgentRunResult(boolean success, String finalMessage, int turnCount, String errorReason, Usage totalUsage) {

    public AgentRunResult(boolean success, String finalMessage, int turnCount, String errorReason) {
        this(success, finalMessage, turnCount, errorReason, null);
    }

    public AgentRunResult {
        DomainGuards.requireNonNegative(turnCount, "turnCount");
        finalMessage = finalMessage != null ? finalMessage : "";
    }
}
