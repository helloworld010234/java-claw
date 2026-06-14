package com.tinyclaw.application.approval;

import com.tinyclaw.domain.run.AgentRunStatus;

/**
 * Result of attempting to resume an approved tool call.
 *
 * @param approvalId   the approval ID that was resumed
 * @param runId        the run ID associated with the approval
 * @param toolCallId   the tool call ID that was resumed
 * @param toolName     the tool name that was resumed
 * @param resumed      true if the resume attempt was processed
 * @param toolError    true if the underlying tool execution failed
 * @param runStatus    the resulting run status
 * @param output       the tool output on success, or error message on failure
 * @param finalMessage the final assistant message when the run continued to completion
 */
public record ApprovalResumeResult(
    String approvalId,
    String runId,
    String toolCallId,
    String toolName,
    boolean resumed,
    boolean toolError,
    AgentRunStatus runStatus,
    String output,
    String finalMessage
) {

    public ApprovalResumeResult(String approvalId,
                                String runId,
                                String toolCallId,
                                String toolName,
                                boolean resumed,
                                boolean toolError,
                                AgentRunStatus runStatus,
                                String output) {
        this(approvalId, runId, toolCallId, toolName, resumed, toolError, runStatus, output, "");
    }
}
