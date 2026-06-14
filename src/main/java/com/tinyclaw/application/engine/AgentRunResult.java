package com.tinyclaw.application.engine;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.Usage;

/**
 * Result of a single agent run.
 *
 * @param success             true if the run completed normally
 * @param finalMessage        the last assistant message content
 * @param turnCount           number of turns executed
 * @param errorReason         failure reason (non-null only when success is false)
 * @param totalUsage          aggregated token usage across all LLM calls in this run (null if none reported)
 * @param waitingForApproval  true if the run is paused waiting for human approval
 */
public record AgentRunResult(boolean success,
                             String finalMessage,
                             int turnCount,
                             String errorReason,
                             Usage totalUsage,
                             boolean waitingForApproval) {

    public AgentRunResult(boolean success, String finalMessage, int turnCount, String errorReason) {
        this(success, finalMessage, turnCount, errorReason, null, false);
    }

    public AgentRunResult(boolean success, String finalMessage, int turnCount, String errorReason, Usage totalUsage) {
        this(success, finalMessage, turnCount, errorReason, totalUsage, false);
    }

    public AgentRunResult {
        DomainGuards.requireNonNegative(turnCount, "turnCount");
        finalMessage = finalMessage != null ? finalMessage : "";
    }

    /**
     * Creates a result representing a run paused for approval.
     *
     * @param approvalId the approval ID the operator must act on
     * @param toolName   the tool that requires approval
     * @param turnCount  the turn at which the run paused
     * @return a waiting-for-approval result
     */
    public static AgentRunResult waitingForApproval(String approvalId, String toolName, int turnCount) {
        DomainGuards.requireNonBlank(approvalId, "approvalId");
        DomainGuards.requireNonBlank(toolName, "toolName");
        String reason = "Approval required: " + approvalId + " for tool " + toolName;
        return new AgentRunResult(false, "", turnCount, reason, null, true);
    }
}
