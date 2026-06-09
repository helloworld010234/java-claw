package com.tinyclaw.application.engine;

import com.tinyclaw.domain.common.DomainGuards;

/**
 * Result of a single agent run.
 *
 * @param success     true if the run completed normally
 * @param finalMessage the last assistant message content
 * @param turnCount   number of turns executed
 * @param errorReason failure reason (non-null only when success is false)
 */
public record AgentRunResult(boolean success, String finalMessage, int turnCount, String errorReason) {

    public AgentRunResult {
        DomainGuards.requireNonNegative(turnCount, "turnCount");
        finalMessage = finalMessage != null ? finalMessage : "";
    }
}
