package com.tinyclaw.ports.tool;

import com.tinyclaw.domain.common.DomainGuards;

import java.nio.file.Path;

/**
 * Execution context shared by tools during one tool call.
 */
public record ToolExecutionContext(Path workspaceRoot) {

    public ToolExecutionContext {
        DomainGuards.requireNonNull(workspaceRoot, "workspaceRoot");
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }
}
