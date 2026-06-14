package com.tinyclaw.domain.tool;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.ToolCall;

/**
 * Exception thrown when a tool call requires human approval before it can execute.
 *
 * <p>Carries enough context for the engine to pause the run, persist the waiting
 * state, and for the resume service to later re-execute the same tool call.</p>
 */
public class ToolApprovalRequiredException extends RuntimeException {

    private final String approvalId;
    private final String runId;
    private final String sessionId;
    private final ToolCall toolCall;
    private final String argumentsPreview;

    public ToolApprovalRequiredException(String approvalId,
                                         String runId,
                                         String sessionId,
                                         ToolCall toolCall,
                                         String argumentsPreview) {
        super("Approval required: " + approvalId + " for tool " + toolName(toolCall));
        this.approvalId = DomainGuards.requireNonBlank(approvalId, "approvalId");
        this.runId = runId != null ? runId : "";
        this.sessionId = sessionId != null ? sessionId : "";
        this.toolCall = DomainGuards.requireNonNull(toolCall, "toolCall");
        this.argumentsPreview = argumentsPreview != null ? argumentsPreview : "";
    }

    public String approvalId() {
        return approvalId;
    }

    public String runId() {
        return runId;
    }

    public String sessionId() {
        return sessionId;
    }

    public ToolCall toolCall() {
        return toolCall;
    }

    public String argumentsPreview() {
        return argumentsPreview;
    }

    private static String toolName(ToolCall toolCall) {
        return toolCall != null ? toolCall.name() : "unknown";
    }
}
