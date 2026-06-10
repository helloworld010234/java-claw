package com.tinyclaw.ports.tool;

import com.tinyclaw.domain.common.DomainGuards;

import java.nio.file.Path;

/**
 * Execution context shared by tools during one tool call.
 */
public record ToolExecutionContext(Path workspaceRoot, String runId, String sessionId) {

    public ToolExecutionContext {
        DomainGuards.requireNonNull(workspaceRoot, "workspaceRoot");
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /**
     * Creates a context with only the workspace root.
     * Run and session IDs are left null.
     */
    public ToolExecutionContext(Path workspaceRoot) {
        this(workspaceRoot, null, null);
    }

    /**
     * Returns a new context with the given run and session identifiers.
     */
    public ToolExecutionContext withRun(String runId, String sessionId) {
        return new ToolExecutionContext(workspaceRoot, runId, sessionId);
    }
}
