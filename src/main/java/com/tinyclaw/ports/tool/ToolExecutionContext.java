package com.tinyclaw.ports.tool;

import com.tinyclaw.domain.common.DomainGuards;

import java.nio.file.Path;

/**
 * Execution context shared by tools during one tool call.
 */
public record ToolExecutionContext(Path workspaceRoot, String runId, String sessionId, String approvedApprovalId) {

    public ToolExecutionContext {
        DomainGuards.requireNonNull(workspaceRoot, "workspaceRoot");
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /**
     * Creates a context with only the workspace root.
     * Run and session IDs are left null.
     */
    public ToolExecutionContext(Path workspaceRoot) {
        this(workspaceRoot, null, null, null);
    }

    /**
     * Creates a context with workspace root, run ID and session ID.
     * Approved approval ID is left null.
     */
    public ToolExecutionContext(Path workspaceRoot, String runId, String sessionId) {
        this(workspaceRoot, runId, sessionId, null);
    }

    /**
     * Returns a new context with the given run and session identifiers.
     */
    public ToolExecutionContext withRun(String runId, String sessionId) {
        return new ToolExecutionContext(workspaceRoot, runId, sessionId, approvedApprovalId);
    }

    /**
     * Returns a new context with the given approved approval identifier.
     * Used when resuming a tool call that has already been approved.
     */
    public ToolExecutionContext withApprovedApproval(String approvalId) {
        return new ToolExecutionContext(workspaceRoot, runId, sessionId, approvalId);
    }
}
